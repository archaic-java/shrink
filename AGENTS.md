# shrink

- Build with `--release 25`; accept JDK 25 and newer at runtime, preview disabled.
  Parse with the running JDK's default source level, not a fixed source/release option.
  Compile and launch only through JDK tools and named JPMS modules.
- Verify on the pinned JDK 25 baseline only for now; do not add a multi-JDK or early-access matrix.
  New syntax support is a goal, not a guarantee that every future feature handles every new construct.
- Canonical commands from this directory: `javac @cmd/compile`, `java @cmd/test`, `java @cmd/run`.
- Do not add Maven, Gradle, classpath dependencies, automatic modules, compiler internals,
  generated protocol bindings, annotation processing or reflective application dispatch.
- Source links under `lib/src/` point to sibling checkouts pinned in `dependencies.lock`.
  Deliberately pinned modular JSON JARs live in `lib/bin/`; verify `lib/checksums.sha256`.
- `work.archaic.shrink.compiler` implements the catalog's versioned compiler contract and must
  remain independently usable without the server or JSON. Select its implementation explicitly.
- stdout is exclusively LSP framing. All logs and compiler auxiliary output go to stderr.
- Minau tests use `-ea`; the test launcher must reject a zero-test run.
- Keep generated `out/` untracked. Document protocol or editor-facing behavior in `docs/`.
