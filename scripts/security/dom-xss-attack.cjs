/*
 * Copyright (c) 2026 Eshad Bin Reza, Mahir Labib, Zahin Ahmad.
 * All rights reserved.
 */

// Run from the repository root with Playwright on NODE_PATH and compiled server classes.
const { chromium } = require('playwright');
const { readFileSync } = require('node:fs');
const { execFileSync } = require('node:child_process');
const { resolve } = require('node:path');
const assert = require('node:assert/strict');

const root = resolve(__dirname, '../..');
const html = readFileSync(resolve(root, 'arbitrator-server/src/main/resources/static/admin/index.html'), 'utf8');
const jsoup = process.env.JSOUP_JAR;
assert(jsoup, 'Set JSOUP_JAR to the installed jsoup 1.23.2 jar');
const payloads = [
  '<img src=x onerror="window.__xssProbe++">',
  '<svg onload="window.__xssProbe++"></svg>',
  '</pre><script>window.__xssProbe++</script>',
  '\"><img src=x onerror="window.__xssProbe++">',
];

(async () => {
  const browser = await chromium.launch({ headless: true,
    executablePath: process.env.CHROMIUM_PATH || chromium.executablePath() });
  let checks = 0;
  try {
    const context = await browser.newContext();
    // Positive control: verify this browser really executes script and image-error payloads.
    const control = await context.newPage();
    await control.setContent('<script>window.__xssProbe=1</script>'
      + '<img src="data:image/png;base64,broken" onerror="window.__xssProbe++">');
    await control.waitForTimeout(150);
    assert.equal(await control.evaluate(() => window.__xssProbe), 2);
    await control.close();
    const page = await context.newPage();
    const dialogs = [];
    const errors = [];
    page.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });
    page.on('pageerror', e => errors.push(e.message));
    let fixtures = {};
    await context.route('**/*', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path === '/admin/index.html') {
        await route.fulfill({ contentType: 'text/html', body: html });
      } else if (path.startsWith('/api/')) {
        assert(Object.hasOwn(fixtures, path), 'Unexpected API request: ' + path);
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify(fixtures[path]) });
      } else {
        await route.fulfill({ status: 404, body: '' });
      }
    });
    for (const payload of payloads) {
      const sanitized = execFileSync('java', ['-cp',
        resolve(root, 'arbitrator-server/target/classes') + ':' + jsoup,
        resolve(__dirname, 'RichTextFixture.java'), '<p>safe n<sup>2</sup></p>' + payload], { encoding: 'utf8' });
      fixtures = {
        '/api/admin/contests/1/clarifications': [{ id: 1, question: payload, answer: payload,
          askedBy: payload, problemCode: payload, problemTitle: payload,
          isPublic: false, approved: true, askedAtMs: 1, answeredAtMs: 1 }],
        '/api/admin/submissions/1/tests': { passedCount: 0, totalTestCases: 1, tests: [
          { index: 1, verdict: 'WA', execTimeMs: 1, input: payload,
            expectedOutput: payload, actualOutput: payload }] },
        '/api/admin/contests/1/announcements': [{ id: 1, body: sanitized, createdAtMs: 1 }],
      };
      await page.goto('http://127.0.0.1:47891/admin/index.html');
      await page.evaluate(() => { window.__xssProbe = 0; selected = 1; token = 'fixture-only'; });
      await page.evaluate(async payload => {
        const n = { id: 1, username: 'probe', displayName: payload, contestTitle: payload,
          type: 'MAC_CHANGED', atMs: 1 };
        liveNotifs = [n]; renderLiveNotifs(); renderNotifTab([n]); renderUserNotifModal('probe', [n]);
        await loadClarifications();
        await loadSourceTests(1);
        $('annBody').value = payload; renderAnnouncementPreview();
        await loadAnnouncements();
        $('pvStatement').srcdoc = '<img src=x onerror="parent.__xssProbe++">';
        state = { title: payload };
        const report = buildReport([{ username: 'probe', displayName: payload, solved: 0,
          penaltyMinutes: 0, marks: 0, bestVerdict: 'WA', bestExecTimeMs: 1, bestLanguage: payload }],
          { probe: [{ problemCode: payload, problemTitle: payload, verdict: 'WA',
            sourceCode: payload, compilerOutput: payload, language: payload, execTimeMs: 1 }] },
          [payload], { summary: true, problems: true, best: true, source: true });
        window.__report = report;
      }, payload);
      // Allow SVG/image load/error handlers to fire if any payload reached an active sink.
      await page.waitForTimeout(150);
      const result = await page.evaluate(payload => {
        const areas = ['alertStack', 'notifList', 'userNotifContainer', 'clarifyList',
          'sourceTests', 'annPreview', 'annList'];
        return {
          executed: window.__xssProbe,
          active: areas.flatMap(id => Array.from($(id).querySelectorAll('img,svg,script,iframe,object,embed'))).length,
          handlers: areas.flatMap(id => Array.from($(id).querySelectorAll('*')))
            .flatMap(e => Array.from(e.attributes)).filter(a => /^on/i.test(a.name)).length,
          literal: ['alertStack', 'notifList', 'userNotifContainer', 'clarifyList', 'sourceTests', 'annPreview']
            .every(id => $(id).textContent.includes(payload)),
          formatting: $('annList').querySelector('sup')?.textContent === '2',
          sandbox: $('pvStatement').getAttribute('sandbox'),
          report: window.__report,
        };
      }, payload);
      assert.equal(result.executed, 0); assert.equal(result.active, 0); assert.equal(result.handlers, 0);
      assert(result.literal); assert(result.formatting); assert.equal(result.sandbox, '');
      const reportPage = await context.newPage();
      reportPage.on('dialog', async d => { dialogs.push(d.message()); await d.dismiss(); });
      await reportPage.evaluate(() => { window.__xssProbe = 0; });
      await reportPage.setContent(result.report);
      await reportPage.waitForTimeout(150);
      assert.equal(await reportPage.evaluate(() => window.__xssProbe), 0);
      assert.equal(await reportPage.locator('script,img,svg,iframe,object,embed').count(), 0);
      assert((await reportPage.locator('body').textContent()).includes(payload));
      await reportPage.close();
      checks++;
    }
    assert.deepEqual(dialogs, []);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ status: 'PASS', positiveControl: 'script and image handler executed', payloads: checks,
      paths: ['live notifications', 'notification list', 'notification history', 'clarifications',
        'submission test output', 'announcement draft', 'sanitized announcements', 'sandboxed statement', 'HTML export'],
      executions: 0, dialogs: 0, pageErrors: 0 }, null, 2));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
