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
import work.archaic.service.logging.v03.Configuration;
import work.archaic.service.logging.v03.Context;
import work.archaic.service.logging.v03.Log;
import work.archaic.service.logging.v03.Logging;
import work.archaic.shrink.protocol.Framing;

/** One session owns all document mutations and all protocol output. */
public final class Session implements Logging {
  private enum State { NEW, INITIALIZING, ACTIVE, SHUTDOWN }
  private sealed interface Event {}
  private record Message(byte[] body) implements Event {}
  private record End(IOException failure) implements Event {}
  private record Completed(Documents.Work work, ParseResult result, Throwable failure) implements Event {}

  private final InputStream input;
  private final OutputStream output;
  private final CompilerAdapter compiler;
  private final Log logging;
  private final Configuration configuration;
  private final Context context;
  private final Documents documents = new Documents(TimeUnit.MILLISECONDS.toNanos(150));
  private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(16);
  private State state = State.NEW;
  private boolean versionSupport;
  private boolean busy;
  private Integer exitStatus;

  public Session(InputStream input, OutputStream output, CompilerAdapter compiler, Log logging, Configuration configuration) {
    this.input = java.util.Objects.requireNonNull(input, "input");
    this.output = java.util.Objects.requireNonNull(output, "output");
    this.compiler = java.util.Objects.requireNonNull(compiler, "compiler");
    this.logging = java.util.Objects.requireNonNull(logging);
    this.configuration = java.util.Objects.requireNonNull(configuration);
    this.context = logging.context(configuration);
  }

  public int run() throws SessionException {
    context.run(this::serveSession);
    return exitStatus;
  }

  private void serveSession() throws SessionException {
    logOnFailure("Serving editor session");
    try { serve(); }
    catch (IOException failure) { throw new SessionException("Session transport failed", failure); }
    catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
      throw new SessionException("Session interrupted", stopped);
    }
    if (exitStatus != 0) context.fail("Language-server session ended abnormally");
  }

  private void serve() throws IOException, InterruptedException {
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
        startReadyAnalysis(worker);
        long wait = !busy && state == State.ACTIVE ? documents.waitNanos(System.nanoTime()) : Long.MAX_VALUE;
        Event event = events.poll(wait, TimeUnit.NANOSECONDS);
        if (event == null) continue;
        switch (event) {
          case Message message -> receive(message.body());
          case Completed completed -> completed(completed);
          case End end -> {
            boolean normal = end.failure() == null && state == State.SHUTDOWN;
            if (!normal) logOnFailure(end.failure() == null
                ? "LSP stream ended before shutdown"
                : "Invalid or incomplete LSP stream: " + end.failure());
            exitStatus = normal ? 0 : 1;
          }
        }
      }
    } finally {
      documents.clear();
      reader.interrupt();
      worker.shutdownNow(); // Never await an uncooperative javac task during shutdown.
    }
  }

  private void startReadyAnalysis(java.util.concurrent.Executor worker) {
    if (busy || state != State.ACTIVE) return;
    Documents.Work next = documents.takeReady(System.nanoTime());
    if (next == null) return;
    busy = true;
    worker.execute(() -> analyze(next));
  }

  private void analyze(Documents.Work work) {
    try {
      // Child tasks establish independent contexts; the session context stays on its event loop.
      ParseResult result = new Analysis(compiler, work).run(logging.context(configuration));
      enqueue(new Completed(work, result, null));
    } catch (Throwable failure) {
      // Completion runs after the analysis context has published and released its evidence.
      enqueue(new Completed(work, null, failure));
    }
  }

  private void enqueue(Event event) {
    try { events.put(event); }
    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
  }

  private void receive(byte[] body) throws IOException {
    JsonValue decoded;
    try {
      decoded = decodeMessage(body);
    } catch (JsonException | CharacterCodingException malformed) {
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
    logOnFailure("Received " + method + " in " + state);
    try {
      dispatch(message, id, request, method);
    } catch (InvalidParamsException | DocumentUpdateException failure) {
      if (request) {
        error(id, -32602, "Invalid params");
        return;
      }
      logOnFailure("Ignored invalid " + method + ": " + failure.getMessage());
    }
  }

  private static JsonValue decodeMessage(byte[] body) throws CharacterCodingException {
    String text = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(body)).toString();
    try (var parser = Json.createParser(new StringReader(text))) {
      if (!parser.hasNext()) throw new JsonException("Empty JSON body");
      parser.next();
      JsonValue value = parser.getValue();
      if (parser.hasNext()) throw new JsonException("Trailing JSON value");
      return value;
    }
  }

  private void dispatch(JsonObject message, JsonValue id, boolean request, String method)
      throws IOException, InvalidParamsException, DocumentUpdateException {
    if (method.equals("exit") && !request) {
      exitSession();
      return;
    }
    if (method.equals("initialize") && request) {
      initialize(message, id);
      return;
    }
    if (state == State.NEW || state == State.INITIALIZING) {
      beforeInitialized(message, id, request, method);
      return;
    }
    if (state == State.SHUTDOWN) {
      afterShutdown(id, request);
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
      case "textDocument/didOpen" -> openDocument(message);
      case "textDocument/didChange" -> changeDocument(message);
      case "textDocument/didClose" -> closeDocument(message);
      default -> { /* Unknown notifications require no response. */ }
    }
  }

  private void afterShutdown(JsonValue id, boolean request) throws IOException {
    if (!request) return;
    error(id, -32600, "Server has shut down");
  }

  private void exitSession() {
    if (state != State.SHUTDOWN) logOnFailure("LSP client exited before shutdown");
    exitStatus = state == State.SHUTDOWN ? 0 : 1;
  }

  private void initialize(JsonObject message, JsonValue id) throws IOException, InvalidParamsException {
    if (state != State.NEW) {
      error(id, -32600, "Already initialized");
      return;
    }
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
  }

  private void beforeInitialized(JsonObject message, JsonValue id, boolean request, String method)
      throws IOException, InvalidParamsException {
    if (method.equals("initialized") && !request && state == State.INITIALIZING) {
      object(message.get("params"));
      state = State.ACTIVE;
      return;
    }
    if (request) error(id, -32002, "Server not initialized");
  }

  private void openDocument(JsonObject message) throws InvalidParamsException, DocumentUpdateException {
    JsonObject document = object(object(message.get("params")).get("textDocument"));
    if (!string(document, "languageId").equals("java")) return;
    URI uri = uri(document);
    int version = integer(document, "version");
    String text = string(document, "text");
    String path = uri.getPath();
    String name = path == null ? "Buffer.java" : path.substring(path.lastIndexOf('/') + 1);
    if (!name.endsWith(".java") || name.length() <= 5) name = "Buffer.java";
    SourceSnapshot source = snapshot(uri, name, text);
    documents.open(source, version, System.nanoTime());
  }

  private static SourceSnapshot snapshot(URI uri, String name, String text) throws InvalidParamsException {
    // Translate only the published record's input validation, not document mutation or dispatch.
    try { return new SourceSnapshot(uri, name, text); }
    catch (IllegalArgumentException failure) { throw new InvalidParamsException("Invalid source identity", failure); }
  }

  private void changeDocument(JsonObject message) throws InvalidParamsException, DocumentUpdateException {
    JsonObject params = object(message.get("params"));
    JsonObject document = object(params.get("textDocument"));
    URI uri = uri(document);
    int version = integer(document, "version");
    JsonValue changesValue = params.get("contentChanges");
    if (!(changesValue instanceof JsonArray changes) || changes.isEmpty()) {
      throw new InvalidParamsException("Expected full contentChanges");
    }
    String text = null;
    for (JsonValue item : changes) text = fullChange(item);
    documents.change(uri, version, text, System.nanoTime());
  }

  private static String fullChange(JsonValue item) throws InvalidParamsException {
    JsonObject change = object(item);
    if (change.containsKey("range") || change.containsKey("rangeLength")) {
      throw new InvalidParamsException("Only full synchronization is supported");
    }
    return string(change, "text");
  }

  private void closeDocument(JsonObject message) throws IOException, InvalidParamsException {
    URI uri = uri(object(object(message.get("params")).get("textDocument")));
    documents.close(uri);
    publish(uri, null, Json.createArrayBuilder().build());
  }

  private void completed(Completed completed) throws IOException {
    busy = false;
    if (state != State.ACTIVE || !documents.current(completed.work())) return;
    if (completed.failure() != null) {
      notify("window/showMessage", Json.createObjectBuilder().add("type", 1)
          .add("message", "shrink could not analyze " + completed.work().source().uri() + "; see server logs.").build());
      return;
    }
    for (String notice : completed.result().notices()) logImmediately("Compiler notice: " + notice);
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
    if (!(value instanceof JsonNumber number) || !number.isIntegral()) return false;
    try { number.intValueExact(); return true; }
    catch (ArithmeticException outsideRange) { return false; }
  }

  private static JsonObject object(JsonValue value) throws InvalidParamsException {
    if (!(value instanceof JsonObject object)) throw new InvalidParamsException("Expected an object");
    return object;
  }

  private static JsonObject optionalObject(JsonObject parent, String key) throws InvalidParamsException {
    return parent.containsKey(key) ? object(parent.get(key)) : JsonValue.EMPTY_JSON_OBJECT;
  }

  private static String string(JsonObject object, String key) throws InvalidParamsException {
    if (!(object.get(key) instanceof JsonString value)) throw new InvalidParamsException("Expected string: " + key);
    return value.getString();
  }

  private static int integer(JsonObject object, String key) throws InvalidParamsException {
    if (!(object.get(key) instanceof JsonNumber value) || !value.isIntegral()) {
      throw new InvalidParamsException("Expected integer: " + key);
    }
    try { return value.intValueExact(); }
    catch (ArithmeticException outsideRange) { throw new InvalidParamsException("Integer outside range: " + key, outsideRange); }
  }

  private static URI uri(JsonObject object) throws InvalidParamsException {
    String text = string(object, "uri");
    URI uri;
    try { uri = new URI(text); }
    catch (java.net.URISyntaxException malformed) { throw new InvalidParamsException("Invalid URI", malformed); }
    if (!uri.isAbsolute()) throw new InvalidParamsException("Expected absolute URI");
    return uri;
  }
}

