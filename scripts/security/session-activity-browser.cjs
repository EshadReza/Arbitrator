/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

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
    const page = await browser.newPage(), calls = [], warnings = [], errors = [];
    page.on('dialog', async d => { warnings.push(d.message()); await d.dismiss(); });
    page.on('pageerror', e => errors.push(e.message));
    let reject = false;
    await page.route('**/*', async route => {
      const req = route.request(), path = new URL(req.url()).pathname;
      if (path === '/admin/index.html') return route.fulfill({ contentType: 'text/html', body: html });
      if (path.startsWith('/api/')) {
        calls.push({ path, method: req.method(), auth: req.headers().authorization });
        return route.fulfill({ status: reject ? 401 : 200, contentType: 'application/json', body: '[]' });
      }
      await route.fulfill({ status: 404, body: '' });
    });
    await page.goto('http://127.0.0.1:47893/admin/index.html');
    await page.evaluate(() => { token = 'fixture'; });
    await page.evaluate(() => api('GET', '/api/admin/contests', null, true));
    assert.equal(calls.filter(c => c.path === '/api/auth/activity').length, 0);
    const active = page.waitForResponse(r => r.url().endsWith('/api/auth/activity'));
    await page.evaluate(() => document.dispatchEvent(new Event('pointerdown')));
    await active;
    await page.evaluate(() => document.dispatchEvent(new Event('keydown')));
    await page.waitForTimeout(100);
    assert.equal(calls.filter(c => c.path === '/api/auth/activity').length, 1, 'Throttle repeated input');
    assert.deepEqual(calls[1], { path: '/api/auth/activity', method: 'POST', auth: 'Bearer fixture' });
    reject = true;
    const reload = page.waitForEvent('load');
    await page.evaluate(() => api('GET', '/api/admin/contests', null, true));
    await reload;
    assert.equal(await page.evaluate(() => token), null);
    assert(await page.locator('#gate').isVisible());
    assert.equal(warnings.length, 1);
    assert(warnings[0].includes('session ended'));
    assert.deepEqual(errors, []);
    console.log('PASS: background reads do not send activity; user input does, throttled; expired session clears UI');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
