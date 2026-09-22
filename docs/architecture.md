# Architecture

Shrink has three modules:

| Module | Responsibility |
|---|---|
| `work.archaic.shrink` | Stdio transport, JSON mapping, lifecycle, document snapshots and scheduling |
| `work.archaic.shrink.compiler` | Service provider for synchronous parsing with the running JDK, using supported compiler APIs |
| `work.archaic.shrink.test` | Minau suites and real-process protocol tests |

The compiler module provides `work.archaic.service.compiler.v01.CompilerAdapter` from
service-catalog. The server resolves exactly one provider through `ServiceLoader`; the provider
module is an explicit runtime root in the command files and launcher. The compiler exports no
implementation package and requires the catalog only for its provider contract. The server's
implementation packages have only narrow qualified exports to its test module.

Shrink uses Peep's logging v02 provider. At composition, it resolves exactly one `Diagnostics`,
`Log` and compiler adapter. `language-server.serve` owns the complete editor session, including
LSP responses on its event-loop thread. `diagnostics.analyze` is the independent goal for each
document snapshot on the parser worker. A broken stream is noted on the serving goal, which then
fails when the session ends abnormally; a failed adapter attempt reports separately through its
analysis goal. These goals never nest on one thread.

## Compiler contract

```java
module example.consumer {
    requires work.archaic.service.catalog;
    uses work.archaic.service.compiler.v01.CompilerAdapter;
}
```

```java
import java.net.URI;
import work.archaic.service.compiler.v01.CompilerAdapter;
import work.archaic.service.compiler.v01.SourceSnapshot;
import java.util.ServiceLoader;

var providers = ServiceLoader.load(CompilerAdapter.class).stream().toList();
if (providers.size() != 1) throw new IllegalStateException("Expected one compiler provider");
CompilerAdapter compiler = providers.getFirst().get();
var source = new SourceSnapshot(URI.create("memory:/Demo.java"), "Demo.java",
    "class Demo { int count = ; }");
var result = compiler.parse(source);
```

Resolve `work.archaic.shrink.compiler` as a runtime root (for example, with
`--add-modules work.archaic.shrink.compiler`) so the provider participates in service discovery.

The checked `ParseException` distinguishes an adapter failure from ordinary syntax problems or
an empty successful result. Results echo the immutable source identity and defensively copy
diagnostics and source-less notices. Calls can execute concurrently because each creates its own
compiler, task and file manager. The parser does not read source from disk or resolve project
dependencies. It calls only `JavacTask.parse()`, with `-proc:none`, no lint, and a 100-error limit.
Construction requires JDK 25 or newer; preview syntax is disabled. No source/release option is
passed to the embedded compiler, so parsing follows the running JDK's default language level.

Shrink's own build retains `--release 25`. This is separate from the source language of the documents
it parses. A newer JDK can supply new syntax without a shrink grammar update or a new shrink build.
There is currently no project language-level override: choose the appropriate JDK when launching.

New non-preview syntax support is the goal. It does not imply automatic support for every new
construct in every future feature. Diagnostics forward compiler results without interpreting tree
nodes; completion, navigation, outline and refactoring may need construct-specific implementations.
Verification remains on pinned JDK 25 only, with no multi-release or early-access test matrix.
Accepting newer runtimes is not a claim that all future releases have been tested.

The catalog has no document versions or close/reopen tokens. These belong to the caller; shrink's
`Documents.Work` retains source, version and generation around each invocation. This reconciles
source identity with the approved requirement that the catalog avoid editor lifecycle concepts.
There is no compiler provider registry, and no JSON or LSP contract in the catalog.

Positions count zero-based lines and original UTF-16 code units. Tabs count as one unit; LF, CRLF,
CR, supplementary characters and Java Unicode escapes are covered by tests. javac offsets are
mapped using the exact input text, not javac's tab-expanded column numbers. Missing end offsets
become point ranges; a missing start uses the point offset, then file start if neither exists.
Offsets are clamped to valid text boundaries and line-ending positions to the preceding line end.
Source-less compiler diagnostics are notices for logging, never invented file locations.

## Protocol and lifecycle

JSON-P/Parsson handles JSON; shrink maps fields explicitly and implements its limited JSON-RPC
dispatcher. LSP messages have ASCII headers and UTF-8 bodies; Content-Length is a byte count.
Framing accepts partial reads and consecutive messages. Headers are limited to 8 KiB, bodies to
8 MiB. Irrecoverable framing ends the session; malformed JSON in a complete frame produces a
parse-error response and permits the next frame. Logs and compiler auxiliary output use stderr.

The server advertises only full text synchronization and UTF-16 coordinates. It supports initialize,
initialized, didOpen, didChange, didClose, shutdown and exit. Diagnostics are pushed, not pulled.
String and signed 32-bit integer request IDs are preserved. Unknown requests receive MethodNotFound;
unknown notifications are ignored. Invalid notification parameters are logged without a response.
Invalid JSON-RPC envelopes receive InvalidRequest. Requests before initialization receive
ServerNotInitialized. After shutdown, only exit is accepted; other requests receive InvalidRequest.

Published diagnostics include source `shrink`, severity, message, range and a compiler code when
available. Version is included only when the client advertises version support. A successful edit
or close publishes an empty list to clear old diagnostics. Adapter failures produce a log and
window/showMessage; they never publish an empty success. A close publication omits version.

## Scheduling

One event loop owns document state and every protocol write. A virtual-thread reader feeds a
bounded 16-event queue. One daemon parser worker feeds completions into the same queue. There
is at most one in-flight parse and one pending snapshot per open document (at most 128 documents).

Changes replace pending work and reset its 150 ms deadline. Ready work is taken fairly across
documents. Results publish only when their version and generation still match the current open
document, with the check and write performed on the same event loop. Generation changes on
close/reopen, even if a client reuses a version number. Duplicate opens and non-increasing changes
are rejected without altering the accepted snapshot.

Shutdown clears document state and disables publication before responding. Interrupting javac is
best effort; the server never waits for the parser to finish during shutdown. The standalone
process exits with 0 after shutdown/exit and 1 for an abnormal exit or broken transport.
