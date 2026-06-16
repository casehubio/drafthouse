package io.casehub.drafthouse.e2e;

import com.microsoft.playwright.Page;
import io.casehub.drafthouse.DebateMcpTools;

import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DebateE2EFixtures {

    private static final Pattern SESSION_ID_PATTERN =
            Pattern.compile("\"debateSessionId\":\"([^\"]+)\"");
    private static final Pattern NEW_SESSION_ID_PATTERN =
            Pattern.compile("\"newDebateSessionId\":\"([^\"]+)\"");
    private static final Pattern POINT_ID_PATTERN =
            Pattern.compile("\"pointId\":\"([^\"]+)\"");

    private DebateE2EFixtures() {}

    public static String startDebateSession(DebateMcpTools tools) {
        return startDebateSession(tools, "test-spec.md");
    }

    public static String startDebateSession(DebateMcpTools tools, String specPath) {
        String result = tools.startDebate(specPath);
        String sessionId = extractSessionId(result);
        if (sessionId.isBlank()) {
            throw new AssertionError("startDebate failed: " + result);
        }
        return sessionId;
    }

    public static void loadWithDebate(Page page, URL base, String sessionId) {
        String a = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-a.md"), StandardCharsets.UTF_8);
        String b = URLEncoder.encode(PlaywrightFixtures.fixturePath("diff-b.md"), StandardCharsets.UTF_8);
        page.navigate(base + "?a=" + a + "&b=" + b + "&debate=" + sessionId);
        PlaywrightFixtures.waitForRender(page);
    }

    public static void waitForDebateEntries(Page page, int count) {
        page.locator("drafthouse-debate .entry").nth(count - 1).waitFor();
    }

    public static void waitForTrackerPoints(Page page, int count) {
        page.locator("drafthouse-review-tracker .point-item").nth(count - 1).waitFor();
    }

    public static void listenForPointSelected(Page page) {
        page.evaluate("() => {"
                + "window.__pointSelectedDetail = null;"
                + "document.addEventListener('point-selected',"
                + "  e => window.__pointSelectedDetail = e.detail);"
                + "}");
    }

    public static Object getPointSelectedDetail(Page page) {
        return page.evaluate("() => window.__pointSelectedDetail");
    }

    public static String extractSessionId(String mcpResult) {
        return extractGroup(SESSION_ID_PATTERN, mcpResult);
    }

    public static String extractNewSessionId(String mcpResult) {
        return extractGroup(NEW_SESSION_ID_PATTERN, mcpResult);
    }

    public static String extractPointId(String mcpResult) {
        return extractGroup(POINT_ID_PATTERN, mcpResult);
    }

    private static String extractGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : "";
    }
}
