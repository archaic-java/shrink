package work.archaic.shrink.test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.Executors;
import work.archaic.service.compiler.v01.*;
import work.archaic.service.test.v02.*;

public record CompilerTest() implements TestSuite {
  @Override public void cases(Collection<TestCase> cases) {
    cases.add(new ParsesWithoutResolvingSymbols());
    cases.add(new ReportsSyntaxAndClearsIt());
    cases.add(new OffsetsUseOriginalUtf16Text());
    cases.add(new HandlesEndOfFileAndImmutableResults());
    cases.add(new AdapterCallsAreIndependent());
    cases.add(new IndependentModuleUsesCompilerService());
    cases.add(new NullSourceIsAProgrammingError());
  }

  static SourceSnapshot source(String name, String text) {
    return new SourceSnapshot(URI.create("file:///not-on-disk/" + name), name, text);
  }

  static CompilerAdapter compiler() {
    var providers = ServiceLoader.load(CompilerAdapter.class).stream().toList();
    if (providers.size() != 1) throw new IllegalStateException("Expected one compiler provider");
    return providers.getFirst().get();
  }
}

record ParsesWithoutResolvingSymbols() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    CompilerAdapter compiler = CompilerTest.compiler();
    SourceSnapshot input = CompilerTest.source("Demo.java", "class Demo { Missing dependency; int n = \"wrong type\"; }");
    ParseResult result = compiler.parse(input);
    trail.note("Parsing syntax without project dependencies");
    assert result.source().equals(input) : "Parser result must identify the analyzed source";
    assert result.diagnostics().isEmpty() : "Unknown symbols and type mismatches must not become syntax diagnostics";
    assert compiler.parse(CompilerTest.source("Empty.java", "")).diagnostics().isEmpty()
        : "Empty source must be syntactically valid";
    assert compiler.parse(CompilerTest.source("module-info.java", "module sample { requires nonexistent; }")).diagnostics().isEmpty()
        : "Unresolved module names must not become syntax diagnostics";
  }
}

record ReportsSyntaxAndClearsIt() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    var compiler = CompilerTest.compiler();
    var bad = compiler.parse(CompilerTest.source("Demo.java", "class Demo { int n = ; }"));
    trail.note("Checking a malformed field initializer");
    assert !bad.diagnostics().isEmpty() : "Invalid Java syntax must produce a diagnostic";
    var diagnostic = bad.diagnostics().getFirst();
    assert diagnostic.severity() == Diagnostic.Severity.ERROR : "Invalid syntax must be reported as an error";
    assert !diagnostic.code().isEmpty() : "Compiler diagnostics must retain their compiler code";
    assert !diagnostic.message().isEmpty() : "Compiler diagnostics must retain an explanation";
    assert compiler.parse(CompilerTest.source("Demo.java", "class Demo { int n = 1; }")).diagnostics().isEmpty()
        : "Correcting the field initializer must clear syntax diagnostics";
    assert !compiler.parse(CompilerTest.source("module-info.java", "module sample { requires ; }")).diagnostics().isEmpty()
        : "Invalid module declarations must produce syntax diagnostics";
  }
}

record OffsetsUseOriginalUtf16Text() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    var compiler = CompilerTest.compiler();
    for (String newline : List.of("\n", "\r\n", "\r")) {
      String second = "\tString s = \"😀\"; int n = ;";
      String text = "class Demo {" + newline + second + newline + "}";
      var diagnostic = compiler.parse(CompilerTest.source("Demo.java", text)).diagnostics().getFirst();
      assert diagnostic.range().start().equals(new Position(1, second.lastIndexOf(';')))
          : "Diagnostic position must use original UTF-16 text for newline " + newline.replace("\r", "CR").replace("\n", "LF");
    }
    String escaped = "class Demo { String s = \"" + "\\" + "u0061\"; int n = ; }";
    var diagnostic = compiler.parse(CompilerTest.source("Demo.java", escaped)).diagnostics().getFirst();
    assert diagnostic.range().start().character() == escaped.lastIndexOf(';')
        : "Unicode escapes must not shift diagnostic positions in the original text";
    String virtualLine = "class Demo { " + "\\" + "u000a int n = ; }";
    var onOriginalLine = compiler.parse(CompilerTest.source("Demo.java", virtualLine)).diagnostics().getFirst();
    assert onOriginalLine.range().start().equals(new Position(0, virtualLine.lastIndexOf(';')))
        : "Unicode line escapes must keep the diagnostic on the original line";
    trail.note("Verified line and character mapping for Java source text");
  }
}

record HandlesEndOfFileAndImmutableResults() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    var result = CompilerTest.compiler().parse(CompilerTest.source("Demo.java", "class Demo {\n"));
    assert !result.diagnostics().isEmpty() : "Unclosed source at EOF must produce a diagnostic";
    assert result.diagnostics().getFirst().range().end().line() <= 1 : "EOF range must remain within the input text";
    boolean immutable = false;
    try { result.diagnostics().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
    assert immutable : "Parse-result diagnostics must be immutable";
    var mutable = new ArrayList<Diagnostic>(result.diagnostics());
    var copy = new ParseResult(result.source(), mutable, List.of());
    mutable.clear();
    assert !copy.diagnostics().isEmpty() : "Parse results must copy supplied diagnostic collections";
    trail.note("Verified immutable parse-result diagnostics");
  }
}

record AdapterCallsAreIndependent() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    CompilerAdapter compiler = CompilerTest.compiler();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<java.util.concurrent.Future<ParseResult>>();
      for (int i = 0; i < 8; i++) {
        int index = i;
        futures.add(executor.submit(() -> compiler.parse(CompilerTest.source("File" + index + ".java", "class File" + index + " {}"))));
      }
      for (int i = 0; i < futures.size(); i++) {
        var result = futures.get(i).get(10, java.util.concurrent.TimeUnit.SECONDS);
        assert result.diagnostics().isEmpty() : "Independent parser call " + i + " must have no syntax errors";
        assert result.source().fileName().equals("File" + i + ".java") : "Parser call " + i + " must retain its own source identity";
      }
    }
    trail.note("Verified concurrent parser calls with independent compiler state");
  }
}

record IndependentModuleUsesCompilerService() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    Path directory = Files.createTempDirectory("shrink-consumer-");
    try {
      var descriptor = directory.resolve("module-info.java");
      var main = directory.resolve("Main.java");
      Files.writeString(descriptor, "module example.consumer { requires work.archaic.service.catalog; uses work.archaic.service.compiler.v01.CompilerAdapter; }");
      Files.writeString(main, """
          package example.consumer;
          import java.net.URI;
          import java.util.ServiceLoader;
          import work.archaic.service.compiler.v01.*;
          public class Main {
            public static void main(String[] args) throws Exception {
              var providers = ServiceLoader.load(CompilerAdapter.class).stream().toList();
              assert providers.size() == 1 : "Expected one compiler provider";
              CompilerAdapter compiler = providers.getFirst().get();
              var source = new SourceSnapshot(URI.create("memory:/A.java"), "A.java", "class A {}");
              assert compiler.parse(source).diagnostics().isEmpty() : "Expected valid source";
              for (String excluded : new String[]{"work.archaic.shrink", "jakarta.json", "org.eclipse.parsson", "work.archaic.minau", "work.archaic.culpa"}) {
                assert ModuleLayer.boot().findModule(excluded).isEmpty() : "Consumer must not resolve " + excluded;
              }
            }
          }
          """);
      String jdkBin = Path.of(System.getProperty("java.home"), "bin").toString();
      String modules = Path.of("out").toAbsolutePath().toString();
      var compile = new ProcessBuilder(jdkBin + "/javac", "--module-path", modules, "-d", directory.resolve("classes").toString(),
          descriptor.toString(), main.toString()).redirectErrorStream(true).start();
      var compilation = ConsumerProcess.await(compile);
      assert compilation.exitCode() == 0 : "Independent consumer must compile: " + compilation.output();
      var run = new ProcessBuilder(jdkBin + "/java", "-ea", "--module-path", modules + ":" + directory.resolve("classes"),
          "--add-modules", "work.archaic.shrink.compiler", "-m", "example.consumer/example.consumer.Main").redirectErrorStream(true).start();
      var execution = ConsumerProcess.await(run);
      assert execution.exitCode() == 0 : "Independent consumer must discover only the compiler provider: " + execution.output();
      trail.note("Compiled and ran a contract-only compiler consumer");
    } finally {
      try (var files = Files.walk(directory)) {
        for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
      }
    }
  }
}


/** Collect subprocess observations with one deadline for execution and pipe draining. */
final class ConsumerProcess {
  record Result(int exitCode, String output) {}
  private ConsumerProcess() {}

  static Result await(Process process) throws Exception {
    var output = new java.util.concurrent.CompletableFuture<String>();
    Thread reader = Thread.ofVirtual().start(() -> {
      try {
        output.complete(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
      } catch (java.io.IOException failure) { output.completeExceptionally(failure); }
    });
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    try {
      if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
        throw new java.util.concurrent.TimeoutException("Consumer process exceeded its deadline");
      }
      long remaining = Math.max(0, deadline - System.nanoTime());
      return new Result(process.exitValue(), output.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS));
    } finally {
      process.destroyForcibly();
      process.getInputStream().close();
      reader.interrupt();
      reader.join(1000);
    }
  }
}

record NullSourceIsAProgrammingError() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    var compiler = CompilerTest.compiler();
    try {
      compiler.parse(null);
      assert false : "A null source must be rejected as incorrect API usage";
    } catch (NullPointerException expected) {
      assert expected.getMessage().equals("source") : "Null-source guard must identify the invalid argument";
    }
    assert compiler.parse(CompilerTest.source("Valid.java", "class Valid {}")).diagnostics().isEmpty()
        : "Rejected API input must leave the adapter reusable";
    trail.note("Verified null precondition stays outside compiler-failure translation");
  }
}
