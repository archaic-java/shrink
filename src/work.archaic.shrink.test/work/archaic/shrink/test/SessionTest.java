package work.archaic.shrink.test;

import jakarta.json.*;
import java.io.*;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;
import work.archaic.service.compiler.v01.*;
import work.archaic.service.logging.v02.*;
import work.archaic.service.test.v02.*;
import work.archaic.shrink.server.Session;

public record SessionTest() implements TestSuite {
  @Override public void cases(Collection<TestCase> cases) {
    cases.add(new StaleCompletionCannotPublishAfterCloseReopen());
    cases.add(new AdapterFailureIsNotAnEmptySuccess());
    cases.add(new ShutdownDoesNotWaitForAnUncooperativeParser());
  }
}

final class SessionGoals {
  private SessionGoals() {}

  record Instrumentation(Goal analyze, Diagnostics diagnostics, Log log) {}

  static Instrumentation instrumentation() {
    var diagnostics = exactlyOne(Diagnostics.class);
    Log log = exactlyOne(Log.class);
    return new Instrumentation(diagnostics.goal("diagnostics.analyze", log), diagnostics, log);
  }

  private static <T> T exactlyOne(Class<T> service) {
    var providers = java.util.ServiceLoader.load(service).stream().toList();
    if (providers.size() != 1) throw new IllegalStateException("Expected one " + service.getName());
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
  final WireClient client = new WireClient(clientInput, clientOutput);

  RunningSession(CompilerAdapter compiler) throws IOException {
    var instrumentation = SessionGoals.instrumentation();
    server = Thread.ofVirtual().start(() -> {
      try { status.complete(new Session(serverInput, serverOutput, compiler, instrumentation.analyze(), instrumentation.diagnostics(), instrumentation.log()).run()); }
      catch (Exception failure) { status.completeExceptionally(failure); }
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
    try (var running = new RunningSession(source -> { throw new ParseException("Deliberate test failure", null); })) {
      var client = running.client;
      client.initialize(true);
      client.open(uri, 1, "class A {}");
      var failure = client.receive();
      assert failure.getString("method").equals("window/showMessage") : "Adapter failures must notify the editor";
      assert failure.getJsonObject("params").getInt("type") == 1 : "Adapter failures must use the error message type";
      client.shutdown();
      assert running.status.get(10, TimeUnit.SECONDS) == 0 : "Server must remain usable after reporting adapter failure";
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
