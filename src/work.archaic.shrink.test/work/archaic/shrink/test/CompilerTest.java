package work.archaic.shrink.test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import work.archaic.service.compiler.v01.*;
import work.archaic.service.test.v01.*;
import work.archaic.shrink.compiler.JavacCompiler;

public final class CompilerTest implements TestSuite {
  private static SourceSnapshot source(String name, String text) {
    return new SourceSnapshot(URI.create("file:///not-on-disk/" + name), name, text);
  }

  @Test public void parsesWithoutResolvingSymbols() throws Exception {
    CompilerAdapter compiler = new JavacCompiler();
    SourceSnapshot input = source("Demo.java", "class Demo { Missing dependency; int n = \"wrong type\"; }");
    ParseResult result = compiler.parse(input);
    assert result.source().equals(input);
    assert result.diagnostics().isEmpty() : result;
    assert compiler.parse(source("Empty.java", "")).diagnostics().isEmpty();
    assert compiler.parse(source("module-info.java", "module sample { requires nonexistent; }")).diagnostics().isEmpty();
  }

  @Test public void reportsSyntaxAndClearsIt() throws Exception {
    var compiler = new JavacCompiler();
    var bad = compiler.parse(source("Demo.java", "class Demo { int n = ; }"));
    assert !bad.diagnostics().isEmpty();
    var diagnostic = bad.diagnostics().getFirst();
    assert diagnostic.severity() == Diagnostic.Severity.ERROR;
    assert !diagnostic.code().isEmpty();
    assert !diagnostic.message().isEmpty();
    assert compiler.parse(source("Demo.java", "class Demo { int n = 1; }")).diagnostics().isEmpty();
    assert !compiler.parse(source("module-info.java", "module sample { requires ; }")).diagnostics().isEmpty();
  }

  @Test public void offsetsUseOriginalUtf16Text() throws Exception {
    var compiler = new JavacCompiler();
    for (String newline : List.of("\n", "\r\n", "\r")) {
      String second = "\tString s = \"😀\"; int n = ;";
      String text = "class Demo {" + newline + second + newline + "}";
      var diagnostic = compiler.parse(source("Demo.java", text)).diagnostics().getFirst();
      assert diagnostic.range().start().equals(new Position(1, second.lastIndexOf(';'))) : diagnostic;
    }
    String escaped = "class Demo { String s = \"" + "\\" + "u0061\"; int n = ; }";
    var diagnostic = compiler.parse(source("Demo.java", escaped)).diagnostics().getFirst();
    assert diagnostic.range().start().character() == escaped.lastIndexOf(';') : diagnostic;
    String virtualLine = "class Demo { " + "\\" + "u000a int n = ; }";
    var onOriginalLine = compiler.parse(source("Demo.java", virtualLine)).diagnostics().getFirst();
    assert onOriginalLine.range().start().equals(new Position(0, virtualLine.lastIndexOf(';'))) : onOriginalLine;
  }

  @Test public void handlesEndOfFileAndImmutableResults() throws Exception {
    var result = new JavacCompiler().parse(source("Demo.java", "class Demo {\n"));
    assert !result.diagnostics().isEmpty();
    assert result.diagnostics().getFirst().range().end().line() <= 1;
    boolean immutable = false;
    try { result.diagnostics().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
    assert immutable;
    var mutable = new ArrayList<Diagnostic>(result.diagnostics());
    var copy = new ParseResult(result.source(), mutable, List.of());
    mutable.clear();
    assert !copy.diagnostics().isEmpty();
  }

  @Test public void adapterCallsAreIndependent() throws Exception {
    CompilerAdapter compiler = new JavacCompiler();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<ParseResult>>();
      for (int i = 0; i < 8; i++) {
        int index = i;
        futures.add(executor.submit(() -> compiler.parse(source("File" + index + ".java", "class File" + index + " {}"))));
      }
      for (int i = 0; i < futures.size(); i++) {
        var result = futures.get(i).get();
        assert result.diagnostics().isEmpty();
        assert result.source().fileName().equals("File" + i + ".java");
      }
    }
  }

  @Test public void independentModuleNeedsNeitherServerNorJson() throws Exception {
    var directory = java.nio.file.Files.createTempDirectory("shrink-consumer-");
    try {
      var descriptor = directory.resolve("module-info.java");
      var main = directory.resolve("Main.java");
      java.nio.file.Files.writeString(descriptor, "module example.consumer { requires work.archaic.shrink.compiler; }");
      java.nio.file.Files.writeString(main, """
          package example.consumer;
          import java.net.URI;
          import work.archaic.shrink.compiler.JavacCompiler;
          import work.archaic.service.compiler.v01.*;
          public class Main {
            public static void main(String[] args) throws Exception {
              CompilerAdapter compiler = new JavacCompiler();
              var source = new SourceSnapshot(URI.create("memory:/A.java"), "A.java", "class A {}");
              if (!compiler.parse(source).diagnostics().isEmpty()) throw new AssertionError();
              for (String excluded : new String[]{"work.archaic.shrink", "jakarta.json", "org.eclipse.parsson", "work.archaic.minau"}) {
                if (ModuleLayer.boot().findModule(excluded).isPresent()) throw new AssertionError(excluded);
              }
            }
          }
          """);
      String jdkBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin").toString();
      String modules = java.nio.file.Path.of("out").toAbsolutePath().toString();
      var compile = new ProcessBuilder(jdkBin + "/javac", "--module-path", modules, "-d", directory.resolve("classes").toString(),
          descriptor.toString(), main.toString()).redirectErrorStream(true).start();
      String compileOutput = new String(compile.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assert compile.waitFor() == 0 : compileOutput;
      var run = new ProcessBuilder(jdkBin + "/java", "--module-path", modules + ":" + directory.resolve("classes"),
          "-m", "example.consumer/example.consumer.Main").redirectErrorStream(true).start();
      String runOutput = new String(run.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assert run.waitFor() == 0 : runOutput;
    } finally {
      try (var files = java.nio.file.Files.walk(directory)) {
        for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(file);
      }
    }
  }
}
