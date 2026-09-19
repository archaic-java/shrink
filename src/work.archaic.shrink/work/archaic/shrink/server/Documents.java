package work.archaic.shrink.server;

import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import work.archaic.service.compiler.v01.SourceSnapshot;

/** Session-owned state. Time is supplied by the caller so scheduling tests need no sleeps. */
public final class Documents {
  public record Work(SourceSnapshot source, int version, long generation) {}
  private record Pending(Work work, long due) {}
  private final Map<URI, Work> open = new HashMap<>();
  private final LinkedHashMap<URI, Pending> pending = new LinkedHashMap<>();
  private final long debounceNanos;
  private long generation;

  public Documents(long debounceNanos) { this.debounceNanos = debounceNanos; }

  public void open(SourceSnapshot source, int version, long now) {
    if (open.containsKey(source.uri())) throw new IllegalArgumentException("Document already open");
    if (open.size() >= 128) throw new IllegalArgumentException("At most 128 open documents are supported");
    put(new Work(source, version, ++generation), now);
  }

  public void change(URI uri, int version, String text, long now) {
    Work old = open.get(uri);
    if (old == null) throw new IllegalArgumentException("Document is not open");
    if (version <= old.version()) throw new IllegalArgumentException("Document version must increase");
    put(new Work(new SourceSnapshot(uri, old.source().fileName(), text), version, old.generation()), now);
  }

  private void put(Work work, long now) {
    URI uri = work.source().uri();
    open.put(uri, work);
    // Moving an edited document to the back prevents it starving other ready documents.
    pending.remove(uri);
    pending.put(uri, new Pending(work, now + debounceNanos));
  }

  public void close(URI uri) {
    open.remove(uri);
    pending.remove(uri);
  }

  public boolean current(Work work) {
    Work present = open.get(work.source().uri());
    return present != null && present.version() == work.version()
        && present.generation() == work.generation();
  }

  public Work takeReady(long now) {
    var iterator = pending.values().iterator();
    while (iterator.hasNext()) {
      Pending next = iterator.next();
      if (now - next.due() >= 0) {
        iterator.remove();
        return next.work();
      }
    }
    return null;
  }

  public long waitNanos(long now) {
    long wait = Long.MAX_VALUE;
    for (Pending next : pending.values()) wait = Math.min(wait, Math.max(0, next.due() - now));
    return wait;
  }

  public void clear() { open.clear(); pending.clear(); }
}
