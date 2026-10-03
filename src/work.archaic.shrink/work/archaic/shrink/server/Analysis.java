package work.archaic.shrink.server;

import work.archaic.service.compiler.v01.CompilerAdapter;
import work.archaic.service.compiler.v01.ParseException;
import work.archaic.service.compiler.v01.ParseResult;
import work.archaic.service.compiler.v01.SourceSnapshot;
import work.archaic.service.logging.v03.Context;
import work.archaic.service.logging.v03.Logging;

/** One parser attempt, with its own outcome and evidence on the executing worker thread. */
final class Analysis implements Logging {
  private final CompilerAdapter compiler;
  private final Documents.Work work;
  private ParseResult result;

  Analysis(CompilerAdapter compiler, Documents.Work work) {
    this.compiler = compiler;
    this.work = work;
  }

  ParseResult run(Context context) throws ParseException {
    context.run(() -> {
      SourceSnapshot source = work.source();
      logOnFailure("Analyzing " + source.uri() + " version " + work.version() + " generation " + work.generation());
      logOnDebug(() -> "Snapshot " + source.fileName() + ": " + source.text().length() + " UTF-16 units");
      result = compiler.parse(source);
      if (!result.source().equals(source)) throw new IllegalStateException("Compiler returned a result for a different source");
      logOnDebug(() -> "Parser returned " + result.diagnostics().size() + " diagnostics and " + result.notices().size() + " notices");
    });
    return result;
  }
}
