// electron-tests/e2e/swap-panels.spec.js
'use strict';
const { test, expect } = require('@playwright/test');
const { _electron: electron } = require('playwright');
const path = require('path');

const ELECTRON_BIN = process.env.ELECTRON_BIN ||
  '/Users/mdproctor/claude/sparge/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron';
const APP_PATH = path.join(__dirname, '..', '..');

// Launch with both files — waits for both panels to render an h1
async function launchBothFiles(fileA, fileB) {
  const app = await electron.launch({ executablePath: ELECTRON_BIN, args: [APP_PATH, fileA, fileB] });
  const window = await app.firstWindow();
  await window.waitForFunction(() => document.querySelector('#render-a h1') !== null, { timeout: 55_000 });
  await window.waitForFunction(() => document.querySelector('#render-b h1') !== null, { timeout: 55_000 });
  return { app, window };
}

// Launch with one file — waits for panel A only
async function launchOneFile(fileA) {
  const app = await electron.launch({ executablePath: ELECTRON_BIN, args: [APP_PATH, fileA] });
  const window = await app.firstWindow();
  await window.waitForFunction(() => document.querySelector('#render-a h1') !== null, { timeout: 55_000 });
  return { app, window };
}

// ── Both files loaded ────────────────────────────────────────────────────────

test.describe('swap panels — both files loaded', () => {
  let app, window, originalPathA, originalPathB;

  test.beforeAll(async () => {
    ({ app, window } = await launchBothFiles(process.env.TEST_FILE_A, process.env.TEST_FILE_B));
    originalPathA = await window.locator('#path-a').textContent();
    originalPathB = await window.locator('#path-b').textContent();
  });

  test.afterAll(async () => { if (app) await app.close(); });

  test('swap button is enabled when both panels are loaded', async () => {
    await expect(window.locator('#btn-swap')).toBeEnabled();
  });

  test('swap reverses panel paths', async () => {
    await window.locator('#btn-swap').click();
    await expect(window.locator('#path-a')).toHaveText(originalPathB);
    await expect(window.locator('#path-b')).toHaveText(originalPathA);
    await window.locator('#btn-swap').click(); // restore
  });

  test('double-swap restores original state', async () => {
    await window.locator('#btn-swap').click();
    await window.locator('#btn-swap').click();
    await expect(window.locator('#path-a')).toHaveText(originalPathA);
    await expect(window.locator('#path-b')).toHaveText(originalPathB);
  });

  test('labels follow content after swap', async () => {
    const originalLabel = await window.locator('#label-a').inputValue();
    await window.locator('#label-a').fill('My Draft');
    await window.locator('#btn-swap').click();
    await expect(window.locator('#label-b')).toHaveValue('My Draft');
    await window.locator('#btn-swap').click(); // restore
    await window.locator('#label-a').fill(originalLabel); // restore label
  });

  test('diff markers remain present on both sides after swap', async () => {
    await window.locator('#btn-swap').click();
    expect(await window.locator('#render-a .diff-del').count()).toBeGreaterThan(0);
    expect(await window.locator('#render-b .diff-ins').count()).toBeGreaterThan(0);
    await window.locator('#btn-swap').click(); // restore
  });

  test('scroll positions reset to top after swap', async () => {
    await window.evaluate(() => {
      document.getElementById('body-a').scrollTop = 200;
      document.getElementById('body-b').scrollTop = 200;
    });
    await window.locator('#btn-swap').click();
    const scrollA = await window.evaluate(() => document.getElementById('body-a').scrollTop);
    const scrollB = await window.evaluate(() => document.getElementById('body-b').scrollTop);
    expect(scrollA).toBe(0);
    expect(scrollB).toBe(0);
    await window.locator('#btn-swap').click(); // restore
  });
});

// ── One file loaded ──────────────────────────────────────────────────────────

test.describe('swap panels — single file loaded', () => {
  let app, window;

  test.beforeAll(async () => {
    ({ app, window } = await launchOneFile(process.env.TEST_FILE_A));
  });

  test.afterAll(async () => { if (app) await app.close(); });

  test('swap button is disabled when only one panel is loaded', async () => {
    await expect(window.locator('#btn-swap')).toBeDisabled();
  });
});
