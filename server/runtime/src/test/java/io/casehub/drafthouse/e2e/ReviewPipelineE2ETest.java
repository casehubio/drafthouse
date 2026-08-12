package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import io.quarkiverse.playwright.InjectPlaywright;
import io.quarkiverse.playwright.WithPlaywright;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.fixturePath;
import static io.casehub.drafthouse.e2e.PlaywrightFixtures.loadFilePair;

@QuarkusTest
@WithPlaywright
class ReviewPipelineE2ETest {

    @InjectPlaywright
    BrowserContext context;

    @TestHTTPResource("/")
    URL index;

    private Page page;

    @BeforeEach
    void openPage() {
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        if (page != null) page.close();
    }

    @Test
    void pipeline_button_visible_in_topbar() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        assertThat(page.locator("#btn-pipeline")).isVisible();
    }

    @Test
    void pipeline_panel_hidden_by_default() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        var btn = page.locator("#btn-pipeline");
        assertThat(btn).isVisible();
        assertThat(btn).not().hasClass("active");
    }

    @Test
    void pipeline_toggle_shows_and_hides_panel() {
        loadFilePair(page, index, fixturePath("diff-a.md"), fixturePath("diff-b.md"));
        var btn = page.locator("#btn-pipeline");
        btn.click();
        assertThat(btn).hasClass("active");
        var emptyMsg = page.locator("review-pipeline .empty");
        assertThat(emptyMsg).isVisible();
        assertThat(emptyMsg).containsText("No active review pipeline");
        btn.click();
        assertThat(btn).not().hasClass("active");
    }
}
