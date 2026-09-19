# shrink

Shrink reports Java syntax errors in your editor using supported JDK compiler APIs.
It analyzes the current text, including unsaved changes, and clears diagnostics when you fix them.
The first release implements LSP 3.17 push diagnostics over stdin/stdout.

Requires **JDK 25**, with preview features disabled. There is no Maven or Gradle build.
All application and library dependencies are explicit JPMS modules.

## Build and run

Clone shrink into a parent directory that can also hold its pinned `minau` and `service-catalog`
sibling checkouts. Select JDK 25 for both `java` and `javac`, then run from the shrink directory:

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
from the development shell that selects your JDK. The current server still requires JDK 25.

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
