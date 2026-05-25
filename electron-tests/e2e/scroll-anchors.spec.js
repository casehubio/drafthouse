// electron-tests/e2e/scroll-anchors.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('scroll anchors', () => {
  let app, window, jsErrors;

  test.beforeAll(async () => {
    ({ app, window, jsErrors } = await launchApp(
      process.env.TEST_FILE_A,
      process.env.TEST_FILE_B
    ));
  });

  test.afterAll(async () => {
    if (jsErrors) expect(jsErrors).toHaveLength(0);
    if (app) await app.close();
  });

  // ── normHead ─────────────────────────────────────────────────────────

  test('normHead normalises punctuation, case, and caps at 6 words', async () => {
    const r1 = await window.evaluate(() => normHead('Hello, World!'));
    expect(r1).toBe('hello world');

    const r2 = await window.evaluate(
      () => normHead('One Two Three Four Five Six Seven Eight')
    );
    expect(r2).toBe('one two three four five six');

    const r3 = await window.evaluate(() => normHead('  Leading   Spaces  '));
    expect(r3).toBe('leading spaces');
  });

  // ── buildScrollAnchors ───────────────────────────────────────────────

  test('buildScrollAnchors with no shared headings returns boundary-only anchors', async () => {
    const filler = Array.from({ length: 100 },
      (_, i) => `Paragraph ${i + 1} of filler content for scroll testing.`
    ).join('\n\n');
    const contentA = `# Title A\n\n${filler}`;
    const contentB = `# Title B\n\n${filler}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());
    expect(anchors.length).toBe(2);
    expect(anchors[0]).toEqual({ a: 0, b: 0 });
    expect(anchors[1].a).toBeGreaterThan(0);
    expect(anchors[1].b).toBeGreaterThan(0);
  });

  test('buildScrollAnchors with shared headings returns sorted interior anchors', async () => {
    const shortFill = Array.from({ length: 30 },
      (_, i) => `Short filler paragraph ${i + 1}.`
    ).join('\n\n');
    const longFill = Array.from({ length: 60 },
      (_, i) => `Long filler paragraph ${i + 1}.`
    ).join('\n\n');

    const contentA =
      `# Doc A\n\n${shortFill}\n\n## Shared Section\n\n${shortFill}\n\n## Second Heading\n\n${shortFill}`;
    const contentB =
      `# Doc B\n\n${longFill}\n\n## Shared Section\n\n${shortFill}\n\n## Second Heading\n\n${shortFill}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());

    expect(anchors.length).toBeGreaterThan(2);

    for (let i = 1; i < anchors.length; i++) {
      expect(anchors[i].a).toBeGreaterThan(anchors[i - 1].a);
    }

    expect(anchors[0]).toEqual({ a: 0, b: 0 });
    expect(anchors[anchors.length - 1].a).toBeGreaterThan(0);
    expect(anchors[anchors.length - 1].b).toBeGreaterThan(0);
  });

  // ── interp edge cases ────────────────────────────────────────────────

  test('interp with no anchors returns pos unchanged', async () => {
    const result = await window.evaluate(() => {
      scrollAnchors = [];
      return interp(42, 'a', 'b');
    });
    expect(result).toBe(42);
  });

  test('interp with degenerate segment (hi[fk] === lo[fk]) returns lo[tk]', async () => {
    const result = await window.evaluate(() => {
      scrollAnchors = [{ a: 0, b: 0 }, { a: 0, b: 100 }];
      return interp(0, 'a', 'b');
    });
    expect(result).toBe(0);
  });

  // ── prefix / duplicate matching ─────────────────────────────────────

  test('prefix fallback does not match short headings', async () => {
    const filler = Array.from({ length: 40 },
      (_, i) => `Paragraph ${i + 1}.`
    ).join('\n\n');

    // "Setup" (5 chars) should NOT prefix-match "Setup Instructions" — too short
    const contentA = `## Setup\n\n${filler}\n\n## Details\n\n${filler}`;
    const contentB = `## Setup Instructions\n\n${filler}\n\n## Details\n\n${filler}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());

    // "Details" matches exactly. "Setup" vs "Setup Instructions" should NOT match
    // via prefix (too short). So: boundary + Details + boundary = 3 anchors.
    const interiorCount = anchors.length - 2;
    expect(interiorCount).toBe(1);
  });

  test('each B-heading is consumed at most once', async () => {
    const filler = Array.from({ length: 30 },
      (_, i) => `Paragraph ${i + 1}.`
    ).join('\n\n');

    // A has two headings that could both match the same B heading
    const contentA = `## Overview\n\n${filler}\n\n## Overview\n\n${filler}`;
    const contentB = `## Overview\n\n${filler}\n\n## Other\n\n${filler}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());

    // Only one A-heading should match B's "Overview" — the second A "Overview"
    // should not re-use the same B-heading.
    const bPositions = anchors.slice(1, -1).map(an => an.b);
    const uniqueB = new Set(bPositions);
    expect(uniqueB.size).toBe(bPositions.length);
  });

  // ── Behavioural sync ─────────────────────────────────────────────────

  test('scroll sync uses heading anchors, diverging from pure percentage', async () => {
    const longFill = Array.from({ length: 80 },
      (_, i) => `Filler paragraph ${i + 1} for divergence test.`
    ).join('\n\n');
    const shortFill = Array.from({ length: 10 },
      (_, i) => `Short tail ${i + 1}.`
    ).join('\n\n');

    // A: heading near top;  B: heading near bottom (~75%+ of scroll)
    const contentA = `## Anchor\n\n${longFill}`;
    const contentB = `${longFill}\n\n## Anchor\n\n${shortFill}`;

    await window.evaluate(([a, b]) => {
      renderMarkdown('a', a);
      renderMarkdown('b', b);
    }, [contentA, contentB]);

    const anchors = await window.evaluate(() => getScrollAnchors());
    expect(anchors.length).toBeGreaterThan(2);

    const { scrollB, maxB } = await window.evaluate(() => {
      const bodyA = document.getElementById('body-a');
      const headingA = document.querySelector('#render-a h2');
      bodyA.scrollTop = headingA.offsetTop;
      bodyA.dispatchEvent(new Event('scroll'));
      const bodyB = document.getElementById('body-b');
      return { scrollB: bodyB.scrollTop, maxB: bodyB.scrollHeight - bodyB.clientHeight };
    });

    // Anchor sync puts B near B's heading (~75%+ of maxB).
    // Pure-% from A's tiny scrollTop would give < 5% of maxB.
    expect(scrollB).toBeGreaterThan(maxB * 0.4);
  });
});
