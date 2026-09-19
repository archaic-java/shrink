package work.archaic.shrink.compiler;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import work.archaic.service.compiler.v01.*;

/**
 * Java 25 syntax analysis through public javac APIs. Each invocation owns all compiler resources.
 * Instantiate explicitly; no provider registry is required. Preview features are disabled.
 */
public final class JavacCompiler implements CompilerAdapter {
  public JavacCompiler() {
    if (Runtime.version().feature() != 25) {
      throw new IllegalStateException("shrink requires JDK 25 (preview disabled)");
    }
    if (ToolProvider.getSystemJavaCompiler() == null) {
      throw new IllegalStateException("The jdk.compiler module is required");
    }
  }

  @Override
  public ParseResult parse(SourceSnapshot source) throws ParseException {
    var compiler = ToolProvider.getSystemJavaCompiler();
    var collected = new DiagnosticCollector<JavaFileObject>();
    var auxiliary = new StringWriter();
    try (var files = compiler.getStandardFileManager(collected, Locale.ROOT, StandardCharsets.UTF_8)) {
      URI compilerUri = new URI("string", null, "/" + source.fileName(), null);
      var unit = new SimpleJavaFileObject(compilerUri, JavaFileObject.Kind.SOURCE) {
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
          return source.text();
        }
      };
      var task = (JavacTask) compiler.getTask(auxiliary, files, collected,
          List.of("-source", "25", "-proc:none", "-Xlint:none", "-Xmaxerrs", "100"),
          null, List.of(unit));
      task.parse();
      var diagnostics = new ArrayList<Diagnostic>();
      var notices = new ArrayList<String>();
      var lines = new Lines(source.text());
      for (var d : collected.getDiagnostics()) {
        String message = d.getMessage(Locale.ROOT);
        if (d.getSource() == null) {
          notices.add(message);
          continue;
        }
        long start = d.getStartPosition();
        if (start == javax.tools.Diagnostic.NOPOS) start = d.getPosition();
        // A source diagnostic without any usable offset is anchored at the start of the file.
        int from = clamp(start, source.text().length());
        long end = d.getEndPosition();
        int to = end == javax.tools.Diagnostic.NOPOS ? from : Math.max(from, clamp(end, source.text().length()));
        var severity = switch (d.getKind()) {
          case ERROR -> Diagnostic.Severity.ERROR;
          case WARNING, MANDATORY_WARNING -> Diagnostic.Severity.WARNING;
          default -> Diagnostic.Severity.INFORMATION;
        };
        diagnostics.add(new Diagnostic(new Range(lines.at(from), lines.at(to)), severity,
            d.getCode() == null ? "" : d.getCode(), message));
      }
      if (!auxiliary.toString().isBlank()) notices.add(auxiliary.toString().strip());
      return new ParseResult(source, diagnostics, notices);
    } catch (IOException | URISyntaxException | RuntimeException | LinkageError | StackOverflowError failure) {
      throw new ParseException("Cannot parse " + source.uri(), failure);
    }
  }

  private static int clamp(long offset, int length) {
    return (int) Math.max(0L, Math.min(offset, length));
  }

  private static final class Lines {
    private final String text;
    private final int[] starts;

    Lines(String text) {
      this.text = text;
      var positions = new ArrayList<Integer>();
      positions.add(0);
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '\r') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
          positions.add(i + 1);
        } else if (c == '\n') positions.add(i + 1);
      }
      starts = positions.stream().mapToInt(Integer::intValue).toArray();
    }

    Position at(int offset) {
      int index = Arrays.binarySearch(starts, offset);
      int line = index >= 0 ? index : -index - 2;
      int end = line + 1 < starts.length ? starts[line + 1] : text.length();
      while (end > starts[line] && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) end--;
      return new Position(line, Math.min(offset, end) - starts[line]);
    }
  }
}
