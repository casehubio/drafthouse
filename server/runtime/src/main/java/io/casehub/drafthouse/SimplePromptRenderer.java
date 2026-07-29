package io.casehub.drafthouse;

import io.casehub.eidos.api.AgentCapability;
import io.casehub.eidos.api.AgentDescriptor;
import io.casehub.eidos.api.AgentDisposition;
import io.casehub.eidos.api.AgentPromptContext;
import io.casehub.eidos.api.DispositionAxis;
import io.casehub.eidos.api.Resource;
import io.casehub.eidos.api.SystemPromptRenderer;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * @DefaultBean mock SystemPromptRenderer — renders AgentDescriptor to MARKDOWN
 * without LLM enrichment or vocabulary resolution. Returns null hashes and enriched=false.
 */
@ApplicationScoped
@DefaultBean
public class SimplePromptRenderer implements SystemPromptRenderer {

    @Override
    public RenderedPrompt render(AgentDescriptor descriptor, AgentPromptContext context) {
        StringBuilder content = new StringBuilder();

        content.append("# ").append(descriptor.name()).append("\n");
        content.append("**Agent ID:** ").append(descriptor.agentId()).append("\n\n");

        content.append("## Role\n");
        content.append(descriptor.slot()).append("\n\n");

        if (!descriptor.capabilities().isEmpty()) {
            content.append("## Capabilities\n");
            for (AgentCapability cap : descriptor.capabilities()) {
                content.append("- **").append(cap.name()).append("**");
                if (cap.inputTypes() != null && !cap.inputTypes().isEmpty()) {
                    content.append(": accepts ");
                    content.append(String.join(", ", cap.inputTypes()));
                    if (cap.outputTypes() != null && !cap.outputTypes().isEmpty()) {
                        content.append(" → ");
                        content.append(String.join(", ", cap.outputTypes()));
                    }
                }
                content.append("\n");
            }
            content.append("\n");
        }

        AgentDisposition disp = descriptor.disposition();
        if (hasAnyDispositionAxis(disp)) {
            content.append("## How You Operate\n");
            appendAxis(content, disp, DispositionAxis.SOCIAL_ORIENTATION, "Social orientation");
            appendAxis(content, disp, DispositionAxis.RULE_FOLLOWING, "Rule following");
            appendAxis(content, disp, DispositionAxis.RISK_APPETITE, "Risk appetite");
            appendAxis(content, disp, DispositionAxis.AUTONOMY, "Autonomy");
            appendAxis(content, disp, DispositionAxis.CONFLICT_MODE, "Conflict mode");
            content.append("- Can delegate: ").append(disp.delegation() ? "yes" : "no").append("\n");
            content.append("\n");
        }

        if (descriptor.briefing() != null) {
            content.append("## Operating Principles\n");
            content.append(descriptor.briefing()).append("\n\n");
        }

        if (descriptor.jurisdiction() != null || descriptor.dataHandlingPolicy() != null) {
            content.append("## Data Handling\n");
            if (descriptor.jurisdiction() != null) {
                content.append("Jurisdiction: ").append(descriptor.jurisdiction()).append("\n");
            }
            if (descriptor.dataHandlingPolicy() != null) {
                content.append("Policy: ").append(descriptor.dataHandlingPolicy()).append("\n");
            }
            content.append("\n");
        }

        context.goal().ifPresent(goal -> {
            content.append("## Current Goal\n");
            content.append(goal.description()).append("\n");
            for (String subGoal : goal.subGoals()) {
                content.append("- ").append(subGoal).append("\n");
            }
            content.append("\n");
        });

        if (!context.resources().isEmpty()) {
            content.append("## Resources\n");
            for (Resource res : context.resources()) {
                content.append("- **").append(res.label() != null ? res.label() : res.uri()).append("**: ");
                content.append(res.uri());
                if (res.type() != null) {content.append(" (").append(res.type()).append(")");}
                content.append("\n");
            }
            content.append("\n");
        }

        if (context.situationalContext() != null) {
            content.append("## Context\n");
            content.append(context.situationalContext()).append("\n");
        }

        return new RenderedPrompt(
                content.toString(),
                RenderFormat.MARKDOWN,
                null,
                null,
                false
        );}

    private boolean hasAnyDispositionAxis(AgentDisposition disp) {
        return !disp.get(DispositionAxis.SOCIAL_ORIENTATION).isEmpty()
               || !disp.get(DispositionAxis.RULE_FOLLOWING).isEmpty()
               || !disp.get(DispositionAxis.RISK_APPETITE).isEmpty()
               || !disp.get(DispositionAxis.AUTONOMY).isEmpty()
               || !disp.get(DispositionAxis.CONFLICT_MODE).isEmpty();
    }

    private void appendAxis(StringBuilder content, AgentDisposition disp,
                            DispositionAxis axis, String label) {
        String term = disp.primaryTerm(axis);
        if (term != null) {
            content.append("- ").append(label).append(": ").append(term).append("\n");
        }
    }
}
