package work.archaic.shrink;

import java.io.PrintStream;
import work.archaic.service.logging.v03.Entry;
import work.archaic.service.logging.v03.FailureReport;

/** Keep each report together across session and parser threads; never write protocol stdout. */
final class ServerLog {
  private final PrintStream destination;
  ServerLog(PrintStream destination) { this.destination = destination; }

  void entry(Entry entry) {
    synchronized (destination) { destination.println(render(entry)); }
  }

  void failure(FailureReport report) {
    synchronized (destination) {
      destination.println("--- failed logging context ---");
      if (report.dropped() != 0) destination.println("[" + report.dropped() + " earlier entries dropped]");
      report.evidence().forEach(entry -> destination.println(render(entry)));
      if (report.explicitFailure() != null) destination.println("Failure: " + render(report.explicitFailure()));
      if (report.cause() != null) report.cause().printStackTrace(destination);
      destination.println("--- end context ---");
    }
  }

  void problem(Throwable failure) {
    synchronized (destination) { destination.println("shrink cannot continue: " + escape(failure.toString())); }
  }

  private static String render(Entry entry) {
    return entry.timestamp() + " " + escape(entry.source()) + ": " + escape(entry.message());
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
  }
}
