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
- `work.archaic.shrink.compiler` provides the catalog's versioned compiler contract and must
  remain independently usable without the server or JSON. Select exactly one implementation with ServiceLoader.
- stdout is exclusively LSP framing. Peep v02 writes failures and compiler auxiliary output to stderr.
- Name each complete user-facing intent as a Goal; keep its response and failures inside `goal.run(...)`.
- Minau v02 tests use `-ea`, public suite records, package-private cases and inline `assert condition : "reason"` checks.
  Do not create assertion helper methods. Launch Minau directly from `cmd/test`.
- Keep generated `out/` untracked. Document protocol or editor-facing behavior in `docs/`.
