package work.archaic.shrink.test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import work.archaic.service.test.v01.*;
import work.archaic.shrink.protocol.Framing;

public final class FramingTest implements TestSuite {
  @Test public void unicodeAndConsecutiveFramesSurviveShortReads() throws Exception {
    var bytes = new ByteArrayOutputStream();
    Framing.write(bytes, "{\"text\":\"Grüße 😀\"}");
    Framing.write(bytes, "{}");
    var input = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
      @Override public int read(byte[] b, int off, int len) throws IOException {
        return super.read(b, off, Math.min(len, 1));
      }
    };
    assert new String(Framing.read(input), StandardCharsets.UTF_8).equals("{\"text\":\"Grüße 😀\"}");
    assert new String(Framing.read(input), StandardCharsets.UTF_8).equals("{}");
    assert Framing.read(input) == null;
  }

  @Test public void rejectsUnrecoverableFrames() throws Exception {
    for (String frame : new String[] {"Content-Length: -1\r\n\r\n", "Content-Length: 8388609\r\n\r\n",
        "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}", "X: y\r\n\r\n{}",
        "Content-Length: 4\r\n\r\n{}", "Content-Length: 2\r\n", "X: " + "x".repeat(8192)}) {
      boolean rejected = false;
      try { Framing.read(new ByteArrayInputStream(frame.getBytes(StandardCharsets.US_ASCII))); }
      catch (IOException expected) { rejected = true; }
      assert rejected : frame.substring(0, Math.min(frame.length(), 100));
    }
  }
}
