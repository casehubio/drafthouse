# DocumentSet Refactor Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three code-quality issues from code review on #59: DocumentSet thread-safety race, triplicated JSON serialization, and private `_panels` access.

**Architecture:** DocumentSet gets `synchronized` on all methods (plain ArrayList replaces CopyOnWriteArrayList). A new `DocumentSetJson` utility in `server/runtime/` consolidates three JSON serialization sites. `drafthouse-diff.js` gets a `currentPath(slot)` public accessor.

**Tech Stack:** Java 17, JUnit 5, AssertJ, JavaScript (Web Components)

---

### Task 1: DocumentSet thread safety — tests

**Files:**
- Modify: `server/api/src/test/java/io/casehub/drafthouse/DocumentSetTest.java`

- [ ] **Step 1: Write concurrent-add test**

Add to `DocumentSetTest.java`:

```java
@Test
void add_concurrentSamePath_onlyOneSucceeds() throws Exception {
    var set = new DocumentSet();
    int threadCount = 20;
    var executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    var latch = new java.util.concurrent.CountDownLatch(1);
    var futures = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();

    for (int i = 0; i < threadCount; i++) {
        futures.add(executor.submit(() -> {
            latch.await();
            return set.add("/race.md", "label");
        }));
    }
    latch.countDown();

    long trueCount = futures.stream()
            .map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } })
            .filter(b -> b)
            .count();

    executor.shutdown();
    assertThat(trueCount).isEqualTo(1);
    assertThat(set.documents()).hasSize(1);
}
```

- [ ] **Step 2: Write concurrent add+remove interleaving test**

Add to `DocumentSetTest.java`:

```java
@Test
void addAndRemove_concurrent_noExceptionOrLostUpdate() throws Exception {
    var set = new DocumentSet();
    int iterations = 100;
    var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
    var latch = new java.util.concurrent.CountDownLatch(1);

    var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
    for (int i = 0; i < iterations; i++) {
        String path = "/doc-" + i + ".md";
        futures.add(executor.submit(() -> {
            latch.await();
            set.add(path, "label");
            return null;
        }));
        futures.add(executor.submit(() -> {
            latch.await();
            set.remove(path);
            return null;
        }));
    }
    latch.countDown();

    for (var f : futures) {
        assertThatCode(() -> f.get()).doesNotThrowAnyException();
    }
    executor.shutdown();
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DocumentSetTest`

Expected: `add_concurrentSamePath_onlyOneSucceeds` FAILS (race condition allows duplicates). The interleaving test may pass or fail non-deterministically.

- [ ] **Step 4: Commit failing tests**

```
git add server/api/src/test/java/io/casehub/drafthouse/DocumentSetTest.java
git commit -m "test: concurrent DocumentSet tests — expose check-then-act race

Refs #67"
```

---

### Task 2: DocumentSet thread safety — implementation

**Files:**
- Modify: `server/api/src/main/java/io/casehub/drafthouse/DocumentSet.java`

- [ ] **Step 1: Replace CopyOnWriteArrayList with synchronized ArrayList**

Replace the full `DocumentSet.java` content:

```java
package io.casehub.drafthouse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class DocumentSet {

    private final ArrayList<DocumentEntry> documents = new ArrayList<>();
    private ComparisonPair currentComparison;

    public record DocumentEntry(String path, String label) {
        public DocumentEntry {
            Objects.requireNonNull(path, "path");
            if (path.isBlank()) throw new IllegalArgumentException("path must be non-blank");
            Objects.requireNonNull(label, "label");
        }
    }

    public record ComparisonPair(String pathA, String pathB) {}

    public synchronized boolean add(String path, String label) {
        for (DocumentEntry e : documents) {
            if (e.path().equals(path)) return false;
        }
        documents.add(new DocumentEntry(path, label));
        return true;
    }

    public synchronized boolean remove(String path) {
        boolean removed = documents.removeIf(e -> e.path().equals(path));
        if (removed) {
            ComparisonPair cp = currentComparison;
            if (cp != null && (path.equals(cp.pathA()) || path.equals(cp.pathB()))) {
                currentComparison = null;
            }
        }
        return removed;
    }

    public synchronized List<DocumentEntry> documents() {
        return List.copyOf(documents);
    }

    public synchronized Optional<DocumentEntry> primary() {
        return documents.isEmpty() ? Optional.empty() : Optional.of(documents.get(0));
    }

    public synchronized void setComparison(String pathA, String pathB) {
        currentComparison = new ComparisonPair(pathA, pathB);
    }

    public synchronized void clearComparison() {
        currentComparison = null;
    }

    public synchronized ComparisonPair currentComparison() {
        return currentComparison;
    }

    public static DocumentSet copyOf(DocumentSet source) {
        var copy = new DocumentSet();
        synchronized (source) {
            for (DocumentEntry e : source.documents) {
                copy.documents.add(e);
            }
            copy.currentComparison = source.currentComparison;
        }
        return copy;
    }
}
```

- [ ] **Step 2: Run all DocumentSet tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml test -pl api -Dtest=DocumentSetTest`

Expected: ALL PASS (14 tests including the two new concurrent tests).

- [ ] **Step 3: Commit**

```
git add server/api/src/main/java/io/casehub/drafthouse/DocumentSet.java
git commit -m "fix: DocumentSet thread safety — synchronized ArrayList replaces CopyOnWriteArrayList

Check-then-act race in add() and read-modify-write race in remove()
are now atomic under the instance monitor. copyOf synchronizes on
source for consistent snapshot.

Refs #67"
```

---

### Task 3: DocumentSetJson utility — tests

**Files:**
- Create: `server/runtime/src/test/java/io/casehub/drafthouse/DocumentSetJsonTest.java`

- [ ] **Step 1: Write DocumentSetJsonTest**

```java
package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DocumentSetJsonTest {

    @Test
    void documentsToJson_emptyList() {
        var set = new DocumentSet();
        assertThat(DocumentSetJson.documentsToJson(set.documents())).isEqualTo("[]");
    }

    @Test
    void documentsToJson_singleDocument() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        assertThat(DocumentSetJson.documentsToJson(set.documents()))
                .isEqualTo("[{\"path\":\"/a.md\",\"label\":\"spec\"}]");
    }

    @Test
    void documentsToJson_multipleDocuments() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        String json = DocumentSetJson.documentsToJson(set.documents());
        assertThat(json).startsWith("[{");
        assertThat(json).contains("\"path\":\"/a.md\"");
        assertThat(json).contains("\"path\":\"/b.md\"");
        assertThat(json).endsWith("}]");
    }

    @Test
    void documentsToJson_escapesSpecialChars() {
        var set = new DocumentSet();
        set.add("/path/with \"quotes\".md", "label\nwith\nnewlines");
        String json = DocumentSetJson.documentsToJson(set.documents());
        assertThat(json).contains("\\\"quotes\\\"");
        assertThat(json).contains("\\n");
    }

    @Test
    void comparisonToJson_nonNull() {
        var cp = new DocumentSet.ComparisonPair("/a.md", "/b.md");
        assertThat(DocumentSetJson.comparisonToJson(cp))
                .isEqualTo("{\"pathA\":\"/a.md\",\"pathB\":\"/b.md\"}");
    }

    @Test
    void comparisonToJson_null() {
        assertThat(DocumentSetJson.comparisonToJson(null)).isEqualTo("null");
    }

    @Test
    void documentsAndComparisonToJson_withComparison() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        set.add("/b.md", "impl");
        set.setComparison("/a.md", "/b.md");
        String json = DocumentSetJson.documentsAndComparisonToJson(set);
        assertThat(json).startsWith("{\"documents\":[");
        assertThat(json).contains("\"currentComparison\":{\"pathA\":\"/a.md\",\"pathB\":\"/b.md\"}");
        assertThat(json).endsWith("}");
    }

    @Test
    void documentsAndComparisonToJson_withoutComparison() {
        var set = new DocumentSet();
        set.add("/a.md", "spec");
        String json = DocumentSetJson.documentsAndComparisonToJson(set);
        assertThat(json).contains("\"currentComparison\":null");
    }

    @Test
    void comparisonToJson_pathsWithSpecialChars() {
        var cp = new DocumentSet.ComparisonPair("/path\twith\ttabs.md", "/normal.md");
        String json = DocumentSetJson.comparisonToJson(cp);
        assertThat(json).contains("\\t");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DocumentSetJsonTest`

Expected: FAIL — `DocumentSetJson` class does not exist.

- [ ] **Step 3: Commit failing tests**

```
git add server/runtime/src/test/java/io/casehub/drafthouse/DocumentSetJsonTest.java
git commit -m "test: DocumentSetJson tests — JSON serialization for document sets

Refs #67"
```

---

### Task 4: DocumentSetJson utility — implementation

**Files:**
- Create: `server/runtime/src/main/java/io/casehub/drafthouse/DocumentSetJson.java`

- [ ] **Step 1: Create DocumentSetJson**

```java
package io.casehub.drafthouse;

import java.util.List;

class DocumentSetJson {

    private DocumentSetJson() {}

    static String documentsToJson(List<DocumentSet.DocumentEntry> docs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"path\":").append(escapeAndQuote(docs.get(i).path()))
              .append(",\"label\":").append(escapeAndQuote(docs.get(i).label())).append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    static String comparisonToJson(DocumentSet.ComparisonPair cp) {
        if (cp == null) return "null";
        return "{\"pathA\":" + escapeAndQuote(cp.pathA())
                + ",\"pathB\":" + escapeAndQuote(cp.pathB()) + "}";
    }

    static String documentsAndComparisonToJson(DocumentSet documentSet) {
        return "{\"documents\":" + documentsToJson(documentSet.documents())
                + ",\"currentComparison\":" + comparisonToJson(documentSet.currentComparison()) + "}";
    }

    private static String escapeAndQuote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
```

- [ ] **Step 2: Run DocumentSetJsonTest**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest=DocumentSetJsonTest`

Expected: ALL PASS (9 tests).

- [ ] **Step 3: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DocumentSetJson.java
git commit -m "feat: DocumentSetJson — consolidated document set JSON serialization

Package-private utility in runtime/ (transport concern, not domain).
Replaces three duplicate serialization sites in DebateEventResource
and DebateMcpTools.

Refs #67"
```

---

### Task 5: Wire DocumentSetJson into call sites

**Files:**
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java`
- Modify: `server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java`

- [ ] **Step 1: Update DebateEventResource.pushDocumentsChanged()**

Replace lines 66–74 of `DebateEventResource.java` (the `pushDocumentsChanged` method body):

```java
public void pushDocumentsChanged(UUID channelId, DocumentSet documentSet) {
    try {
        String json = "{\"type\":\"documents-changed\",\"documents\":"
                + DocumentSetJson.documentsToJson(documentSet.documents()) + "}";
        pendingDocuments.put(channelId, json);
    } catch (Exception e) {
        LOG.warning("Failed to build documents-changed JSON: " + e.getMessage());
    }
}
```

- [ ] **Step 2: Update DebateEventResource.getDocuments()**

Replace lines 243–258 of `DebateEventResource.java` (the response body construction inside `getDocuments`):

```java
@GET
@Path("/{debateSessionId}/documents")
@Produces(MediaType.APPLICATION_JSON)
public jakarta.ws.rs.core.Response getDocuments(
        @PathParam("debateSessionId") String debateSessionId) {
    UUID channelId = parseSessionId(debateSessionId);
    DebateSession session = registry.find(channelId)
            .orElseThrow(() -> new NotFoundException("No active debate session: " + debateSessionId));

    return jakarta.ws.rs.core.Response.ok(
            DocumentSetJson.documentsAndComparisonToJson(session.documentSet())).build();
}
```

- [ ] **Step 3: Update DebateMcpTools.listDocuments() and delete buildDocumentsJson()**

In `DebateMcpTools.java`, replace the `listDocuments` method body (line 575):

```java
@Tool(name = "list_documents",
      description = "List all documents in the working set and the current comparison pair.")
public String listDocuments(
        @ToolArg(description = "debateSessionId returned by start_debate") String debateSessionId) {
    DebateSession session = resolveSession(debateSessionId);
    if (session == null) return sessionError(debateSessionId);

    return DocumentSetJson.documentsAndComparisonToJson(session.documentSet());
}
```

Delete the `buildDocumentsJson()` private method (lines 660–677).

- [ ] **Step 4: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: ALL PASS. The existing `DebateMcpToolsTest`, `DebateEventResourceTest`, and E2E tests validate that behavior is unchanged.

- [ ] **Step 5: Commit**

```
git add server/runtime/src/main/java/io/casehub/drafthouse/DebateEventResource.java server/runtime/src/main/java/io/casehub/drafthouse/DebateMcpTools.java
git commit -m "refactor: wire DocumentSetJson into DebateEventResource and DebateMcpTools

pushDocumentsChanged(), getDocuments(), and listDocuments() now
delegate to DocumentSetJson. Deleted triplicated buildDocumentsJson().

Refs #67"
```

---

### Task 6: Public accessor on drafthouse-diff.js + index.html fix

**Files:**
- Modify: `panels/drafthouse-diff.js`
- Modify: `index.html`

- [ ] **Step 1: Add currentPath(slot) to drafthouse-diff.js**

After the `loadFile` method (around line 505), add:

```js
currentPath(slot) {
  return this._panels[slot]?.path || null;
}
```

- [ ] **Step 2: Replace _panels access in index.html**

At lines 251-252, replace:

```js
const currentA = slot === 'a' ? doc.path : (diffPanel._panels?.a?.path || '');
const currentB = slot === 'b' ? doc.path : (diffPanel._panels?.b?.path || '');
```

with:

```js
const currentA = slot === 'a' ? doc.path : (diffPanel.currentPath('a') || '');
const currentB = slot === 'b' ? doc.path : (diffPanel.currentPath('b') || '');
```

- [ ] **Step 3: Manual verification**

Build and run:
```
/opt/homebrew/bin/mvn -f server/pom.xml package -DskipTests
java -Dui.dir=/Users/mdproctor/claude/casehub/drafthouse -jar server/runtime/target/drafthouse-server-runner.jar
```

Open `http://localhost:9001/?a=sample-a.md&b=sample-b.md` in a browser. Click document dropdown buttons (A/B) — verify the diff panel switches documents correctly and the comparison POST fires.

- [ ] **Step 4: Commit**

```
git add panels/drafthouse-diff.js index.html
git commit -m "refactor: public currentPath(slot) accessor on drafthouse-diff

Replaces direct _panels private field access from index.html.
Preserves @casehub/ui Component encapsulation contract.

Refs #67"
```

---

### Task 7: Final verification and close

- [ ] **Step 1: Run full test suite**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime`

Expected: ALL PASS.

- [ ] **Step 2: Run E2E tests**

Run: `/opt/homebrew/bin/mvn -f server/pom.xml install -DskipTests && /opt/homebrew/bin/mvn -f server/pom.xml test -pl runtime -Dtest="io.casehub.drafthouse.e2e.*"`

Expected: ALL PASS.
