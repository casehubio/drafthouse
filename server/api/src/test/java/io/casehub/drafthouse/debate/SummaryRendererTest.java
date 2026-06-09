package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SummaryRendererTest {

    private final SummaryRenderer renderer = new SummaryRenderer();

    /** Build a ReviewState with a single open point and one anchored finding. */
    private static ReviewState stateWithPointAndFinding(String pointId, SubTaskFinding finding) {
        var point = new ReviewPoint(pointId,
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(new ThreadEntry(pointId, AgentType.REV, 1, EntryType.RAISE, "Issue.")),
                ReviewStatus.OPEN);
        return new ReviewState(Map.of(pointId, point), List.of(), List.of(),
                Map.of(finding.subTaskId(), finding));
    }

    @Test
    void rendersEmptyStateAsHeader() {
        String output = renderer.render(new ReviewState(Map.of(), List.of(), List.of(), Map.of()));
        assertThat(output).contains("# Review Summary");
        assertThat(output).doesNotContain("##");
    }

    @Test
    void rendersOpenPoint() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, "§3.2"),
                List.of(new ThreadEntry("R1-REV-001", AgentType.REV, 0, EntryType.RAISE, "Both variants appear.")),
                ReviewStatus.OPEN)),
            List.of(), List.of(), Map.of());
        String output = renderer.render(state);
        assertThat(output).contains("🔴");
        assertThat(output).contains("[R1-REV-001]");
        assertThat(output).contains("P1");
        assertThat(output).contains("Both variants appear.");
    }

    @Test
    void rendersAgreedPointWithStrikethrough() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry("R1-REV-001", AgentType.REV, 0, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.IMP, 0, EntryType.AGREE, "Fixed.")),
                ReviewStatus.AGREED)),
            List.of(), List.of(), Map.of());
        String output = renderer.render(state);
        assertThat(output).contains("✅");
        assertThat(output).contains("~~");
    }

    @Test
    void rendersFlagSectionAtBottom() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry(null, AgentType.REV, 0, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.REV, 0, EntryType.FLAG_HUMAN, "Human needed.")),
                ReviewStatus.PENDING_HUMAN)),
            List.of(new FlagEntry(null, 0, AgentType.REV, "Human needed.")),
            List.of(), Map.of());
        String output = renderer.render(state);
        assertThat(output).contains("⚑");
        assertThat(output).contains("Human needed.");
        assertThat(output.indexOf("⚑")).isGreaterThan(output.indexOf("R1-REV-001"));
    }

    @Test
    void emptyMemosProduceNoOutput() {
        // An empty memos list produces no Agent Memos section.
        String output = renderer.render(new ReviewState(Map.of(), List.of(), List.of(), Map.of()));
        assertThat(output).doesNotContain("Agent Memos");
        assertThat(output).doesNotContain("Private thought.");
    }

    @Test
    void renderTimestampIsControlledByClock() {
        Instant fixed = Instant.parse("2026-01-15T10:30:00Z");
        renderer.setClockForTest(() -> fixed);
        String output = renderer.render(new ReviewState(Map.of(), List.of(), List.of(), Map.of()));
        assertThat(output).contains("2026-01-15T10:30:00Z");
    }

    @Test
    void rendersDeclinedPointWithStrikethrough() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P3, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry("R1-REV-001", AgentType.REV, 0, EntryType.RAISE, "Off topic?"),
                    new ThreadEntry(null, AgentType.IMP, 0, EntryType.DECLINED, "Out of scope.")),
                ReviewStatus.DECLINED)),
            List.of(), List.of(), Map.of());
        String output = renderer.render(state);
        assertThat(output).contains("🚫");
        assertThat(output).contains("~~");
        assertThat(output).contains("declined");
    }

    @Test
    void rendersDisputedPoint_withLightningMarker_noStrikethrough() {
        var state = new ReviewState(
            Map.of("R1-IMP-001", new ReviewPoint("R1-IMP-001",
                new PointClassification(Priority.P2, Scope.ISOLATED, null),
                List.of(new ThreadEntry("R1-IMP-001", AgentType.IMP, 1, EntryType.RAISE, "Counter point.")),
                ReviewStatus.DISPUTED)),
            List.of(), List.of(), Map.of());
        String output = renderer.render(state);
        assertThat(output).contains("⚡");
        assertThat(output).doesNotContain("~~");
    }

    // ── renderFinding — PENDING / ERROR / COMPLETE ────────────────────────────

    @Test
    void renderFinding_pending_showsPendingIndicator() {
        var finding = new SubTaskFinding("s1", SubTaskType.VERIFY, "REV", "pt-1", null, null, SubTaskStatus.PENDING);
        var state = stateWithPointAndFinding("pt-1", finding);
        String out = renderer.render(state);
        assertThat(out).contains("⏳");
        assertThat(out).contains("VERIFY");
        assertThat(out).contains("pending");
    }

    @Test
    void renderFinding_error_showsErrorReason() {
        var finding = new SubTaskFinding("s1", SubTaskType.VERIFY, "REV", "pt-1",
                null, "analysis timed out", SubTaskStatus.ERROR);
        var state = stateWithPointAndFinding("pt-1", finding);
        String out = renderer.render(state);
        assertThat(out).contains("✗");
        assertThat(out).contains("VERIFY");
        assertThat(out).contains("analysis timed out");
    }

    @Test
    void renderFinding_complete_showsFindingTextAndProvenanceMarker() {
        var finding = new SubTaskFinding("s1", SubTaskType.ARBITRATE, "IMP", "pt-1",
                "The arbitration result.", null, SubTaskStatus.COMPLETE);
        var state = stateWithPointAndFinding("pt-1", finding);
        String out = renderer.render(state);
        assertThat(out).contains("⊕");
        assertThat(out).contains("ARBITRATE");
        assertThat(out).contains("The arbitration result.");
    }

    @Test
    void renderFinding_complete_nullFinding_rendersNoFindingSentinel() {
        // Guard: null finding field renders "(no finding)", not the literal string "null"
        var finding = new SubTaskFinding("s1", SubTaskType.VERIFY, "REV", "pt-1",
                null, null, SubTaskStatus.COMPLETE);
        var state = stateWithPointAndFinding("pt-1", finding);
        String out = renderer.render(state);
        assertThat(out).contains("(no finding)");
        assertThat(out).doesNotContain("null");
    }

    // ── point-anchored vs standalone findings ─────────────────────────────────

    @Test
    void pointAnchoredFinding_appearsInsidePointSection_notStandalone() {
        // A finding with a non-null pointId must appear inside its point block,
        // and NOT appear in the standalone "Sub-task findings" section.
        var finding = new SubTaskFinding("s1", SubTaskType.ARBITRATE, "REV", "pt-1",
                "Anchored finding.", null, SubTaskStatus.COMPLETE);
        var state = stateWithPointAndFinding("pt-1", finding);
        String out = renderer.render(state);
        assertThat(out).contains("Anchored finding.");
        assertThat(out).doesNotContain("Sub-task findings");
    }

    @Test
    void standaloneFinding_nullPointId_appearsInSubtaskSection() {
        // A finding with null pointId (NEUTRAL_SUMMARY, CUSTOM) belongs in the standalone section.
        var finding = new SubTaskFinding("s1", SubTaskType.NEUTRAL_SUMMARY, "REV", null,
                "Overall summary.", null, SubTaskStatus.COMPLETE);
        var state = new ReviewState(Map.of(), List.of(), List.of(),
                Map.of("s1", finding));
        String out = renderer.render(state);
        assertThat(out).contains("Sub-task findings");
        assertThat(out).contains("Overall summary.");
    }

    // ── memos section ─────────────────────────────────────────────────────────

    @Test
    void memo_appearsInAgentMemosSection_withRoleAndRound() {
        var memo = new RoundMemo("REV", 3, "Working hypothesis about the architecture.");
        var state = new ReviewState(Map.of(), List.of(), List.of(memo), Map.of());
        String out = renderer.render(state);
        assertThat(out).contains("Agent Memos");
        assertThat(out).contains("REV");
        assertThat(out).contains("Round 3");
        assertThat(out).contains("Working hypothesis about the architecture.");
    }

    @Test
    void rendersCounterEntryType_withCounterLabel() {
        var state = new ReviewState(
            Map.of("R1-REV-001", new ReviewPoint("R1-REV-001",
                new PointClassification(Priority.P1, Scope.ISOLATED, null),
                List.of(
                    new ThreadEntry("R1-REV-001", AgentType.REV, 1, EntryType.RAISE, "Issue."),
                    new ThreadEntry(null, AgentType.IMP, 2, EntryType.COUNTER, "My counter.")),
                ReviewStatus.ACTIVE)),
            List.of(), List.of(), Map.of());
        String output = renderer.render(state);
        assertThat(output).contains("counter");
        assertThat(output).contains("My counter.");
    }
}
