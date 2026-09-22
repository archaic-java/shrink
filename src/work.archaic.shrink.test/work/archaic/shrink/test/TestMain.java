package work.archaic.shrink.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Runs Minau and guards against its successful zero-test outcome. */
public final class TestMain {
  private TestMain() {}

  public static void main(String[] args) throws Exception {
    boolean assertions = false;
    assert assertions = true : "Test launcher requires assertions";
    if (!assertions) throw new IllegalStateException("Tests require -ea");
    var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-ea", "--module-path", "out:lib/bin", "--add-modules", "work.archaic.shrink.test,work.archaic.shrink.compiler,work.archaic.peep,org.eclipse.parsson",
        "-m", "work.archaic.minau/work.archaic.minau.Main", "work.archaic.shrink.test", "--debug")
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    System.out.print(output);
    int status = process.waitFor();
    if (status != 0 || !java.util.regex.Pattern.compile("Tests:\\s+[1-9][0-9]*").matcher(output).find()) {
      throw new AssertionError("Minau failed or discovered zero tests (exit " + status + ")");
    }
  }
}
