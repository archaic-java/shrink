package work.archaic.shrink.test;

import jakarta.json.*;
import java.io.*;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import work.archaic.service.compiler.v01.*;
import work.archaic.service.logging.v03.*;
import work.archaic.service.logging.v03.Configuration;
import work.archaic.service.test.v02.*;
import work.archaic.shrink.server.Session;

public record SessionTest() implements TestSuite {
  @Override public void cases(Collection<TestCase> cases) {
    cases.add(new StaleCompletionCannotPublishAfterCloseReopen());
    cases.add(new AdapterFailureIsNotAnEmptySuccess());
    cases.add(new ShutdownDoesNotWaitForAnUncooperativeParser());
    cases.add(new HandledAnalysisFailureDoesNotFailSession());
    cases.add(new AnalysisDebugUsesWorkerContext());
  }
}

final class SessionLogging {
  private SessionLogging() {}

  static Log provider() {
    var providers = java.util.ServiceLoader.load(Log.class).stream().toList();
    if (providers.size() != 1) throw new IllegalStateException("Expected one Log provider");
    return providers.getFirst().get();
  }
}

final class RunningSession implements AutoCloseable {
  final PipedInputStream serverInput = new PipedInputStream(65536);
  final PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
  final PipedInputStream clientInput = new PipedInputStream(65536);
  final PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
  final CompletableFuture<Integer> status = new CompletableFuture<>();
  final Thread server;
  final List<Entry> entries = new CopyOnWriteArrayList<>();
  final List<FailureReport> reports = new CopyOnWriteArrayList<>();
  final WireClient client = new WireClient(clientInput, clientOutput);

  RunningSession(CompilerAdapter compiler) throws IOException {
    this(compiler, false);
  }

  RunningSession(CompilerAdapter compiler, boolean debug) throws IOException {
    var logging = SessionLogging.provider();
    var configuration = new Configuration(debug, entries::add, reports::add);
    server = Thread.ofVirtual().start(() -> {
      try { status.complete(new Session(serverInput, serverOutput, compiler, logging, configuration).run()); }
      catch (Throwable failure) { status.completeExceptionally(failure); }
    });
  }

  @Override public void close() throws Exception {
    server.interrupt();
    client.close();
    serverInput.close();
    serverOutput.close();
    server.join(1000);
  }
}

record StaleCompletionCannotPublishAfterCloseReopen() implements TestCase {
  private static final URI uri = URI.create("file:///A.java");

  @Override public void run(TestTrail trail) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    CompilerAdapter controlled = source -> {
      if (source.text().equals("old")) {
        entered.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
        catch (InterruptedException interrupted) { throw new ParseException("Interrupted", interrupted); }
      }
      var point = new Position(0, 0);
      return new ParseResult(source, List.of(new Diagnostic(new Range(point, point), Diagnostic.Severity.ERROR, "test", source.text())), List.of());
    };
    try (var running = new RunningSession(controlled)) {
      var client = running.client;
      client.initialize(true);
      client.open(uri, 1, "old");
      assert entered.await(10, TimeUnit.SECONDS) : "Old parse must begin before close/reopen";
      client.closeDocument(uri);
      assert client.diagnostics().getJsonArray("diagnostics").isEmpty() : "Closing the document must clear diagnostics";
      client.open(uri, 1, "new");
      client.raw("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"barrier\"}");
      assert client.receive().getInt("id") == 7 : "Barrier response must arrive before releasing stale parse";
      release.countDown();
      var result = client.diagnostics();
      assert result.getJsonArray("diagnostics").getJsonObject(0).getString("message").equals("new")
          : "Only the reopened document generation may publish diagnostics";
      client.shutdown();
      assert running.status.get(10, TimeUnit.SECONDS) == 0 : "Shutdown after a session must succeed";
      trail.note("Verified close/reopen generation protection against stale completion");
    } finally { release.countDown(); }
  }
}

record AdapterFailureIsNotAnEmptySuccess() implements TestCase {
  private static final URI uri = URI.create("file:///A.java");

  @Override public void run(TestTrail trail) throws Exception {
    var attempted = new java.util.concurrent.atomic.AtomicInteger();
    var original = new ParseException("Deliberate test failure", null);
    try (var running = new RunningSession(source -> {
      if (attempted.getAndIncrement() == 0) throw original;
      return new ParseResult(source, List.of(), List.of());
    })) {
      var client = running.client;
      client.initialize(true);
      client.open(uri, 1, "class A {}");
      var failure = client.receive();
      assert failure.getString("method").equals("window/showMessage") : "Adapter failures must notify the editor";
      assert failure.getJsonObject("params").getInt("type") == 1 : "Adapter failures must use the error message type";
      assert running.reports.size() == 1 && running.reports.getFirst().cause() == original
          : "Analysis must publish the original adapter failure exactly once before notifying the editor";
      var evidence = running.reports.getFirst().evidence();
      assert evidence.size() == 1 && evidence.getFirst().source().endsWith(".Analysis")
          && evidence.getFirst().message().contains("file:///A.java version 1")
          : "Analysis evidence must identify its object, source and snapshot version";
      client.change(uri, 2, "class A {}");
      assert client.diagnostics().getJsonArray("diagnostics").isEmpty()
          : "A subsequent successful parse must still publish its diagnostics";
      client.shutdown();
      assert running.status.get(10, TimeUnit.SECONDS) == 0 : "Server must remain usable after reporting adapter failure";
      assert running.reports.size() == 1 : "A recovered analysis failure must not fail the session context";
      trail.note("Verified adapter failure is not published as empty diagnostics");
    }
  }
}

record ShutdownDoesNotWaitForAnUncooperativeParser() implements TestCase {
  private static final URI uri = URI.create("file:///A.java");

  @Override public void run(TestTrail trail) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var running = new RunningSession(source -> {
      entered.countDown();
      while (true) {
        try { if (release.await(10, TimeUnit.SECONDS)) break; }
        catch (InterruptedException ignored) { /* Deliberately model an uninterruptible compiler. */ }
      }
      return new ParseResult(source, List.of(), List.of());
    })) {
      var client = running.client;
      client.initialize(true);
      client.open(uri, 1, "class A {}");
      assert entered.await(10, TimeUnit.SECONDS) : "Parser must begin before shutdown";
      client.shutdown();
      assert running.status.get(3, TimeUnit.SECONDS) == 0 : "Shutdown must not wait for an uncooperative parser";
      trail.note("Verified bounded shutdown despite ignored interruption");
    } finally { release.countDown(); }
  }
}

record HandledAnalysisFailureDoesNotFailSession() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    try (var running = new RunningSession(source -> {
      Logging.context().fail("Handled adapter failure");
      return new ParseResult(source, List.of(), List.of());
    })) {
      running.client.initialize(true);
      running.client.open(URI.create("file:///Handled.java"), 1, "class Handled {}");
      running.client.diagnostics();
      running.client.shutdown();
      assert running.status.get(10, TimeUnit.SECONDS) == 0 : "Explicit analysis failure must not poison the session";
      assert running.reports.size() == 1 && running.reports.getFirst().cause() == null
          && running.reports.getFirst().explicitFailure().message().equals("Handled adapter failure")
          : "The adapter's deep explicit failure must be published by its independent context";
    }
  }
}

record AnalysisDebugUsesWorkerContext() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    var contexts = new CopyOnWriteArrayList<Context>();
    var threads = new CopyOnWriteArrayList<String>();
    try (var running = new RunningSession(source -> {
      contexts.add(Logging.context());
      threads.add(Thread.currentThread().getName());
      return new ParseResult(source, List.of(), List.of("auxiliary output"));
    }, true)) {
      running.client.initialize(true);
      var uri = URI.create("file:///Debug.java");
      running.client.open(uri, 1, "class Debug {}");
      running.client.diagnostics();
      running.client.change(uri, 2, "class Debug { int n; }");
      running.client.diagnostics();
      running.client.shutdown();
      assert running.status.get(10, TimeUnit.SECONDS) == 0 : "Debug output must not interfere with protocol shutdown";
      assert contexts.size() == 2 && contexts.get(0) != contexts.get(1)
          : "Each analysis attempt must establish a fresh context";
      assert threads.stream().allMatch(name -> name.equals("shrink-parser"))
          : "Analysis and its context must execute on the existing parser thread";
      assert running.reports.isEmpty() : "Successful syntax analysis must discard evidence even with debug enabled";
      assert running.entries.stream().filter(entry -> entry.source().endsWith(".Analysis")).count() == 4
          : "Enabled analysis debug suppliers must publish snapshot and result details";
      assert running.entries.stream().filter(entry -> entry.message().equals("Compiler notice: auxiliary output")).count() == 2
          : "Current compiler auxiliary output must be published on the session output sink";
      try { contexts.getFirst().fail("too late"); assert false : "Completed contexts must reject cross-thread use"; }
      catch (IllegalStateException expected) { }
    }
  }
}
