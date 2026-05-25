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
});
