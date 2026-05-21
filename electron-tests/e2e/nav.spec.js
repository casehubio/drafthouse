// electron-tests/e2e/nav.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

test.describe('diff navigation', () => {
  let app, window, originalContentB;

  test.beforeAll(async () => {
    ({ app, window } = await launchApp(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalContentB = await window.evaluate(() => panels.b.content);
  });

  test.afterAll(async () => { if (app) await app.close(); });

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
    // Ensure we are at chunk >= 2 so p has somewhere to go
    await window.locator('#btn-next').click();
    await window.waitForFunction(
      () => /^[2-9]/.test(document.getElementById('diff-counter').textContent),
      undefined, { timeout: 3000 }
    );
    const before = await window.locator('#diff-counter').textContent();
    await window.keyboard.press('p');
    await window.waitForFunction(
      before => document.getElementById('diff-counter').textContent !== before,
      before, { timeout: 3000 }
    );
    const after = await window.locator('#diff-counter').textContent();
    expect(after).not.toBe(before);
    expect(after).toMatch(/^\d+ \/ \d+$/);
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

  test('minimap click scrolls at least one panel to a diff', async () => {
    await window.evaluate(() => {
      document.getElementById('body-a').scrollTop = 0;
      document.getElementById('body-b').scrollTop = 0;
    });
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
    await window.waitForFunction(
      () => document.getElementById('body-a').scrollTop > 0 ||
            document.getElementById('body-b').scrollTop > 0,
      undefined, { timeout: 2000 }
    ).catch(() => {});
    const scrollA = await window.evaluate(() => document.getElementById('body-a').scrollTop);
    const scrollB = await window.evaluate(() => document.getElementById('body-b').scrollTop);
    expect(Math.max(scrollA, scrollB)).toBeGreaterThan(0);
  });
});
