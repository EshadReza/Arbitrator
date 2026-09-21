/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

// Actual admin page; controlled logout responses, with no live accounts/server.
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const assert = require('node:assert/strict');
const html = readFileSync(resolve(__dirname,
  '../../arbitrator-server/src/main/resources/static/admin/index.html'), 'utf8');

(async () => {
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.CHROMIUM_PATH || chromium.executablePath() });
  try {
    for (const outcome of [200, 401, 500, 'network-error']) {
      const context = await browser.newContext();
      const page = await context.newPage();
      const warnings = [], errors = [], calls = [];
      page.on('dialog', async d => { warnings.push(d.message()); await d.dismiss(); });
      page.on('pageerror', e => errors.push(e.message));
      await context.route('**/*', async route => {
        const request = route.request(), path = new URL(request.url()).pathname;
        if (path === '/admin/index.html') await route.fulfill({ contentType: 'text/html', body: html });
        else if (path === '/api/auth/logout') {
          calls.push({ method: request.method(), authorization: request.headers().authorization });
          if (outcome === 'network-error') await route.abort();
          else await route.fulfill({ status: outcome, body: '' });
        } else await route.fulfill({ status: 404, body: '' });
      });
      await page.goto('http://127.0.0.1:47892/admin/index.html');
      await page.evaluate(() => {
        token = 'fixture-token'; $('app').classList.remove('hide'); $('gate').classList.add('hide');
      });
      const reloaded = page.waitForEvent('load');
      await page.locator('#adminSignOut').click();
      await reloaded;
      assert.deepEqual(calls, [{ method: 'POST', authorization: 'Bearer fixture-token' }]);
      assert.equal(await page.evaluate(() => token), null);
      assert(await page.locator('#gate').isVisible());
      assert.equal(warnings.length, outcome === 200 || outcome === 401 ? 0 : 1);
      if (warnings.length) assert(warnings[0].includes('Server logout was not confirmed'));
      assert.deepEqual(errors, []);
      await context.close();
    }
    console.log('PASS: admin logout success, already-invalid token, server failure, and network failure');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
