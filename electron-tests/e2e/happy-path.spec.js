// electron-tests/e2e/happy-path.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { launchApp }    = require('./helpers');

// Wrap all tests in a describe block so beforeAll/afterAll run exactly once
// for the entire suite rather than per-test (Playwright scoping requirement).
const jsErrors = [];

test.describe('happy path', () => {
  let app, window;

  test.beforeAll(async () => {
    // Launch app — listener must be set up as part of launch
    const result = await launchApp(
      process.env.TEST_FILE_A,
      process.env.TEST_FILE_B
    );
    app    = result.app;
    window = result.window;
    // Register error listener immediately after window is obtained
    // (launchApp waits for DOM, so this catches runtime errors not startup errors,
    // but is still valuable for catching errors triggered by test interactions)
    window.on('pageerror', e => jsErrors.push(e.message));
  });

  test.afterAll(async () => { if (app) await app.close(); });

  test('app launches — window visible, no JS errors', async () => {
    await expect(window.locator('#topbar')).toBeVisible();
    await expect(window.locator('#logo')).toContainText('md-compare');
    expect(jsErrors).toHaveLength(0);
  });

  test('panel A renders the heading from file A', async () => {
    await expect(window.locator('#render-a h1')).toContainText('Rule Engines');
  });

  test('panel B renders the heading from file B', async () => {
    await expect(window.locator('#render-b h1')).toContainText('Rule Engines');
  });

  test('both panels render code blocks', async () => {
    await expect(window.locator('#render-a pre')).toBeVisible();
    await expect(window.locator('#render-b pre')).toBeVisible();
  });

  test('diff markers appear in panel A for changed sections', async () => {
    const count = await window.locator('#render-a .diff-del').count();
    expect(count).toBeGreaterThan(0);
  });

  test('diff markers appear in panel B for changed sections', async () => {
    const count = await window.locator('#render-b .diff-ins').count();
    expect(count).toBeGreaterThan(0);
  });

  test('diff minimap canvas has coloured segments', async () => {
    const hasColor = await window.evaluate(() => {
      const canvas = document.getElementById('diff-map');
      const ctx    = canvas.getContext('2d');
      const w = canvas.width, h = canvas.height;
      // Sample a grid: every 4px vertically, every 3px horizontally
      // Look for any pixel that is not near-grey (R, G, B not all within 30 of each other)
      for (let y = 0; y < h; y += 4) {
        for (let x = 1; x < w - 1; x += 3) {
          const px = ctx.getImageData(x, y, 1, 1).data;
          const [r, g, b, a] = px;
          if (a === 0) continue; // transparent
          const avg = (r + g + b) / 3;
          if (Math.abs(r - avg) > 30 || Math.abs(g - avg) > 30 || Math.abs(b - avg) > 30) {
            return true; // found a non-grey pixel — canvas has coloured content
          }
        }
      }
      return false;
    });
    expect(hasColor).toBe(true);
  });

  test('sync toggle button is active by default', async () => {
    await expect(window.locator('#btn-sync')).toHaveClass(/active/);
  });
});
