package work.archaic.shrink;

import jakarta.json.spi.JsonProvider;
import java.util.ServiceLoader;
import work.archaic.service.compiler.v01.CompilerAdapter;
import work.archaic.service.logging.v03.Configuration;
import work.archaic.service.logging.v03.Log;
import work.archaic.shrink.server.Session;

/** Stdio entry point. stdout is reserved for framed protocol messages. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args));
  }

  private static int run(String[] args) {
    Session session;
    var output = new ServerLog(System.err);
    try {
      if (args.length != 0) throw new IllegalArgumentException("Usage: shrink (stdio; no arguments)");
      if (ServiceLoader.load(JsonProvider.class).stream().count() != 1) {
        throw new IllegalStateException("Expected exactly one JSON-P provider");
      }
      JsonProvider.provider(); // Resolve before accepting any protocol input.
      Log logging = exactlyOne(Log.class);
      CompilerAdapter compiler = exactlyOne(CompilerAdapter.class);
      var configuration = new Configuration(Boolean.getBoolean("shrink.debug"), output::entry, output::failure);
      session = new Session(System.in, System.out, compiler, logging, configuration);
    } catch (Exception | java.util.ServiceConfigurationError failure) {
      output.problem(failure); // Composition failed before a context existed.
      return 1;
    }
    try {
      return session.run();
    } catch (Exception | Error failure) {
      // The session context has already rendered its evidence and original failure.
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      return 1;
    }
  }

  private static <T> T exactlyOne(Class<T> service) {
    var providers = ServiceLoader.load(service).stream().toList();
    if (providers.size() != 1) {
      throw new IllegalStateException("Expected exactly one " + service.getName() + "; found " + providers.size());
    }
    return providers.getFirst().get();
  }
}
