package work.archaic.shrink.test;

import java.net.URI;
import work.archaic.service.compiler.v01.SourceSnapshot;
import work.archaic.service.test.v01.*;
import work.archaic.shrink.server.Documents;

public final class DocumentsTest implements TestSuite {
  private static SourceSnapshot source(String name) {
    return new SourceSnapshot(URI.create("file:///" + name + ".java"), name + ".java", "class " + name + " {}");
  }

  @Test public void debouncesAndRejectsObsoleteWork() {
    var docs = new Documents(150);
    var source = source("A");
    docs.open(source, 1, 0);
    assert docs.takeReady(149) == null;
    var old = docs.takeReady(150);
    assert docs.current(old);
    docs.change(source.uri(), 2, "class A { int n; }", 160);
    assert !docs.current(old);
    docs.change(source.uri(), 3, "class A { int n = 1; }", 170);
    assert docs.takeReady(310) == null;
    var newest = docs.takeReady(320);
    assert newest.version() == 3;
    assert docs.takeReady(500) == null;
    assert docs.current(newest);
  }

  @Test public void closeReopenInvalidatesEvenReusedVersions() {
    var docs = new Documents(0);
    var source = source("A");
    docs.open(source, 1, 0);
    var old = docs.takeReady(0);
    docs.close(source.uri());
    docs.open(source, 1, 0);
    var reopened = docs.takeReady(0);
    assert !docs.current(old);
    assert docs.current(reopened);
    docs.clear();
    assert !docs.current(reopened);
    assert docs.takeReady(999) == null;
  }

  @Test public void editsDoNotStarveOtherDocuments() {
    var docs = new Documents(150);
    var a = source("A");
    var b = source("B");
    docs.open(a, 1, 0);
    docs.open(b, 1, 10);
    for (int i = 2; i <= 100; i++) docs.change(a.uri(), i, "class A {}", 20 + i);
    assert docs.takeReady(160).source().equals(b);
    assert docs.takeReady(160) == null;
    assert docs.takeReady(300).version() == 100;
  }

  @Test public void rejectsOutOfOrderVersions() {
    var docs = new Documents(0);
    var source = source("A");
    docs.open(source, 5, 0);
    boolean rejected = false;
    try { docs.change(source.uri(), 4, "broken", 0); } catch (IllegalArgumentException expected) { rejected = true; }
    assert rejected;
    assert docs.takeReady(0).source().equals(source);
  }
}
