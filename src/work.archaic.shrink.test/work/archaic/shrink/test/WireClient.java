package work.archaic.shrink.test;

import jakarta.json.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import work.archaic.shrink.protocol.Framing;

/** Test-side wire client; reads have deadlines so protocol failures cannot hang the suite. */
final class WireClient implements AutoCloseable {
  private final OutputStream toServer;
  private final InputStream fromServer;
  private final LinkedBlockingQueue<Object> received = new LinkedBlockingQueue<>();
  private final Thread reader;

  WireClient(InputStream fromServer, OutputStream toServer) {
    this.fromServer = fromServer;
    this.toServer = toServer;
    reader = Thread.ofVirtual().start(() -> {
      try {
        byte[] body;
        while ((body = Framing.read(fromServer)) != null) {
          try (var json = Json.createReader(new ByteArrayInputStream(body))) {
            received.add(json.readObject());
          }
        }
        received.add(new EOFException("Server closed stdout"));
      } catch (Exception failure) { received.add(failure); }
    });
  }

  void raw(String json) throws IOException { Framing.write(toServer, json); }

  void send(JsonObject message) throws IOException { raw(message.toString()); }

  JsonObject receive() throws Exception {
    Object next = received.poll(15, TimeUnit.SECONDS);
    if (next instanceof Exception failure) throw new AssertionError("Invalid protocol stream", failure);
    if (!(next instanceof JsonObject object)) throw new AssertionError("Timed out waiting for server");
    return object;
  }

  void initialize(boolean versions) throws Exception {
    raw("{\"jsonrpc\":\"2.0\",\"id\":\"init\",\"method\":\"initialize\",\"params\":{\"capabilities\":{\"textDocument\":{\"publishDiagnostics\":{\"versionSupport\":" + versions + "}}}}}");
    JsonObject result = receive();
    assert result.getString("id").equals("init") : "Initialize response must retain the request id: " + result;
    assert result.getJsonObject("result").getJsonObject("capabilities").getString("positionEncoding").equals("utf-16")
        : "Server must advertise UTF-16 positions";
    raw("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}");
  }

  void open(URI uri, int version, String text) throws IOException {
    notification("textDocument/didOpen", Json.createObjectBuilder().add("textDocument", Json.createObjectBuilder()
        .add("uri", uri.toString()).add("languageId", "java").add("version", version).add("text", text)).build());
  }

  void change(URI uri, int version, String text) throws IOException {
    notification("textDocument/didChange", Json.createObjectBuilder().add("textDocument", Json.createObjectBuilder()
        .add("uri", uri.toString()).add("version", version))
        .add("contentChanges", Json.createArrayBuilder().add(Json.createObjectBuilder().add("text", text))).build());
  }

  void closeDocument(URI uri) throws IOException {
    notification("textDocument/didClose", Json.createObjectBuilder().add("textDocument", Json.createObjectBuilder().add("uri", uri.toString())).build());
  }

  void notification(String method, JsonObject params) throws IOException {
    send(Json.createObjectBuilder().add("jsonrpc", "2.0").add("method", method).add("params", params).build());
  }

  JsonObject diagnostics() throws Exception {
    JsonObject message = receive();
    assert message.getString("method", "").equals("textDocument/publishDiagnostics")
        : "Expected a diagnostics notification: " + message;
    return message.getJsonObject("params");
  }

  void shutdown() throws Exception {
    raw("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"shutdown\"}");
    JsonObject response = receive();
    assert response.getInt("id") == 99 : "Shutdown response must retain its request id: " + response;
    assert response.get("result") == JsonValue.NULL : "Shutdown response must return null: " + response;
    raw("{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}");
  }

  @Override public void close() throws IOException {
    reader.interrupt();
    toServer.close();
    fromServer.close();
  }
}
