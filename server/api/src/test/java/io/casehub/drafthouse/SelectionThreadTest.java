package io.casehub.drafthouse;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SelectionThreadTest {

    @Test
    void startThread_createsOpenThread() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        SelectionScope anchor = new SelectionScope(DocumentSide.A, 10, 15, "selected text");

        String threadId = session.startThread(anchor);

        assertNotNull(threadId);
        SelectionThread thread = session.threads().get(threadId);
        assertNotNull(thread);
        assertEquals(ThreadStatus.OPEN, thread.status());
        assertEquals(anchor, thread.anchor());
    }

    @Test
    void resolveThread_setsStatusToResolved() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        SelectionScope anchor = new SelectionScope(DocumentSide.A, 10, 15, "selected text");
        String threadId = session.startThread(anchor);

        session.resolveThread(threadId);

        assertEquals(ThreadStatus.RESOLVED, session.threads().get(threadId).status());
    }

    @Test
    void resolveThread_unknownId_throws() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        assertThrows(IllegalArgumentException.class, () -> session.resolveThread("bogus"));
    }

    @Test
    void resolveThread_alreadyResolved_throws() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        String threadId = session.startThread(new SelectionScope(DocumentSide.A, 1, 5, "text"));
        session.resolveThread(threadId);

        assertThrows(IllegalArgumentException.class, () -> session.resolveThread(threadId));
    }

    @Test
    void findThreadsNear_overlapping_returnsMatch() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        SelectionScope anchor = new SelectionScope(DocumentSide.A, 10, 15, "first");
        session.startThread(anchor);

        SelectionScope query = new SelectionScope(DocumentSide.A, 12, 18, "overlap");
        List<SelectionThread> nearby = session.findThreadsNear(query);

        assertEquals(1, nearby.size());
    }

    @Test
    void findThreadsNear_differentSide_returnsEmpty() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        session.startThread(new SelectionScope(DocumentSide.A, 10, 15, "first"));

        SelectionScope query = new SelectionScope(DocumentSide.B, 10, 15, "same lines different side");
        List<SelectionThread> nearby = session.findThreadsNear(query);

        assertTrue(nearby.isEmpty());
    }

    @Test
    void findThreadsNear_noOverlap_returnsEmpty() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        session.startThread(new SelectionScope(DocumentSide.A, 10, 15, "first"));

        SelectionScope query = new SelectionScope(DocumentSide.A, 20, 25, "no overlap");
        List<SelectionThread> nearby = session.findThreadsNear(query);

        assertTrue(nearby.isEmpty());
    }

    @Test
    void findThreadsNear_adjacentNotOverlapping_returnsEmpty() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        session.startThread(new SelectionScope(DocumentSide.A, 10, 15, "first"));

        SelectionScope query = new SelectionScope(DocumentSide.A, 16, 20, "adjacent");
        List<SelectionThread> nearby = session.findThreadsNear(query);

        assertTrue(nearby.isEmpty());
    }

    @Test
    void findThreadsNear_exactSameRange_returnsMatch() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        session.startThread(new SelectionScope(DocumentSide.A, 10, 15, "first"));

        SelectionScope query = new SelectionScope(DocumentSide.A, 10, 15, "same range");
        List<SelectionThread> nearby = session.findThreadsNear(query);

        assertEquals(1, nearby.size());
    }

    @Test
    void threads_includedInSnapshot() {
        DebateSession session = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        session.addDocument("/path/to/spec.md", "spec");
        String threadId = session.startThread(new SelectionScope(DocumentSide.A, 1, 5, "text"));

        DebateSessionSnapshot snapshot = session.snapshot();

        assertNotNull(snapshot.threads());
        assertEquals(1, snapshot.threads().size());
        assertTrue(snapshot.threads().containsKey(threadId));
    }

    @Test
    void fromSnapshot_restoresThreads() {
        DebateSession original = new DebateSession(
                UUID.randomUUID(), "test-session", "test/channel", "agent-1");
        original.addDocument("/path/to/spec.md", "spec");
        String threadId = original.startThread(new SelectionScope(DocumentSide.A, 1, 5, "text"));

        DebateSessionSnapshot snapshot = original.snapshot();
        DebateSession restored = DebateSession.fromSnapshot(snapshot);

        assertEquals(1, restored.threads().size());
        SelectionThread thread = restored.threads().get(threadId);
        assertNotNull(thread);
        assertEquals(ThreadStatus.OPEN, thread.status());
    }
}
