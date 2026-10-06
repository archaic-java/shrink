package work.archaic.shrink.protocol;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Byte framing only. One reader and one writer own their respective stream. */
public final class Framing {
  public static final int MAX_BODY = 8 * 1024 * 1024;
  private static final int MAX_HEADER = 8192;

  private Framing() {}

  /** Returns null for clean EOF between frames; a partial frame is an error. */
  public static byte[] read(InputStream input) throws IOException {
    var header = new ByteArrayOutputStream();
    int matched = 0;
    byte[] delimiter = {'\r', '\n', '\r', '\n'};
    while (matched < delimiter.length) {
      int b = input.read();
      if (b < 0 && header.size() == 0) return null;
      if (b < 0) throw new EOFException("Truncated LSP header");
      if (b > 127 || b == 0) throw new IOException("Non-ASCII LSP header");
      header.write(b);
      if (header.size() > MAX_HEADER) throw new IOException("LSP header exceeds 8 KiB");
      matched = matchDelimiter(b, matched, delimiter);
    }
    String text = header.toString(StandardCharsets.US_ASCII);
    Integer length = null;
    for (String line : text.substring(0, text.length() - 4).split("\r\n", -1)) {
      int colon = line.indexOf(':');
      if (colon <= 0 || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
        throw new IOException("Malformed LSP header");
      }
      String name = line.substring(0, colon).strip();
      String value = line.substring(colon + 1).strip();
      if (name.equalsIgnoreCase("Content-Length")) {
        length = contentLength(value, length);
        continue;
      }
      if (!name.equalsIgnoreCase("Content-Type")) continue;
      validateContentType(value);
    }
    if (length == null) throw new IOException("Missing Content-Length");
    byte[] body = input.readNBytes(length);
    if (body.length != length) throw new EOFException("Truncated LSP body");
    return body;
  }

  private static int matchDelimiter(int value, int matched, byte[] delimiter) {
    if (value == delimiter[matched]) return matched + 1;
    if (value == '\r') return 1;
    return 0;
  }

  private static int contentLength(String value, Integer previous) throws IOException {
    if (previous != null || !value.matches("[0-9]{1,9}")) {
      throw new IOException("Invalid or duplicate Content-Length");
    }
    int length = Integer.parseInt(value);
    if (length > MAX_BODY) throw new IOException("LSP body exceeds 8 MiB");
    return length;
  }

  private static void validateContentType(String value) throws IOException {
    int index = value.toLowerCase(java.util.Locale.ROOT).indexOf("charset=");
    if (index < 0) return;
    String charset = value.substring(index + 8).strip();
    if (!charset.equalsIgnoreCase("utf-8") && !charset.equalsIgnoreCase("utf8")) {
      throw new IOException("Only UTF-8 LSP bodies are supported");
    }
  }

  public static void write(OutputStream output, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    if (body.length > MAX_BODY) throw new IOException("Outgoing LSP body exceeds 8 MiB");
    output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    output.write(body);
    output.flush();
  }
}

