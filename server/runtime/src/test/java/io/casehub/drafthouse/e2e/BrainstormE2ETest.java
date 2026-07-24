package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import io.casehub.drafthouse.BrainstormService;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

@QuarkusTest
@WithPlaywright
class BrainstormE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    @Inject BrainstormService service;

    private Page page;
    private String sessionId;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (sessionId != null) {
            try { service.endSession(sessionId); } catch (Exception ignored) {}
            sessionId = null;
        }
        if (page != null) page.close();
    }

    private void navigateBrainstorm() {
        page.navigate(index + "?mode=brainstorm");
        page.waitForTimeout(500);
    }

    private void startSessionAndPresentOptions() {
        sessionId = service.startSession();
        service.presentOptions(sessionId, List.of(
                new BrainstormService.OptionInput("opt-a", "Option Alpha", "First approach", "Simple but limited"),
                new BrainstormService.OptionInput("opt-b", "Option Beta", "Second approach", "Complex but flexible"),
                new BrainstormService.OptionInput("opt-c", "Option Gamma", "Third approach", "")
        ));
    }

    private Locator cards() {
        return page.locator("brainstorm-options .card");
    }

    private Locator cardByTitle(String title) {
        return page.locator("brainstorm-options .card").filter(
                new Locator.FilterOptions().setHasText(title));
    }

    @Test
    void optionCardsRenderWithTitlesAndDescriptions() {
        navigateBrainstorm();
        startSessionAndPresentOptions();
        cards().first().waitFor();

        assertThat(cards()).hasCount(3);
        assertThat(cardByTitle("Option Alpha")).isVisible();
        assertThat(cardByTitle("Option Beta")).isVisible();
        assertThat(cardByTitle("Option Gamma")).isVisible();

        assertThat(cardByTitle("Option Alpha")).containsText("First approach");
        assertThat(cardByTitle("Option Alpha")).containsText("Simple but limited");
    }

    @Test
    void eliminateButtonDimsCard() {
        navigateBrainstorm();
        startSessionAndPresentOptions();
        cards().first().waitFor();

        cardByTitle("Option Beta").locator("button", new Locator.LocatorOptions()
                .setHasText("Eliminate")).click();

        Locator eliminated = cardByTitle("Option Beta");
        page.locator("brainstorm-options .card.eliminated").waitFor();
        assertThat(eliminated).hasClass(java.util.regex.Pattern.compile(".*eliminated.*"));
        assertThat(eliminated.locator(".status-badge")).containsText("ELIMINATED");
    }

    @Test
    void recommendButtonHighlightsCard() {
        navigateBrainstorm();
        startSessionAndPresentOptions();
        cards().first().waitFor();

        cardByTitle("Option Alpha").locator("button", new Locator.LocatorOptions()
                .setHasText("Recommend")).click();

        page.locator("brainstorm-options .card.recommended").waitFor();
        Locator recommended = cardByTitle("Option Alpha");
        assertThat(recommended).hasClass(java.util.regex.Pattern.compile(".*recommended.*"));
        assertThat(recommended.locator(".status-badge")).containsText("RECOMMENDED");
    }

    @Test
    void selectButtonShowsConvergenceBanner() {
        navigateBrainstorm();
        startSessionAndPresentOptions();
        cards().first().waitFor();

        cardByTitle("Option Gamma").locator("button", new Locator.LocatorOptions()
                .setHasText("Select")).click();

        Locator banner = page.locator("brainstorm-options .banner.converged");
        banner.waitFor();
        assertThat(banner).isVisible();
        assertThat(banner).containsText("Converged");
    }

    @Test
    void eliminatedCardHasNoActionButtons() {
        navigateBrainstorm();
        startSessionAndPresentOptions();
        cards().first().waitFor();

        cardByTitle("Option Beta").locator("button", new Locator.LocatorOptions()
                .setHasText("Eliminate")).click();
        page.locator("brainstorm-options .card.eliminated").waitFor();

        assertThat(cardByTitle("Option Beta").locator(".actions")).hasCount(0);
    }

    @Test
    void summaryUpdatesWithCounts() {
        navigateBrainstorm();
        startSessionAndPresentOptions();
        cards().first().waitFor();

        Locator summary = page.locator("brainstorm-options .summary");
        assertThat(summary).containsText("3 options");

        cardByTitle("Option Beta").locator("button", new Locator.LocatorOptions()
                .setHasText("Eliminate")).click();
        page.locator("brainstorm-options .card.eliminated").waitFor();

        assertThat(summary).containsText("1 eliminated");
    }
}
