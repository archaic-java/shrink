package work.archaic.shrink.server;

/** A rejected client document update; accepted snapshots remain unchanged. */
public final class DocumentUpdateException extends Exception {
  public DocumentUpdateException(String message) { super(message); }
  public DocumentUpdateException(String message, Throwable cause) { super(message, cause); }
}
