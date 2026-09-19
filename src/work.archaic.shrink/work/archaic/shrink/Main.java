package work.archaic.shrink;

import jakarta.json.spi.JsonProvider;
import java.util.ServiceLoader;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import work.archaic.shrink.compiler.JavacCompiler;
import work.archaic.shrink.server.Session;

/** Stdio entry point. stdout is reserved for framed protocol messages. */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    var root = Logger.getLogger("");
    for (var handler : root.getHandlers()) root.removeHandler(handler);
    var console = new ConsoleHandler(); // ConsoleHandler writes to stderr.
    console.setLevel(Level.INFO);
    root.addHandler(console);
    root.setLevel(Level.INFO);
    int status = 1;
    try {
      if (args.length != 0) throw new IllegalArgumentException("Usage: shrink (stdio; no arguments)");
      long providers = ServiceLoader.load(JsonProvider.class).stream().count();
      if (providers != 1) throw new IllegalStateException("Expected exactly one JSON-P provider; found " + providers);
      JsonProvider.provider(); // Resolve before accepting any protocol input.
      status = new Session(System.in, System.out, new JavacCompiler()).run();
    } catch (Exception failure) {
      root.log(Level.SEVERE, "shrink cannot continue", failure);
    }
    System.exit(status);
  }
}
