package work.archaic.shrink.server;

/** Session transport or interruption failure, preserving its original cause. */
public final class SessionException extends Exception {
  public SessionException(String message, Throwable cause) { super(message, cause); }
}
