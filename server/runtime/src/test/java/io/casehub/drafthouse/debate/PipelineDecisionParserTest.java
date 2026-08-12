package io.casehub.drafthouse.debate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PipelineDecisionParserTest {

    private static final String DECISIONS_MD = """
            ## D1: Architecture choice

            **Choice:** Thin registry
            **Alternatives:**
            - Heavy orchestrator — duplicates logic
            - Middleware — stateless, no checkpoint tracking
            **Rationale:** Preserves separation of concerns
            **Trade-offs:** Extra MCP calls needed
            **Exploration:** quick
            **Status:** captured

            ## D2: Event model

            **Choice:** Extend ProgressLogParser
            **Alternatives:**
            - JSONL bypass — breaks single-parser contract
            **Rationale:** Incremental extension of existing patterns
            **Trade-offs:** More parser types to maintain
            **Exploration:** deep-analysis
            **Depends on:** D1 (Architecture choice)
            **Status:** confirmed
            """;

    @Test
    void parse_two_decisions() {
        var decisions = PipelineDecisionParser.parse(DECISIONS_MD);
        assertEquals(2, decisions.size());
    }

    @Test
    void parse_first_decision_fields() {
        var d = PipelineDecisionParser.parse(DECISIONS_MD).get(0);
        assertEquals("D1", d.id());
        assertEquals("Architecture choice", d.title());
        assertEquals("Thin registry", d.choice());
        assertEquals(2, d.alternatives().size());
        assertEquals("Heavy orchestrator — duplicates logic", d.alternatives().get(0));
        assertEquals("Preserves separation of concerns", d.rationale());
        assertEquals("Extra MCP calls needed", d.tradeoffs());
        assertEquals("quick", d.explorationDepth());
        assertEquals("captured", d.status());
        assertNull(d.dependsOn());
    }

    @Test
    void parse_depends_on() {
        var d = PipelineDecisionParser.parse(DECISIONS_MD).get(1);
        assertEquals("D2", d.id());
        assertEquals("D1 (Architecture choice)", d.dependsOn());
        assertEquals("confirmed", d.status());
    }

    @Test
    void parse_empty_input() {
        assertTrue(PipelineDecisionParser.parse("").isEmpty());
    }

    @Test
    void parse_null_input() {
        assertTrue(PipelineDecisionParser.parse(null).isEmpty());
    }
}
