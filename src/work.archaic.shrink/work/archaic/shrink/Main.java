package work.archaic.shrink;

import jakarta.json.spi.JsonProvider;
import java.util.ServiceLoader;
import work.archaic.service.compiler.v01.CompilerAdapter;
import work.archaic.service.logging.v02.Diagnostics;
import work.archaic.service.logging.v02.Goal;
import work.archaic.service.logging.v02.Log;
import work.archaic.shrink.server.Session;

/** Stdio entry point. stdout is reserved for framed protocol messages. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    int status = 1;
    try {
      if (args.length != 0) throw new IllegalArgumentException("Usage: shrink (stdio; no arguments)");
      if (ServiceLoader.load(JsonProvider.class).stream().count() != 1) {
        throw new IllegalStateException("Expected exactly one JSON-P provider");
      }
      JsonProvider.provider(); // Resolve before accepting any protocol input.
      Log log = exactlyOne(Log.class);
      Diagnostics diagnostics = exactlyOne(Diagnostics.class);
      CompilerAdapter compiler = exactlyOne(CompilerAdapter.class);
      Goal serve = diagnostics.goal("language-server.serve", log);
      Goal analyze = diagnostics.goal("diagnostics.analyze", log);
      serve.run(() -> {
        if (new Session(System.in, System.out, compiler, analyze, diagnostics, log).run() != 0) {
          throw new IllegalStateException("Language-server session ended abnormally");
        }
      });
      status = 0;
    } catch (Exception failure) {
      failure.printStackTrace(System.err);
    }
    System.exit(status);
  }

  private static <T> T exactlyOne(Class<T> service) {
    var providers = ServiceLoader.load(service).stream().toList();
    if (providers.size() != 1) {
      throw new IllegalStateException("Expected exactly one " + service.getName() + "; found " + providers.size());
    }
    return providers.getFirst().get();
  }
}
