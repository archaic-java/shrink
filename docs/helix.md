# Use shrink with Helix

Build and test shrink using the README commands, then put the `bin/shrink` launcher on `PATH`
as described there. Select JDK 25's `java` on `PATH` before starting Helix. The current server
requires JDK 25; this launcher does not change its supported Java versions.

Add this to `~/.config/helix/languages.toml`, or `.helix/languages.toml` in your project.

```toml
[language-server.shrink]
command = "shrink"

[[language]]
name = "java"
language-servers = ["shrink"]
```

The launcher resolves shrink's module paths relative to its installation, including when installed
through a symlink. It preserves the project's working directory and uses `exec java` so the Java
process receives the editor's signals directly. No JDK or installation paths belong in the Helix
configuration. This replaces Helix's default Java server selection and works without jdtls installed.

## Select the JDK

Start Helix from the project's development shell, with its JDK on `PATH`:

```sh
command -v java
java --version
hx .
```

A Nix development shell or another environment manager can select that JDK. Setting `JAVA_HOME`
alone is insufficient: the launcher deliberately uses `java` on `PATH`.

Helix inherits the environment when it starts. Changing Java in another shell and running
`:lsp-restart` does not update Helix's environment; reopen Helix from the intended development shell.

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

If startup fails, check `command -v shrink`, `command -v java`, and `java --version` in the shell
used to start Helix, run `hx --health java`,
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
