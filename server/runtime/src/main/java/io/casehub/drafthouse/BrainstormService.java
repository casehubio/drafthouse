package io.casehub.drafthouse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class BrainstormService {

    private static final Logger LOG = Logger.getLogger(BrainstormService.class.getName());

    public record OptionInput(String id, String title, String description, String tradeoffs) {}

    @Inject BrainstormSessionRegistry registry;
    @Inject WebSocketEventBus eventBus;

    public String startSession() {
        String sessionId = "bs-" + UUID.randomUUID();
        BrainstormSession session = new BrainstormSession(sessionId);
        registry.put(session);
        eventBus.broadcast("brainstorm-session-created", Map.of("sessionId", sessionId));
        return sessionId;
    }

    public void presentOptions(String sessionId, List<OptionInput> inputs) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            for (OptionInput input : inputs) {
                session.addOption(new BrainstormOption(
                        input.id(), input.title(),
                        input.description() != null ? input.description() : "",
                        input.tradeoffs() != null ? input.tradeoffs() : ""));
            }
            session.touch();
        }
        pushOptionsEvent(session);
    }

    public void updateOption(String sessionId, String optionId,
                             String description, String tradeoffs) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            BrainstormOption option = resolveOption(session, optionId);
            option.setDescription(description);
            option.setTradeoffs(tradeoffs);
            option.transitionTo(BrainstormOption.Status.EXPLORED);
            session.touch();
        }
        pushOptionsEvent(session);
    }

    public void setRecommendation(String sessionId, String optionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            session.setRecommendation(optionId);
        }
        pushOptionsEvent(session);
    }

    public void markEliminated(String sessionId, String optionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            BrainstormOption option = resolveOption(session, optionId);
            option.transitionTo(BrainstormOption.Status.ELIMINATED);
            session.touch();
        }
        pushOptionsEvent(session);
    }

    public void markSelected(String sessionId, String optionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            session.markSelected(optionId);
        }
        pushConvergedEvent(session);
    }

    public void endSession(String sessionId) {
        BrainstormSession session = resolve(sessionId);
        synchronized (session) {
            if (session.state() == BrainstormSession.State.ACTIVE) {
                session.abandon();
            }
        }
        eventBus.pushBrainstormEvent(sessionId, "brainstorm-ended",
                Map.of("sessionId", sessionId, "state", session.state().name()));
        registry.remove(sessionId);
    }

    BrainstormSession resolve(String sessionId) {
        return registry.find(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Brainstorm session not found: " + sessionId));
    }

    private BrainstormOption resolveOption(BrainstormSession session, String optionId) {
        return session.findOption(optionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown option: " + optionId));
    }

    void pushOptionsEvent(BrainstormSession session) {
        var optionMaps = buildOptionMaps(session);
        eventBus.pushBrainstormEvent(session.sessionId(), "brainstorm-options",
                Map.of("sessionId", session.sessionId(),
                       "options", optionMaps,
                       "state", session.state().name()));
    }

    private void pushConvergedEvent(BrainstormSession session) {
        var optionMaps = buildOptionMaps(session);
        eventBus.pushBrainstormEvent(session.sessionId(), "brainstorm-converged",
                Map.of("sessionId", session.sessionId(),
                       "options", optionMaps,
                       "state", session.state().name(),
                       "selectedOptionId", session.options().stream()
                               .filter(o -> o.status() == BrainstormOption.Status.SELECTED)
                               .map(BrainstormOption::id)
                               .findFirst().orElse("")));
    }

    private List<Map<String, String>> buildOptionMaps(BrainstormSession session) {
        synchronized (session) {
            return session.options().stream().map(o -> Map.of(
                    "id", o.id(),
                    "title", o.title(),
                    "description", o.description(),
                    "tradeoffs", o.tradeoffs(),
                    "status", o.status().name()
            )).toList();
        }
    }
}
