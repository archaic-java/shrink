---
name: maintain-shrink
description: Maintain, extend, diagnose, and review Shrink's reusable Java compiler adapter and stdio language server. Use for compiler diagnostics, LSP lifecycle, scheduling, logging, dependency changes, tests, and editor integration in this repository.
---

# Maintain Shrink

Read this foundation before changing Shrink. Follow repository instructions in
[AGENTS.md](../../AGENTS.md) and the checkout commands in [README.md](../../README.md).
Apply the shared Archaic Java skill when available; keep project mechanics here and
precise portable contracts in the selected service-catalog source and Javadoc.

## Preserve the boundaries

- Build through `javac @cmd/compile`; test through `java @cmd/test`; launch through
  `java @cmd/run`. Keep named JPMS modules and supported JDK APIs only.
- Build with release 25, without preview. Parse at the running JDK's default source
  level. Accept newer JDKs while verifying only the pinned JDK 25 baseline; do not add
  a multi-JDK or early-access matrix or promise future construct support.
- Keep `work.archaic.shrink.compiler` independently usable through compiler v01,
  without server, JSON, logging, or test-runner dependencies. Preserve published v01.
- Keep stdout exclusively framed LSP. Select exactly one compiler, JSON, and logging
  provider explicitly. Give the session and each analysis their own configured Culpa
  v03 context on the executing thread; keep logs and notices on stderr.
- Let the event loop own lifecycle, document mutations, and protocol writes. Check
  version and generation immediately before publication. Never wait for an
  uncooperative parser during shutdown.
- Keep source revisions in `dependencies.lock`, binary checksums in
  `lib/checksums.sha256`, and generated `out/` untracked.
- Keep tests in their named module, using Minau v02 records and inline assertions
  with explanations. Bound external work and preserve observable failure behavior.

## Choose the task references

| Task | Read and inspect | Verify |
|---|---|---|
| Change compiler parsing, offsets, or contract behavior | [Architecture](references/architecture.md), pinned compiler v01 Javadoc, `JavacCompiler` | Compiler cases, concurrent calls, standalone consumer |
| Change LSP lifecycle, framing, or scheduling | [Architecture](references/architecture.md), `Session`, `Documents`, `Framing` | Framing, document, session, and process cases |
| Change logging composition or failure rendering | [Architecture](references/architecture.md), pinned logging v03 guide and Javadoc, `Main`, `Analysis`, `Session` | Recovery, independent worker contexts, one failure report, debug-enabled stdout framing |
| Change dependencies or command options | [Dependencies](references/dependencies.md), lock, checksums, module descriptors, `cmd/`, launcher | Checksums, named JAR descriptors, compile and all tests |
| Change editor setup or launcher behavior | [Helix](references/helix.md), README, `bin/shrink` | Shell syntax, launch from another directory and through a symlink, relevant process cases |
| Run checks or update coverage claims | [Verification](references/verification.md), test suites and CI workflow | Record exact revision, environment, commands, and results |

## Complete the change

Read only the references relevant to the task. Inspect catalog declarations at the
locked revision; moving-main links aid navigation but do not establish that revision.
Compile before testing. Review guards, failure translation, cleanup, and thread
ownership. Update the owning reference alongside behavior and keep links valid.
Report the checks actually run and explain retained nesting or exception-policy
exceptions where correctness requires them.
