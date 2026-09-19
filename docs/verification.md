# Verification

Verified during implementation on 2026-09-19 using Linux, Eclipse Temurin JDK 25.0.4+7-LTS,
and the exact source and binary pins in `dependencies.lock` and `lib/checksums.sha256`.

## Automated checks

```sh
sha256sum -c lib/checksums.sha256
javac @cmd/compile
java @cmd/test
```

The Minau suite contains 19 tests across five suites. Coverage includes:

- Java syntax errors, corrections, empty source, unresolved dependencies and module-info.java.
- LF, CRLF, CR, tabs, supplementary Unicode, Java Unicode escapes and EOF coordinates.
- Immutable results and concurrent independent adapter calls.
- Compilation and launch of an independent named consumer module without the server, JSON or Minau
  in its resolved module graph.
- UTF-8 byte framing, short reads, consecutive frames, duplicate/invalid lengths and truncated input.
- Debouncing, coalescing, fairness, version ordering and close/reopen generations using supplied time.
- A controlled in-flight parser completing after close/reopen, adapter failure reporting, and shutdown
  while a parser deliberately ignores interruption.
- Actual server subprocesses launched from empty temporary working directories; initialization,
  protocol errors, notifications, publication, clearing, repeated edits, shutdown and abnormal exit.
- No generated files in the server working directory and no unframed stdout output.

`java @cmd/test` enables assertions and launches Minau. Its small wrapper rejects Minau's otherwise
successful zero-test outcome. The deliberately failing adapter logs an expected SEVERE message;
the corresponding test verifies window/showMessage rather than an empty diagnostic success.

Both binary JAR descriptors were inspected with `jar --describe-module` and are explicit named
modules. The dependency setup script was syntax checked with `bash -n`.

The GitHub Actions Verify workflow repeats checksum verification, module inspection, compilation
and tests on a clean checkout using the same public commands. Workflow results are visible under
[Actions](https://github.com/archaic-java/shrink/actions).

## Real editor smoke test

Helix **25.07.1 (a05c151b)** was run in a real pseudoterminal with the configuration from the Helix
guide, using absolute JDK and module paths. `hx --health java` found shrink and the Java grammar.

The initial file contained `class Demo { int count = 1; }`. Deleting `1` without saving produced:

```json
{"version":1,"diagnostics":[{"range":{"start":{"line":0,"character":25},"end":{"line":0,"character":25}},"severity":1,"source":"shrink","message":"illegal start of expression","code":"compiler.err.illegal.start.of.expr"}]}
```

Reinserting `1` without saving produced:

```json
{"version":2,"diagnostics":[]}
```

The examples above omit the document URI for portability. Helix's transport log confirmed both
notifications were received. The document diagnostic picker was opened during the test. The file
on disk remained unchanged. Helix sent shutdown, received a null result, sent exit, and exited with
status 0. These observations are from the editor process, separate from the simulated LSP clients.

This establishes the documented basic workflow on that Helix version. It is not a performance
benchmark or a claim that all editors and every Helix version have been tested.
