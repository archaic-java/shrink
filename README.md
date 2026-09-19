# shrink

Shrink reports Java syntax errors in your editor using supported JDK compiler APIs.
It analyzes the current text, including unsaved changes, and clears diagnostics when you fix them.
The first release implements LSP 3.17 push diagnostics over stdin/stdout.

**Shrink uses your JDK's own parser, making new Java syntax available without waiting for a shrink
grammar update.** Its goal is to accept new non-preview syntax as the selected JDK introduces it.

Requires **JDK 25 or newer**, with preview features disabled. There is no Maven or Gradle build.
All application and library dependencies are explicit JPMS modules.

## Java versions and feature scope

Shrink is built with `--release 25`. The JDK running it determines the source language level:
launching the same compiled shrink with a newer JDK selects that JDK's parser. There is no fixed
source version, project language-level override, or preview switch in this release.

This is a goal for syntax diagnostics, not a promise that every future shrink feature understands
every new language construct. Features such as completion, navigation, outline or refactoring may
need explicit updates even when the compiler already parses the construct. We currently verify
only the pinned JDK 25 baseline; newer runtimes are accepted without claiming tested compatibility
with every release or guaranteeing release-day support.

## Build and run

Clone shrink into a parent directory that can also hold its pinned `minau` and `service-catalog`
sibling checkouts. Use JDK 25 for the verified build/test baseline, then run from the shrink directory:

```sh
bash scripts/checkout-dependencies
sha256sum -c lib/checksums.sha256
javac @cmd/compile
java @cmd/test
java @cmd/run
```

On macOS use `shasum -a 256 -c lib/checksums.sha256`. The dependency script refuses to change
an existing dirty or differently pinned sibling checkout. Use a separate parent directory if
you already develop those repositories at another revision.

`java @cmd/run` waits for framed LSP input; it is not an interactive terminal command.
Use the [Helix guide](docs/helix.md) to launch it from your editor. `java @cmd/test` runs Minau
with assertions and rejects a zero-test run. Its deliberate adapter-failure test emits an expected
SEVERE log; the final test summary determines success.

For editor use, add the checkout's `bin/` directory to your `PATH`, or link its launcher into
a directory already on `PATH`:

```sh
mkdir -p "$HOME/.local/bin"
ln -s "$PWD/bin/shrink" "$HOME/.local/bin/shrink"
export PATH="$HOME/.local/bin:$PATH"
```

Run those commands from the shrink checkout after building. The executable `shrink` launcher finds
its own module paths, including through symlinks, and works from any project directory. It uses
`java` from the inherited `PATH`; `JAVA_HOME` alone does not select the executable. Launch Helix
from the development shell that selects your JDK (25 or newer); its parser determines the Java syntax.

## What it does

- Parses open Java documents, including `module-info.java`, without a project model.
- Reports syntax diagnostics using UTF-16 positions and the original javac diagnostic codes.
- Coalesces edits with a 150 ms debounce and discards obsolete analysis results.
- Keeps diagnostics, logging and process lifecycle separate.

It does not type-check, resolve dependencies, scan unopened files, execute annotation processors,
or generate class files. Unknown types and incompatible assignments may therefore produce no
diagnostics. Completion, navigation, formatting, outline and build orchestration are not implemented.

## Reusable compiler module

`work.archaic.shrink.compiler` provides `JavacCompiler`, implementing the catalog's
`work.archaic.service.compiler.v01.CompilerAdapter`. Another named module can use it without
loading the LSP server or JSON libraries. See [architecture and API usage](docs/architecture.md).

See also [dependency pins and licenses](docs/dependencies.md) and [verification](docs/verification.md).
