# Dependencies

Build shrink with `--release 25`, without preview features. Runtime accepts JDK 25 or newer and
uses its default source language level. CI verifies only Eclipse Temurin `25.0.4+7.0.LTS` (runtime
build 25.0.4+7-LTS), using the same checked-in argument files as local development. There is no
multi-JDK or early-access matrix; newer runtimes are accepted but not verified by this workflow.

## Source modules

`dependencies.lock` is the authority for source commit pins. Run `bash scripts/checkout-dependencies`
from a checkout with room for sibling repositories. Existing checkouts at another revision are
left unchanged and cause an explanatory failure.

| Repository | Commit | Module / purpose |
|---|---|---|
| archaic-java/minau | `7abc609a7092dc6491d9af299499cfe434ad7f23` | `work.archaic.minau`, test runner only |
| archaic-java/service-catalog | `5265d7bb7549a5325fe39ee26b2ce1f4e4f0aeb3` | `work.archaic.service.catalog`, compiler and logging contracts, plus test v02 |
| archaic-java/peep | `b97658043f3eead9f62569c16b650432c45aaee5` | `work.archaic.peep`, logging v02 runtime provider |

The catalog pin includes the compiler contract, logging v02 and test v02. Do not replace a pin with a moving branch name.
The contract package is `work.archaic.service.compiler.v01`; its version is separate from the
repository revision. Shrink's compiler module imports only compiler contract types, not test types.

The committed relative links are:

| Link | Target |
|---|---|
| lib/src/work.archaic.minau | ../../../minau/src/work.archaic.minau |
| lib/src/work.archaic.service.catalog | ../../../service-catalog/src/work.archaic.service.catalog |
| lib/src/work.archaic.peep | ../../../peep/src/work.archaic.peep |

Minau's current implementation requires only the catalog. Its stdout summaries run in a separate
test process; they never share the server's protocol stream. Peep provides Shrink's `Diagnostics`
and `Log` services and writes failure reports to stderr. Jules is not a dependency because this
implementation has no SLF4J consumer.

## Binary modules

The following deliberately accepted JARs are checked into `lib/bin/`. No package manager or network
download is needed to resolve them at build time. Verify bytes with `sha256sum -c lib/checksums.sha256`.

| Artifact | JPMS module | SHA-256 |
|---|---|---|
| jakarta.json:jakarta.json-api:2.1.3 | jakarta.json | bc934142805ea1d794f1440563965a3861a2a9fb7414ecd3fe44f26500734414 |
| org.eclipse.parsson:parsson:1.1.7 | org.eclipse.parsson | c21db018f8ac6cf79893f1af77f1cd337937bd12ae6fa3d4b10f5a00819ee56c |

Origins:

- [JSON-P API JAR](https://repo.maven.apache.org/maven2/jakarta/json/jakarta.json-api/2.1.3/jakarta.json-api-2.1.3.jar)
- [Parsson JAR](https://repo.maven.apache.org/maven2/org/eclipse/parsson/parsson/1.1.7/parsson-1.1.7.jar)

Both distributions contain `META-INF/LICENSE.md` and `META-INF/NOTICE.md`. Their upstream
licensing is EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0; the bundled license files specify
the applicable terms and notices. They are retained intact inside the JARs.

Both actual release artifacts were inspected with `jar --describe-module`; neither is an automatic
module. Parsson requires jakarta.json and provides `jakarta.json.spi.JsonProvider`. JSON-P uses
that SPI. The launch configuration resolves Parsson explicitly, and startup requires exactly one
provider. The application does not use reflective JSON binding or dynamically generated proxies.

```sh
jar --describe-module --file lib/bin/jakarta.json-api-2.1.3.jar
jar --describe-module --file lib/bin/parsson-1.1.7.jar
```

GitHub Actions checkout and setup-java actions are pinned to commit SHAs in the workflow.
