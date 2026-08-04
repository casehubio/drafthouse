package io.casehub.drafthouse.debate;

import io.casehub.drafthouse.DocumentSide;
import io.casehub.drafthouse.ThreadStatus;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static io.casehub.drafthouse.debate.DebateProtocol.META_SENTINEL;
import static org.assertj.core.api.Assertions.assertThat;

class ThreadProjectionTest {

    private ThreadProjection projection;

    @BeforeEach
    void setUp() {
        projection = new ThreadProjection();
    }

    private static MessageView threadMsg(String action, String threadId,
                                          String role, String content,
                                          String extraMeta) {
        String metaHeader = "threadId=" + threadId + "|threadAction=" + action + "|role=" + role;
        if (extraMeta != null && !extraMeta.isEmpty()) {
            metaHeader += "|" + extraMeta;
        }
        String encoded = META_SENTINEL + metaHeader + "\n\n" + content;
        return new MessageView(1L, null, "sender-1", MessageType.QUERY,
                encoded, threadId, null, null, null, List.of(),
                ActorType.AGENT, Instant.now(), null, 0);
    }

    private static MessageView debateMsg(String entryType, String role, String content) {
        String metaHeader = "entryType=" + entryType + "|role=" + role + "|round=1|priority=HIGH|scope=ISOLATED";
        String encoded = META_SENTINEL + metaHeader + "\n\n" + content;
        return new MessageView(2L, null, "sender-1", MessageType.QUERY,
                encoded, "pt-1", null, null, null, List.of(),
                ActorType.AGENT, Instant.now(), null, 0);
    }

    @Test
    void start_createsThread() {
        ThreadState state = projection.identity();
        state = projection.apply(state, threadMsg("START", "t1", "REV",
                "Initial comment",
                "side=A|startLine=10|endLine=15|selectedText=hello world"));

        assertThat(state.threads()).hasSize(1);
        ThreadView view = state.threads().get("t1");
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo(ThreadStatus.OPEN);
        assertThat(view.anchor().side()).isEqualTo(DocumentSide.A);
        assertThat(view.anchor().startLine()).isEqualTo(10);
        assertThat(view.anchor().endLine()).isEqualTo(15);
        assertThat(view.entries()).hasSize(1);
        assertThat(view.entries().get(0).content()).isEqualTo("Initial comment");
        assertThat(view.createdBy()).isEqualTo("REV");
    }

    @Test
    void reply_addsEntry() {
        ThreadState state = projection.identity();
        state = projection.apply(state, threadMsg("START", "t1", "REV",
                "Initial",
                "side=A|startLine=10|endLine=15|selectedText=text"));
        state = projection.apply(state, threadMsg("REPLY", "t1", "HUMAN",
                "Good point", null));

        assertThat(state.threads().get("t1").entries()).hasSize(2);
        assertThat(state.threads().get("t1").entries().get(1).content()).isEqualTo("Good point");
        assertThat(state.threads().get("t1").entries().get(1).agentRole()).isEqualTo("HUMAN");
    }

    @Test
    void resolve_updatesStatus() {
        ThreadState state = projection.identity();
        state = projection.apply(state, threadMsg("START", "t1", "REV",
                "comment",
                "side=A|startLine=1|endLine=5|selectedText=x"));
        state = projection.apply(state, threadMsg("RESOLVE", "t1", "HUMAN",
                "", null));

        assertThat(state.threads().get("t1").status()).isEqualTo(ThreadStatus.RESOLVED);
    }

    @Test
    void nonThreadMessage_skipped() {
        ThreadState state = projection.identity();
        state = projection.apply(state, debateMsg("RAISE", "REV", "some point"));

        assertThat(state.threads()).isEmpty();
    }

    @Test
    void malformedStart_noSide_skipped() {
        ThreadState state = projection.identity();
        state = projection.apply(state, threadMsg("START", "t1", "REV",
                "bad", null));

        assertThat(state.threads()).isEmpty();
    }

    @Test
    void reply_unknownThread_skipped() {
        ThreadState state = projection.identity();
        state = projection.apply(state, threadMsg("REPLY", "unknown", "REV",
                "reply to nothing", null));

        assertThat(state.threads()).isEmpty();
    }

    @Test
    void resolve_unknownThread_skipped() {
        ThreadState state = projection.identity();
        state = projection.apply(state, threadMsg("RESOLVE", "unknown", "REV",
                "", null));

        assertThat(state.threads()).isEmpty();
    }

    @Test
    void multipleThreads_independent() {
        ThreadState state = projection.identity();
        state = projection.apply(state, threadMsg("START", "t1", "REV",
                "First thread",
                "side=A|startLine=1|endLine=5|selectedText=first"));
        state = projection.apply(state, threadMsg("START", "t2", "HUMAN",
                "Second thread",
                "side=B|startLine=10|endLine=20|selectedText=second"));

        assertThat(state.threads()).hasSize(2);
        assertThat(state.threads().get("t1").anchor().side()).isEqualTo(DocumentSide.A);
        assertThat(state.threads().get("t2").anchor().side()).isEqualTo(DocumentSide.B);
    }
}
