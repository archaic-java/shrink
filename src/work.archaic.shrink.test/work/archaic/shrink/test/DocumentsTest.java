package work.archaic.shrink.test;

import java.net.URI;
import java.util.Collection;
import work.archaic.service.compiler.v01.SourceSnapshot;
import work.archaic.service.test.v02.*;
import work.archaic.shrink.server.Documents;
import work.archaic.shrink.server.DocumentUpdateException;

public record DocumentsTest() implements TestSuite {
  @Override public void cases(Collection<TestCase> cases) {
    cases.add(new DebouncesAndRejectsObsoleteWork());
    cases.add(new CloseReopenInvalidatesReusedVersions());
    cases.add(new EditsDoNotStarveOtherDocuments());
    cases.add(new RejectsOutOfOrderVersions());
    cases.add(new RejectedOpensPreserveAcceptedDocuments());
  }

  static SourceSnapshot source(String name) {
    return new SourceSnapshot(URI.create("file:///" + name + ".java"), name + ".java", "class " + name + " {}");
  }
}

record DebouncesAndRejectsObsoleteWork() implements TestCase {
  @Override public void run(TestTrail trail) throws DocumentUpdateException {
    var docs = new Documents(150);
    var source = DocumentsTest.source("A");
    docs.open(source, 1, 0);
    assert docs.takeReady(149) == null : "Work must wait for the complete debounce interval";
    var old = docs.takeReady(150);
    assert docs.current(old) : "Ready work must initially be current";
    docs.change(source.uri(), 2, "class A { int n; }", 160);
    assert !docs.current(old) : "A newer document version must invalidate queued work";
    docs.change(source.uri(), 3, "class A { int n = 1; }", 170);
    assert docs.takeReady(310) == null : "Latest edit must restart the debounce interval";
    var newest = docs.takeReady(320);
    assert newest.version() == 3 : "Ready work must use the newest document version";
    assert docs.takeReady(500) == null : "Only one current snapshot may remain pending";
    assert docs.current(newest) : "Newest queued work must remain current";
    trail.note("Verified debounce replacement and stale-work rejection");
  }
}

record CloseReopenInvalidatesReusedVersions() implements TestCase {
  @Override public void run(TestTrail trail) throws DocumentUpdateException {
    var docs = new Documents(0);
    var source = DocumentsTest.source("A");
    docs.open(source, 1, 0);
    var old = docs.takeReady(0);
    docs.close(source.uri());
    docs.open(source, 1, 0);
    var reopened = docs.takeReady(0);
    assert !docs.current(old) : "Close and reopen must invalidate an earlier generation";
    assert docs.current(reopened) : "Reopened document work must be current";
    docs.clear();
    assert !docs.current(reopened) : "Clearing documents must invalidate all work";
    assert docs.takeReady(999) == null : "Clearing documents must remove pending work";
    trail.note("Verified close/reopen generations despite reused LSP versions");
  }
}

record EditsDoNotStarveOtherDocuments() implements TestCase {
  @Override public void run(TestTrail trail) throws DocumentUpdateException {
    var docs = new Documents(150);
    var a = DocumentsTest.source("A");
    var b = DocumentsTest.source("B");
    docs.open(a, 1, 0);
    docs.open(b, 1, 10);
    for (int i = 2; i <= 100; i++) docs.change(a.uri(), i, "class A {}", 20 + i);
    assert docs.takeReady(160).source().equals(b) : "Repeated edits must not starve another ready document";
    assert docs.takeReady(160) == null : "Frequently edited document must still await its last debounce deadline";
    assert docs.takeReady(300).version() == 100 : "Last edit must eventually become ready";
    trail.note("Verified fair scheduling across open documents");
  }
}

record RejectsOutOfOrderVersions() implements TestCase {
  @Override public void run(TestTrail trail) throws DocumentUpdateException {
    var docs = new Documents(0);
    var source = DocumentsTest.source("A");
    docs.open(source, 5, 0);
    boolean rejected = false;
    try { docs.change(source.uri(), 4, "broken", 0); } catch (DocumentUpdateException expected) { rejected = true; }
    assert rejected : "Non-increasing document versions must be rejected";
    assert docs.takeReady(0).source().equals(source) : "Rejected edits must preserve accepted work";
    trail.note("Verified LSP document-version ordering");
  }
}


record RejectedOpensPreserveAcceptedDocuments() implements TestCase {
  @Override public void run(TestTrail trail) throws DocumentUpdateException {
    var docs = new Documents(0);
    var first = DocumentsTest.source("First");
    docs.open(first, 1, 0);
    try {
      docs.open(new SourceSnapshot(first.uri(), first.fileName(), "broken"), 2, 0);
      assert false : "Duplicate opens must be rejected";
    } catch (DocumentUpdateException expected) { }
    assert docs.takeReady(0).source().equals(first) : "Rejected duplicate open must preserve accepted text";
    for (int i = 1; i < 128; i++) docs.open(DocumentsTest.source("File" + i), 1, 0);
    var overflow = DocumentsTest.source("Overflow");
    try {
      docs.open(overflow, 1, 0);
      assert false : "Opening a 129th document must be rejected";
    } catch (DocumentUpdateException expected) { }
    try {
      docs.change(overflow.uri(), 2, "broken", 0);
      assert false : "Rejected open must not create a document";
    } catch (DocumentUpdateException expected) { }
    docs.close(first.uri());
    docs.open(overflow, 1, 0);
    int pending = 0;
    while (docs.takeReady(0) != null) pending++;
    assert pending == 128 : "Rejection must preserve pending work and allow a later valid open";
    trail.note("Verified checked document rejection before snapshot mutation");
  }
}
