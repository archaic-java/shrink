package work.archaic.shrink.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import work.archaic.service.test.v02.*;
import work.archaic.shrink.protocol.Framing;

public record FramingTest() implements TestSuite {
  @Override public void cases(Collection<TestCase> cases) {
    cases.add(new UnicodeAndConsecutiveFramesSurviveShortReads());
    cases.add(new RejectsUnrecoverableFrames());
  }
}

record UnicodeAndConsecutiveFramesSurviveShortReads() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    var bytes = new ByteArrayOutputStream();
    Framing.write(bytes, "{\"text\":\"Grüße 😀\"}");
    Framing.write(bytes, "{}");
    var input = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
      @Override public int read(byte[] b, int off, int len) throws IOException {
        return super.read(b, off, Math.min(len, 1));
      }
    };
    assert new String(Framing.read(input), StandardCharsets.UTF_8).equals("{\"text\":\"Grüße 😀\"}")
        : "First UTF-8 frame must survive short reads";
    assert new String(Framing.read(input), StandardCharsets.UTF_8).equals("{}")
        : "Second consecutive frame must remain available";
    assert Framing.read(input) == null : "Clean EOF after complete frames must return null";
    trail.note("Verified UTF-8 LSP framing with one-byte reads");
  }
}

record RejectsUnrecoverableFrames() implements TestCase {
  @Override public void run(TestTrail trail) throws Exception {
    for (String frame : new String[] {"Content-Length: -1\r\n\r\n", "Content-Length: 8388609\r\n\r\n",
        "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}", "X: y\r\n\r\n{}",
        "Content-Length: 4\r\n\r\n{}", "Content-Length: 2\r\n", "X: " + "x".repeat(8192)}) {
      boolean rejected = false;
      try { Framing.read(new ByteArrayInputStream(frame.getBytes(StandardCharsets.US_ASCII))); }
      catch (IOException expected) { rejected = true; }
      assert rejected : "Malformed frame must be rejected: " + frame.substring(0, Math.min(frame.length(), 100));
    }
    trail.note("Verified framing limits and malformed-header rejection");
  }
}
