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
| archaic-java/minau | `13808cc18f93005fb88f5510d37bd2785a435578` | `work.archaic.minau`, test runner only |
| archaic-java/service-catalog | `a1b8d4ab43e92647693f2e92933e1f2df99a1476` | `work.archaic.service.catalog`, compiler v01 contract and test contracts |

The catalog pin includes the new compiler contract. Its original baseline was
`fc74f686ef0449773857527811a86c8c80744f08`. Do not replace the pin with a moving branch name.
The contract package is `work.archaic.service.compiler.v01`; its version is separate from the
repository revision. Shrink's compiler module imports only compiler contract types, not test types.

The committed relative links are:

| Link | Target |
|---|---|
| lib/src/work.archaic.minau | ../../../minau/src/work.archaic.minau |
| lib/src/work.archaic.service.catalog | ../../../service-catalog/src/work.archaic.service.catalog |

Minau's current implementation requires only the catalog. Its stdout summaries run in a separate
test process; they never share the server's protocol stream. Jules is not a dependency because
this implementation has no SLF4J consumer. Server logging uses java.util.logging.

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
