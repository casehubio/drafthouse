package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class BrainstormServiceTest {

    @Inject BrainstormService service;
    @Inject BrainstormSessionRegistry registry;

    private String activeSessionId;

    @AfterEach
    void tearDown() {
        if (activeSessionId != null) {
            registry.remove(activeSessionId);
        }
    }

    @Test
    void startSession_createsActiveSession() {
        activeSessionId = service.startSession();
        assertThat(activeSessionId).startsWith("bs-");
        assertThat(registry.find(activeSessionId)).isPresent();
    }

    @Test
    void presentOptions_addsToSession() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "Title A", "Desc A", "Trade A"),
                new BrainstormService.OptionInput("B", "Title B", "Desc B", "Trade B")));
        assertThat(registry.find(activeSessionId).get().options()).hasSize(2);
    }

    @Test
    void updateOption_enrichesAndExplores() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.updateOption(activeSessionId, "A", "Updated desc", "Updated trades");
        var option = registry.find(activeSessionId).get().findOption("A").get();
        assertThat(option.description()).isEqualTo("Updated desc");
        assertThat(option.status()).isEqualTo(BrainstormOption.Status.EXPLORED);
    }

    @Test
    void markEliminated_setsTerminalStatus() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.markEliminated(activeSessionId, "A");
        assertThat(registry.find(activeSessionId).get().findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.ELIMINATED);
    }

    @Test
    void markEliminated_onTerminalStatus_isIdempotent() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.markEliminated(activeSessionId, "A");
        service.markEliminated(activeSessionId, "A");
        assertThat(registry.find(activeSessionId).get().findOption("A").get().status())
                .isEqualTo(BrainstormOption.Status.ELIMINATED);
    }

    @Test
    void recommend_eliminatedOption_throws() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.markEliminated(activeSessionId, "A");
        assertThatThrownBy(() -> service.setRecommendation(activeSessionId, "A"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setRecommendation_singleRecommendation() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A"),
                new BrainstormService.OptionInput("B", "B", "B", "B")));
        service.setRecommendation(activeSessionId, "A");
        service.setRecommendation(activeSessionId, "B");
        var session = registry.find(activeSessionId).get();
        assertThat(session.findOption("A").get().status()).isEqualTo(BrainstormOption.Status.EXPLORED);
        assertThat(session.findOption("B").get().status()).isEqualTo(BrainstormOption.Status.RECOMMENDED);
    }

    @Test
    void markSelected_convergesSession() {
        activeSessionId = service.startSession();
        service.presentOptions(activeSessionId, List.of(
                new BrainstormService.OptionInput("A", "A", "A", "A")));
        service.markSelected(activeSessionId, "A");
        assertThat(registry.find(activeSessionId).get().state())
                .isEqualTo(BrainstormSession.State.CONVERGED);
        activeSessionId = null;
    }

    @Test
    void endSession_removesFromRegistry() {
        activeSessionId = service.startSession();
        service.endSession(activeSessionId);
        assertThat(registry.find(activeSessionId)).isEmpty();
        activeSessionId = null;
    }

    @Test
    void unknownSession_throws() {
        assertThatThrownBy(() -> service.markEliminated("nonexistent", "A"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
