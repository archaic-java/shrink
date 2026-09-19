# Use shrink with Helix

Build and test shrink using the README commands. Keep JDK 25 installed; a Java 17 runtime or
a newer JDK selected accidentally is not the supported configuration.

Add this to `~/.config/helix/languages.toml`, or `.helix/languages.toml` in your project.
Replace all example paths with absolute paths on your machine:

```toml
[language-server.shrink]
command = "/absolute/path/to/jdk-25/bin/java"
args = [
  "--module-path", "/absolute/path/to/shrink/out:/absolute/path/to/shrink/lib/bin",
  "--add-modules", "org.eclipse.parsson",
  "--module", "work.archaic.shrink/work.archaic.shrink.Main"
]

[[language]]
name = "java"
language-servers = ["shrink"]
```

Absolute paths matter because Helix normally starts the server from the edited project's directory.
An absolute path to `@cmd/run` would not fix the relative module paths inside that argument file.
The colon module-path separator in this example is for Linux/macOS. The provider module is
resolved explicitly; no classpath fallback is needed. This replaces Helix's default Java server
selection, so it works without jdtls installed.

## Check the integration

1. Run `hx --health java` and check that the shrink command is found.
2. Open `Demo.java` containing `class Demo { int count = 1; }`.
3. Remove the `1`, leaving `int count = ;`, without saving. After the short debounce, Helix should
   show a syntax diagnostic. Use `Space d` for the document diagnostic picker or `[d` / `]d` to navigate.
4. Insert `1` again without saving. The diagnostic should disappear.
5. Close and reopen the buffer and confirm old diagnostics do not reappear.

After rebuilding, use `:lsp-restart`. `Space D` shows diagnostics across documents known to the
session; shrink does not scan unopened workspace files. Unknown types and type mismatches are
outside syntax-only analysis, so they are not suitable smoke-test errors.

Optional inline presentation in `~/.config/helix/config.toml`:

```toml
[editor.inline-diagnostics]
cursor-line = "warning"
```

If startup fails, check `java --version` using the exact configured executable, run `hx --health java`,
and inspect Helix's `:log-open` output. A missing catalog module means the pinned sibling setup or
compile step is incomplete. A missing JSON provider means the JARs or module-path entries are missing.
Do not launch the server through a shell command that prints a banner to stdout.

## Optional coexistence with jdtls

If jdtls is already configured, Helix supports selecting features per server:

```toml
[[language]]
name = "java"
language-servers = [
  { name = "shrink", only-features = ["diagnostics"] },
  { name = "jdtls", except-features = ["diagnostics"] }
]
```

This avoids duplicate diagnostics but removes jdtls semantic diagnostics. To keep full Java
error checking, use jdtls alone for diagnostics; this optional combination is for experimenting
with shrink while retaining jdtls navigation and completion.

References: [Helix language configuration](https://docs.helix-editor.com/languages.html) and
[Helix LSP commands](https://docs.helix-editor.com/master/lsp.html).
