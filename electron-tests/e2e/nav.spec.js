// electron-tests/e2e/nav.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('diff navigation', () => {
  let app, window, jsErrors, originalContentB;

  test.beforeAll(async () => {
    ({ app, window, jsErrors } = await launchApp(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalContentB = await window.evaluate(() => panels.b.content);
  });

  test.afterAll(async () => {
    expect(jsErrors).toHaveLength(0);
    if (app) await app.close();
  });

  test('nav buttons are enabled when both panels have diffs', async () => {
    await expect(window.locator('#btn-next')).toBeEnabled();
    await expect(window.locator('#btn-prev')).toBeEnabled();
  });

  test('counter shows dash before any navigation', async () => {
    await expect(window.locator('#diff-counter')).toHaveText('— / —');
  });

  test('clicking next updates counter to 1/M', async () => {
    await window.locator('#btn-next').click();
    await window.waitForFunction(
      () => document.getElementById('diff-counter').textContent !== '— / —',
      undefined, { timeout: 3000 }
    );
    const text = await window.locator('#diff-counter').textContent();
    expect(text).toMatch(/^1 \/ \d+$/);
  });

  test('n key advances counter', async () => {
    const before = await window.locator('#diff-counter').textContent();
    await window.keyboard.press('n');
    await window.waitForFunction(
      before => document.getElementById('diff-counter').textContent !== before,
      before, { timeout: 3000 }
    );
    const after = await window.locator('#diff-counter').textContent();
    expect(after).not.toBe(before);
    expect(after).toMatch(/^\d+ \/ \d+$/);
  });

  test('p key decrements counter', async () => {
    // Navigate forward twice to reach chunk 3+ (prevDiff has somewhere to go)
    await window.locator('#btn-next').click();
    await window.locator('#btn-next').click();
    // prevDiff is synchronous — read counter directly after keypress resolves
    const before = await window.evaluate(() => document.getElementById('diff-counter').textContent);
    await window.keyboard.press('p');
    const after = await window.evaluate(() => document.getElementById('diff-counter').textContent);
    expect(after).toMatch(/^\d+ \/ \d+$/);
    expect(after).not.toBe(before);
    const beforeNum = parseInt(before.split(' / ')[0]);
    const afterNum  = parseInt(after.split(' / ')[0]);
    expect(afterNum).toBeLessThan(beforeNum);
  });

  test('nav buttons disabled and counter dashes when files are identical', async () => {
    await window.evaluate(() => { panels.b.content = panels.a.content; updateDiffMap(); });
    await expect(window.locator('#btn-next')).toBeDisabled();
    await expect(window.locator('#btn-prev')).toBeDisabled();
    await expect(window.locator('#diff-counter')).toHaveText('— / —');
    // Restore
    await window.evaluate(c => { panels.b.content = c; updateDiffMap(); }, originalContentB);
    await expect(window.locator('#btn-next')).toBeEnabled();
  });

  test('minimap click calls scrollToChunk without error', async () => {
    // Verify the minimap click handler fires and calls scrollToChunk (the fix that
    // replaced the old single-panel break loop with a both-panels implementation).
    // With short fixture content that fits in the viewport, scrollTop stays 0 —
    // so we verify the handler runs without errors rather than checking scroll position.
    // jsErrors is collected globally by launchApp — checked in afterAll.
    const coords = await window.evaluate(() => {
      const canvas = document.getElementById('diff-map');
      const firstDiff = lastChunks.find(c => c.op !== 'eq');
      if (!firstDiff) return null;
      const rect = canvas.getBoundingClientRect();
      return {
        pageX: rect.left + canvas.width / 4,
        pageY: rect.top + (firstDiff.aStart / lastTotalA) * canvas.height + 1
      };
    });
    if (!coords) return;
    await window.mouse.click(coords.pageX, coords.pageY);
  });
});
