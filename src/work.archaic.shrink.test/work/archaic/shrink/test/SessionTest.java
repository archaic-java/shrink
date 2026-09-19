package work.archaic.shrink.test;

import jakarta.json.*;
import java.io.*;
import java.net.URI;
import java.util.List;
import java.util.concurrent.*;
import work.archaic.service.compiler.v01.*;
import work.archaic.service.test.v01.*;
import work.archaic.shrink.server.Session;

public final class SessionTest implements TestSuite {
  private static final URI URI_A = URI.create("file:///A.java");

  private static final class Running implements AutoCloseable {
    final PipedInputStream serverInput = new PipedInputStream(65536);
    final PipedOutputStream clientOutput = new PipedOutputStream(serverInput);
    final PipedInputStream clientInput = new PipedInputStream(65536);
    final PipedOutputStream serverOutput = new PipedOutputStream(clientInput);
    final CompletableFuture<Integer> status = new CompletableFuture<>();
    final Thread server;
    final WireClient client = new WireClient(clientInput, clientOutput);

    Running(CompilerAdapter compiler) throws IOException {
      server = Thread.ofVirtual().start(() -> {
        try { status.complete(new Session(serverInput, serverOutput, compiler).run()); }
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

  @Test public void staleCompletionCannotPublishAfterCloseReopen() throws Exception {
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
    try (var running = new Running(controlled)) {
      var c = running.client;
      c.initialize(true);
      c.open(URI_A, 1, "old");
      assert entered.await(10, TimeUnit.SECONDS);
      c.closeDocument(URI_A);
      assert c.diagnostics().getJsonArray("diagnostics").isEmpty();
      c.open(URI_A, 1, "new");
      c.raw("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"barrier\"}");
      assert c.receive().getInt("id") == 7;
      release.countDown();
      var result = c.diagnostics();
      assert result.getJsonArray("diagnostics").getJsonObject(0).getString("message").equals("new") : result;
      c.shutdown();
      assert running.status.get(10, TimeUnit.SECONDS) == 0;
    } finally { release.countDown(); }
  }

  @Test public void adapterFailureIsNotAnEmptySuccess() throws Exception {
    try (var running = new Running(source -> { throw new ParseException("Deliberate test failure", null); })) {
      var c = running.client;
      c.initialize(true);
      c.open(URI_A, 1, "class A {}");
      var failure = c.receive();
      assert failure.getString("method").equals("window/showMessage") : failure;
      assert failure.getJsonObject("params").getInt("type") == 1;
      c.shutdown();
      assert running.status.get(10, TimeUnit.SECONDS) == 0;
    }
  }

  @Test public void shutdownDoesNotWaitForAnUncooperativeParser() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var running = new Running(source -> {
      entered.countDown();
      while (true) {
        try { if (release.await(10, TimeUnit.SECONDS)) break; }
        catch (InterruptedException ignored) { /* Deliberately model an uninterruptible compiler. */ }
      }
      return new ParseResult(source, List.of(), List.of());
    })) {
      var c = running.client;
      c.initialize(true);
      c.open(URI_A, 1, "class A {}");
      assert entered.await(10, TimeUnit.SECONDS);
      c.shutdown();
      assert running.status.get(3, TimeUnit.SECONDS) == 0;
    } finally { release.countDown(); }
  }
}
