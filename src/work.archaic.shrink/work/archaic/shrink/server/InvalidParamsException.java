package work.archaic.shrink.server;

/** A malformed client parameter at the protocol boundary. */
public final class InvalidParamsException extends Exception {
  public InvalidParamsException(String message) { super(message); }
  public InvalidParamsException(String message, Throwable cause) { super(message, cause); }
}
