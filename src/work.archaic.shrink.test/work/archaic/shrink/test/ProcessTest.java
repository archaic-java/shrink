package work.archaic.shrink.test;

import jakarta.json.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import work.archaic.service.test.v01.*;

public final class ProcessTest implements TestSuite {
  private static final URI DOCUMENT = URI.create("file:///does-not-exist/Demo.java");

  private static final class Server implements AutoCloseable {
    final Path directory = Files.createTempDirectory("shrink-test-");
    final Process process;
    final WireClient client;
    final ByteArrayOutputStream errors = new ByteArrayOutputStream();
    final Thread errorReader;

    Server() throws IOException {
      String modules = Path.of("out").toAbsolutePath() + ":" + Path.of("lib/bin").toAbsolutePath();
      process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "--module-path", modules, "--add-modules", "org.eclipse.parsson",
          "-m", "work.archaic.shrink/work.archaic.shrink.Main")
          .directory(directory.toFile()).start();
      errorReader = Thread.ofVirtual().start(() -> {
        try { process.getErrorStream().transferTo(errors); } catch (IOException ignored) {}
      });
      client = new WireClient(process.getInputStream(), process.getOutputStream());
    }

    void finish(int expected) throws Exception {
      assert process.waitFor(10, TimeUnit.SECONDS) : "Server did not exit";
      errorReader.join();
      assert process.exitValue() == expected : errors.toString();
      try (var files = Files.list(directory)) { assert files.findAny().isEmpty() : "Server wrote files"; }
    }

    @Override public void close() throws Exception {
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
      client.close();
      errorReader.join(1000);
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      }
    }
  }

  @Test public void unsavedErrorsArePublishedAndCleared() throws Exception {
    try (var server = new Server()) {
      var c = server.client;
      c.initialize(true);
      c.open(DOCUMENT, 1, "class Demo { String s = \"Grüße 😀\"; int n = ; }");
      var bad = c.diagnostics();
      assert bad.getString("uri").equals(DOCUMENT.toString());
      assert bad.getInt("version") == 1;
      assert !bad.getJsonArray("diagnostics").isEmpty();
      assert bad.getJsonArray("diagnostics").getJsonObject(0).getString("source").equals("shrink");
      c.change(DOCUMENT, 2, "class Demo { Missing unresolved; }");
      var fixed = c.diagnostics();
      assert fixed.getInt("version") == 2;
      assert fixed.getJsonArray("diagnostics").isEmpty() : fixed;
      c.closeDocument(DOCUMENT);
      assert c.diagnostics().getJsonArray("diagnostics").isEmpty();
      c.shutdown();
      server.finish(0);
      assert server.errors.size() == 0 : server.errors;
    }
  }

  @Test public void protocolErrorsDoNotBreakTheSession() throws Exception {
    try (var server = new Server()) {
      var c = server.client;
      c.raw("{");
      assert c.receive().getJsonObject("error").getInt("code") == -32700;
      c.raw("[]");
      assert c.receive().getJsonObject("error").getInt("code") == -32600;
      c.raw("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
      assert c.receive().getJsonObject("error").getInt("code") == -32602;
      c.raw("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unknown\"}");
      assert c.receive().getJsonObject("error").getInt("code") == -32002;
      c.initialize(false);
      c.raw("{\"jsonrpc\":\"2.0\",\"method\":\"unknown\"}");
      c.raw("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{}}");
      c.raw("{\"jsonrpc\":\"2.0\",\"id\":\"barrier\",\"method\":\"unknown\"}");
      var response = c.receive();
      assert response.getString("id").equals("barrier") : response;
      assert response.getJsonObject("error").getInt("code") == -32601;
      c.open(DOCUMENT, 1, "class Demo {}");
      var diagnostics = c.diagnostics();
      assert !diagnostics.containsKey("version");
      c.shutdown();
      server.finish(0);
    }
  }

  @Test public void closeReopenAndRepeatedEditsWorkOverStdio() throws Exception {
    try (var server = new Server()) {
      var c = server.client;
      c.initialize(true);
      c.open(DOCUMENT, 1, "class Demo {}");
      c.diagnostics();
      c.closeDocument(DOCUMENT);
      assert c.diagnostics().getJsonArray("diagnostics").isEmpty();
      c.open(DOCUMENT, 1, "class Demo { int x = ; }");
      assert !c.diagnostics().getJsonArray("diagnostics").isEmpty();
      for (int i = 2; i <= 50; i++) c.change(DOCUMENT, i, "class Demo { int x = " + i + "; }");
      JsonObject result;
      do { result = c.diagnostics(); } while (result.getInt("version") < 50);
      assert result.getInt("version") == 50;
      assert result.getJsonArray("diagnostics").isEmpty();
      c.shutdown();
      server.finish(0);
    }
  }

  @Test public void abnormalExitAndBrokenFramingFail() throws Exception {
    try (var server = new Server()) {
      server.client.raw("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
      server.finish(1);
    }
    try (var server = new Server()) {
      server.process.getOutputStream().write("Content-Length: -1\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      server.process.getOutputStream().flush();
      server.finish(1);
      assert server.errors.size() > 0;
    }
  }
}
