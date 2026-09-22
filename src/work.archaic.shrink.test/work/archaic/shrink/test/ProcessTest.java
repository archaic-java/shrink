package work.archaic.shrink.test;

import jakarta.json.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import work.archaic.service.test.v02.*;

public record ProcessTest() implements TestSuite {
  @Override public void cases(Collection<TestCase> cases) {
    cases.add(new UnsavedErrorsArePublishedAndCleared());
    cases.add(new ProtocolErrorsDoNotBreakTheSession());
    cases.add(new CloseReopenAndRepeatedEditsWorkOverStdio());
    cases.add(new AbnormalExitAndBrokenFramingFail());
  }
}

final class Server implements AutoCloseable {
  final Path directory = Files.createTempDirectory("shrink-test-");
  final Process process;
  final WireClient client;
  final ByteArrayOutputStream errors = new ByteArrayOutputStream();
  final Thread errorReader;

  Server() throws IOException {
    String modules = Path.of("out").toAbsolutePath() + ":" + Path.of("lib/bin").toAbsolutePath();
    process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "--module-path", modules, "--add-modules", "org.eclipse.parsson,work.archaic.peep,work.archaic.shrink.compiler",
        "-m", "work.archaic.shrink/work.archaic.shrink.Main")
        .directory(directory.toFile()).start();
    errorReader = Thread.ofVirtual().start(() -> {
      try { process.getErrorStream().transferTo(errors); } catch (IOException ignored) {}
    });
    client = new WireClient(process.getInputStream(), process.getOutputStream());
  }

  void finish(int expected) throws Exception {
    assert process.waitFor(10, TimeUnit.SECONDS) : "Server must exit promptly";
    errorReader.join();
    assert process.exitValue() == expected : "Server must exit with " + expected + ": " + errors;
    try (var files = Files.list(directory)) {
      assert files.findAny().isEmpty() : "Server must not write files into its working directory";
    }
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

record UnsavedErrorsArePublishedAndCleared() implements TestCase {
  private static final URI document = URI.create("file:///does-not-exist/Demo.java");

  @Override public void run(TestTrail trail) throws Exception {
    try (var server = new Server()) {
      var client = server.client;
      client.initialize(true);
      client.open(document, 1, "class Demo { String s = \"Grüße 😀\"; int n = ; }");
      var bad = client.diagnostics();
      assert bad.getString("uri").equals(document.toString()) : "Diagnostics must identify the opened document";
      assert bad.getInt("version") == 1 : "Diagnostics must retain the source version";
      assert !bad.getJsonArray("diagnostics").isEmpty() : "Unsaved invalid syntax must produce a diagnostic";
      assert bad.getJsonArray("diagnostics").getJsonObject(0).getString("source").equals("shrink") : "Diagnostics must identify Shrink as their source";
      client.change(document, 2, "class Demo { Missing unresolved; }");
      var fixed = client.diagnostics();
      assert fixed.getInt("version") == 2 : "Fixed diagnostics must use the updated version";
      assert fixed.getJsonArray("diagnostics").isEmpty() : "Fixing syntax must clear diagnostics";
      client.closeDocument(document);
      assert client.diagnostics().getJsonArray("diagnostics").isEmpty() : "Closing a document must clear its diagnostics";
      client.shutdown();
      server.finish(0);
      assert server.errors.size() == 0 : "Successful server session must not write stderr: " + server.errors;
      trail.note("Verified diagnostics for unsaved source in an isolated server process");
    }
  }
}

record ProtocolErrorsDoNotBreakTheSession() implements TestCase {
  private static final URI document = URI.create("file:///does-not-exist/Demo.java");

  @Override public void run(TestTrail trail) throws Exception {
    try (var server = new Server()) {
      var client = server.client;
      client.raw("{");
      assert client.receive().getJsonObject("error").getInt("code") == -32700 : "Malformed JSON must return ParseError";
      client.raw("[]");
      assert client.receive().getJsonObject("error").getInt("code") == -32600 : "Non-object JSON must return InvalidRequest";
      client.raw("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
      assert client.receive().getJsonObject("error").getInt("code") == -32602 : "Invalid initialize parameters must return InvalidParams";
      client.raw("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"unknown\"}");
      assert client.receive().getJsonObject("error").getInt("code") == -32002 : "Requests before initialization must be rejected";
      client.initialize(false);
      client.raw("{\"jsonrpc\":\"2.0\",\"method\":\"unknown\"}");
      client.raw("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didChange\",\"params\":{}}");
      client.raw("{\"jsonrpc\":\"2.0\",\"id\":\"barrier\",\"method\":\"unknown\"}");
      var response = client.receive();
      assert response.getString("id").equals("barrier") : "Barrier response must retain a string request id";
      assert response.getJsonObject("error").getInt("code") == -32601 : "Unknown request must return MethodNotFound";
      client.open(document, 1, "class Demo {}");
      var diagnostics = client.diagnostics();
      assert !diagnostics.containsKey("version") : "Client without version support must receive unversioned diagnostics";
      client.shutdown();
      server.finish(0);
      trail.note("Verified recovery from malformed and unknown protocol messages");
    }
  }
}

record CloseReopenAndRepeatedEditsWorkOverStdio() implements TestCase {
  private static final URI document = URI.create("file:///does-not-exist/Demo.java");

  @Override public void run(TestTrail trail) throws Exception {
    try (var server = new Server()) {
      var client = server.client;
      client.initialize(true);
      client.open(document, 1, "class Demo {}");
      client.diagnostics();
      client.closeDocument(document);
      assert client.diagnostics().getJsonArray("diagnostics").isEmpty() : "Closing the first document generation must clear diagnostics";
      client.open(document, 1, "class Demo { int x = ; }");
      assert !client.diagnostics().getJsonArray("diagnostics").isEmpty() : "Reopened invalid document must produce diagnostics";
      for (int i = 2; i <= 50; i++) client.change(document, i, "class Demo { int x = " + i + "; }");
      JsonObject result;
      do { result = client.diagnostics(); } while (result.getInt("version") < 50);
      assert result.getInt("version") == 50 : "Final diagnostics must describe the newest edit";
      assert result.getJsonArray("diagnostics").isEmpty() : "Newest syntactically valid edit must clear diagnostics";
      client.shutdown();
      server.finish(0);
      trail.note("Verified close/reopen generations and edit coalescing over stdio");
    }
  }
}

record AbnormalExitAndBrokenFramingFail() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    try (var server = new Server()) {
      server.client.raw("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
      server.finish(1);
    }
    try (var server = new Server()) {
      server.process.getOutputStream().write("Content-Length: -1\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      server.process.getOutputStream().flush();
      server.finish(1);
      assert server.errors.size() > 0 : "Broken framing must leave failure evidence on stderr";
    }
    trail.note("Verified abnormal protocol exits and framing failures");
  }
}
