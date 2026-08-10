package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the full debate lifecycle with real Qhorus on H2.
 *
 * No Awaitility: DebateChannelBackend.post() is a no-op, so ChannelGateway.fanOut()
 * triggers no virtual thread work. All debate operations are synchronous end-to-end.
 * Contrast: ReviewSessionLifecycleTest requires Awaitility because ReviewerChannelBackend
 * executes on virtual threads via fanOut().
 */
@QuarkusTest
class DebateSessionLifecycleTest {

    private static final Pattern DEBATE_ID_PATTERN = Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");
    private static final Pattern POINT_ID_PATTERN  = Pattern.compile("\"pointId\":\"([^\"]+)\"");

    @Inject DebateMcpTools tools;

    private String activeDebateSessionId;

    @BeforeEach
    void setUp() {
        activeDebateSessionId = null;
    }

    @AfterEach
    void tearDown() {
        if (activeDebateSessionId != null) {
            tools.endDebate(activeDebateSessionId, false);
        }
    }

    @Test
    void raiseAndAgree_summaryShowsAgreedPoint() {
        String startResult = tools.startDebate("test-spec.md", null, null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        assertThat(sessionId).isNotBlank();
        activeDebateSessionId = sessionId;

        String raiseResult = tools.raisePoint(sessionId, "REV", 1,
                "The API contract is ambiguous.", "P1", "ISOLATED", "§3.2");
        String pointId = extractGroup(POINT_ID_PATTERN, raiseResult);
        assertThat(pointId).isNotBlank();

        String respondResult = tools.respondTo(sessionId, "IMP", 2, pointId, "agree",
                "Correct — will clarify the contract.");
        assertThat(respondResult).contains("dispatched");

        String summary = tools.getDebateSummary(sessionId);
        assertThat(summary).contains("✅");
        assertThat(summary).contains("~~");          // AGREED is terminal — strikethrough
        assertThat(summary).contains("ambiguous");
    }

    @Test
    void supervisorRaise_foldsCorrectly_appearsInSummary() {
        // Exercises the projection fold path for a new AgentType (SUPERVISOR) end-to-end.
        String startResult = tools.startDebate("test-spec.md", null, null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        String raiseResult = tools.raisePoint(sessionId, "SUPERVISOR", 1,
                "Debate quality seems low.", "P2", "SYSTEMIC", null);
        assertThat(raiseResult).contains("pointId");

        String summary = tools.getDebateSummary(sessionId);
        assertThat(summary).contains("Debate quality seems low.");
        assertThat(summary).contains("🔴");  // OPEN status
    }

    @Test
    void raiseAndDispute_summaryShowsDisputedPoint_noStrikethrough() {
        String startResult = tools.startDebate("test-spec.md", null, null);
        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        String raiseResult = tools.raisePoint(sessionId, "REV", 1,
                "Error handling is missing.", "P2", "SYSTEMIC", null);
        String pointId = extractGroup(POINT_ID_PATTERN, raiseResult);

        tools.respondTo(sessionId, "IMP", 2, pointId, "dispute",
                "Retry is caller responsibility per contract.");

        String summary = tools.getDebateSummary(sessionId);
        assertThat(summary).contains("⚡");
        assertThat(summary).doesNotContain("~~");    // DISPUTED is non-terminal — no strikethrough
        assertThat(summary).contains("dispute");
    }

    @Inject
    DebateSessionRegistry registry;

    @Test
    void startDebate_autonomous_setsOrchestratorOnSession() {
        String startResult = tools.startDebate("test-spec.md", null, true);
        String sessionId   = extractGroup(DEBATE_ID_PATTERN, startResult);
        assertThat(sessionId).isNotBlank();
        activeDebateSessionId = sessionId;

        UUID          channelId = UUID.fromString(sessionId);
        DebateSession session   = registry.find(channelId).orElse(null);
        assertThat(session).isNotNull();
        assertThat(session.isAutonomous()).isTrue();
        assertThat(session.orchestrator()).isNotNull();
    }

    @Test
    void startDebate_nonAutonomous_noOrchestrator() {
        String startResult = tools.startDebate("test-spec.md", null, null);
        String sessionId   = extractGroup(DEBATE_ID_PATTERN, startResult);
        assertThat(sessionId).isNotBlank();
        activeDebateSessionId = sessionId;

        UUID          channelId = UUID.fromString(sessionId);
        DebateSession session   = registry.find(channelId).orElse(null);
        assertThat(session).isNotNull();
        assertThat(session.isAutonomous()).isFalse();
        assertThat(session.orchestrator()).isNull();
    }


    // ── helpers ───────────────────────────────────────────────────────────────


    @Test
    void endDebate_terminatesRunningOrchestrator() {
        String startResult = tools.startDebate("test-spec.md", null, true);
        assertThat(startResult).contains("autonomous\":true");

        String sessionId = extractGroup(DEBATE_ID_PATTERN, startResult);
        activeDebateSessionId = sessionId;

        UUID          channelId = UUID.fromString(sessionId);
        DebateSession session   = registry.find(channelId).orElseThrow();
        assertThat(session.orchestrator()).isNotNull();

        String endResult = tools.endDebate(sessionId, false);
        assertThat(endResult).contains("\"status\":\"ended\"");
        assertThat(session.orchestrator()).isNull();
        activeDebateSessionId = null;  // already ended
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }
}
