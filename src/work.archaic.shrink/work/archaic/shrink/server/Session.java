package work.archaic.shrink.server;

import jakarta.json.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import work.archaic.service.compiler.v01.*;
import work.archaic.service.logging.v02.Goal;
import work.archaic.service.logging.v02.Log;
import work.archaic.shrink.protocol.Framing;

/** One session owns all document mutations and all protocol output. */
public final class Session {
  private enum State { NEW, INITIALIZING, ACTIVE, SHUTDOWN }
  private sealed interface Event {}
  private record Message(byte[] body) implements Event {}
  private record End(IOException failure) implements Event {}
  private record Completed(Documents.Work work, ParseResult result, Throwable failure) implements Event {}

  private final InputStream input;
  private final OutputStream output;
  private final CompilerAdapter compiler;
  private final Goal analyze;
  private final Goal publish;
  private final Log log;
  private final Documents documents = new Documents(TimeUnit.MILLISECONDS.toNanos(150));
  private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(16);
  private State state = State.NEW;
  private boolean versionSupport;
  private boolean busy;
  private Integer exitStatus;

  public Session(InputStream input, OutputStream output, CompilerAdapter compiler, Goal analyze, Goal publish, Log log) {
    this.input = input;
    this.output = output;
    this.compiler = compiler;
    this.analyze = analyze;
    this.publish = publish;
    this.log = log;
  }

  public int run() throws IOException, InterruptedException {
    var worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("shrink-parser").factory());
    Thread reader = Thread.ofVirtual().name("shrink-reader").start(() -> {
      try {
        byte[] body;
        while ((body = Framing.read(input)) != null) events.put(new Message(body));
        events.put(new End(null));
      } catch (IOException failure) {
        enqueue(new End(failure));
      } catch (InterruptedException stopped) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      while (exitStatus == null) {
        if (!busy && state == State.ACTIVE) {
          Documents.Work next = documents.takeReady(System.nanoTime());
          if (next != null) {
            busy = true;
            worker.execute(() -> {
              try { analyze.run(() -> analyze(next)); }
              catch (Throwable ignored) { /* The completed event already communicates the failure. */ }
            });
          }
        }
        long wait = !busy && state == State.ACTIVE ? documents.waitNanos(System.nanoTime()) : Long.MAX_VALUE;
        Event event = events.poll(wait, TimeUnit.NANOSECONDS);
        if (event == null) continue;
        switch (event) {
          case Message message -> receive(message.body());
          case Completed completed -> publish.run(() -> completed(completed));
          case End end -> {
            if (end.failure() != null) log.write("Invalid or incomplete LSP stream: " + end.failure());
            exitStatus = end.failure() == null && state == State.SHUTDOWN ? 0 : 1;
          }
        }
      }
      return exitStatus;
    } finally {
      documents.clear();
      reader.interrupt();
      worker.shutdownNow(); // Never await an uncooperative javac task during shutdown.
    }
  }

  private void analyze(Documents.Work work) throws Throwable {
    try {
      ParseResult result = compiler.parse(work.source());
      if (!result.source().equals(work.source())) {
        throw new IllegalStateException("Compiler returned a result for a different source");
      }
      enqueue(new Completed(work, result, null));
    } catch (Throwable failure) {
      enqueue(new Completed(work, null, failure));
      throw failure;
    }
  }

  private void enqueue(Event event) {
    try { events.put(event); }
    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
  }

  private void receive(byte[] body) throws IOException {
    JsonValue decoded;
    try {
      String text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(body)).toString();
      try (var parser = Json.createParser(new StringReader(text))) {
        if (!parser.hasNext()) throw new IllegalArgumentException("Empty JSON body");
        parser.next();
        decoded = parser.getValue();
        if (parser.hasNext()) throw new IllegalArgumentException("Trailing JSON value");
      }
    } catch (RuntimeException | CharacterCodingException malformed) {
      error(JsonValue.NULL, -32700, "Parse error");
      return;
    }
    if (!(decoded instanceof JsonObject message)) {
      error(JsonValue.NULL, -32600, "Expected a JSON-RPC object");
      return;
    }
    JsonValue id = message.get("id");
    boolean request = id != null;
    if (!(message.get("jsonrpc") instanceof JsonString version) || !version.getString().equals("2.0")
        || !(message.get("method") instanceof JsonString)
        || (request && !validId(id)) || message.containsKey("result") || message.containsKey("error")) {
      error(JsonValue.NULL, -32600, "Invalid JSON-RPC request");
      return;
    }
    String method = message.getString("method");
    try {
      if (method.equals("exit") && !request) {
        exitStatus = state == State.SHUTDOWN ? 0 : 1;
        return;
      }
      if (method.equals("initialize") && request) {
        if (state != State.NEW) { error(id, -32600, "Already initialized"); return; }
        JsonObject params = object(message.get("params"));
        JsonObject capabilities = object(params.get("capabilities"));
        JsonObject textDocument = optionalObject(capabilities, "textDocument");
        JsonObject publish = optionalObject(textDocument, "publishDiagnostics");
        versionSupport = publish.get("versionSupport") == JsonValue.TRUE;
        state = State.INITIALIZING;
        result(id, Json.createObjectBuilder()
            .add("capabilities", Json.createObjectBuilder().add("positionEncoding", "utf-16")
                .add("textDocumentSync", Json.createObjectBuilder().add("openClose", true).add("change", 1)))
            .add("serverInfo", Json.createObjectBuilder().add("name", "shrink").add("version", "0.1.0"))
            .build());
        return;
      }
      if (state == State.NEW || state == State.INITIALIZING) {
        if (method.equals("initialized") && !request && state == State.INITIALIZING) {
          object(message.get("params"));
          state = State.ACTIVE;
        } else if (request) error(id, -32002, "Server not initialized");
        return;
      }
      if (state == State.SHUTDOWN) {
        if (request) error(id, -32600, "Server has shut down");
        return;
      }
      if (method.equals("shutdown") && request) {
        state = State.SHUTDOWN;
        documents.clear();
        result(id, JsonValue.NULL);
        return;
      }
      if (request) {
        error(id, -32601, "Method not found: " + method);
        return;
      }
      switch (method) {
        case "textDocument/didOpen" -> {
          JsonObject document = object(object(message.get("params")).get("textDocument"));
          if (!string(document, "languageId").equals("java")) return;
          URI uri = uri(document);
          String path = uri.getPath();
          String name = path == null ? "Buffer.java" : path.substring(path.lastIndexOf('/') + 1);
          if (!name.endsWith(".java") || name.length() <= 5) name = "Buffer.java";
          documents.open(new SourceSnapshot(uri, name, string(document, "text")), integer(document, "version"), System.nanoTime());
        }
        case "textDocument/didChange" -> {
          JsonObject params = object(message.get("params"));
          JsonObject document = object(params.get("textDocument"));
          JsonValue changesValue = params.get("contentChanges");
          if (!(changesValue instanceof JsonArray changes) || changes.isEmpty()) {
            throw new IllegalArgumentException("Expected full contentChanges");
          }
          String text = null;
          for (JsonValue item : changes) {
            JsonObject change = object(item);
            if (change.containsKey("range") || change.containsKey("rangeLength")) {
              throw new IllegalArgumentException("Only full synchronization is supported");
            }
            text = string(change, "text");
          }
          documents.change(uri(document), integer(document, "version"), text, System.nanoTime());
        }
        case "textDocument/didClose" -> {
          URI uri = uri(object(object(message.get("params")).get("textDocument")));
          documents.close(uri);
          publish(uri, null, Json.createArrayBuilder().build());
        }
        default -> { /* Unknown notifications require no response. */ }
      }
    } catch (IllegalArgumentException | ClassCastException failure) {
      if (request) error(id, -32602, "Invalid params");
      else log.write("shrink ignored invalid " + method + ": " + failure.getMessage());
    }
  }

  private void completed(Completed completed) throws IOException {
    busy = false;
    if (state != State.ACTIVE || !documents.current(completed.work())) return;
    if (completed.failure() != null) {
      notify("window/showMessage", Json.createObjectBuilder().add("type", 1)
          .add("message", "shrink could not analyze " + completed.work().source().uri() + "; see server logs.").build());
      return;
    }
    for (String notice : completed.result().notices()) log.write("shrink compiler notice: " + notice);
    var diagnostics = Json.createArrayBuilder();
    for (Diagnostic diagnostic : completed.result().diagnostics()) {
      var value = Json.createObjectBuilder()
          .add("range", Json.createObjectBuilder().add("start", position(diagnostic.range().start()))
              .add("end", position(diagnostic.range().end())))
          .add("severity", switch (diagnostic.severity()) { case ERROR -> 1; case WARNING -> 2; case INFORMATION -> 3; })
          .add("source", "shrink").add("message", diagnostic.message());
      if (!diagnostic.code().isEmpty()) value.add("code", diagnostic.code());
      diagnostics.add(value);
    }
    publish(completed.work().source().uri(), completed.work().version(), diagnostics.build());
  }

  private static JsonObject position(Position position) {
    return Json.createObjectBuilder().add("line", position.line()).add("character", position.character()).build();
  }

  private void publish(URI uri, Integer version, JsonArray diagnostics) throws IOException {
    var params = Json.createObjectBuilder().add("uri", uri.toString()).add("diagnostics", diagnostics);
    if (versionSupport && version != null) params.add("version", version);
    notify("textDocument/publishDiagnostics", params.build());
  }

  private void notify(String method, JsonObject params) throws IOException {
    send(Json.createObjectBuilder().add("jsonrpc", "2.0").add("method", method).add("params", params).build());
  }

  private void result(JsonValue id, JsonValue value) throws IOException {
    send(Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", id).add("result", value).build());
  }

  private void error(JsonValue id, int code, String message) throws IOException {
    send(Json.createObjectBuilder().add("jsonrpc", "2.0").add("id", id)
        .add("error", Json.createObjectBuilder().add("code", code).add("message", message)).build());
  }

  private void send(JsonObject message) throws IOException { Framing.write(output, message.toString()); }

  private static boolean validId(JsonValue value) {
    if (value instanceof JsonString) return true;
    if (value instanceof JsonNumber number && number.isIntegral()) {
      try { number.intValueExact(); return true; } catch (ArithmeticException outsideRange) { return false; }
    }
    return false;
  }

  private static JsonObject object(JsonValue value) {
    if (!(value instanceof JsonObject object)) throw new IllegalArgumentException("Expected an object");
    return object;
  }

  private static JsonObject optionalObject(JsonObject parent, String key) {
    return parent.containsKey(key) ? object(parent.get(key)) : JsonValue.EMPTY_JSON_OBJECT;
  }

  private static String string(JsonObject object, String key) {
    if (!(object.get(key) instanceof JsonString value)) throw new IllegalArgumentException("Expected string: " + key);
    return value.getString();
  }

  private static int integer(JsonObject object, String key) {
    if (!(object.get(key) instanceof JsonNumber value) || !value.isIntegral()) {
      throw new IllegalArgumentException("Expected integer: " + key);
    }
    try { return value.intValueExact(); }
    catch (ArithmeticException outsideRange) { throw new IllegalArgumentException("Integer outside range: " + key); }
  }

  private static URI uri(JsonObject object) {
    URI uri = URI.create(string(object, "uri"));
    if (!uri.isAbsolute()) throw new IllegalArgumentException("Expected absolute URI");
    return uri;
  }
}
