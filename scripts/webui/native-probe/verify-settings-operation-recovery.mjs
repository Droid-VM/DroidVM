// Reload recovery through native HTTP/TaskService and production browser pages.
// All persistence, ACM gadget IO and process signals are synthetic fixtures.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-settings-operation-recovery-'))
let fixture, browser, diagnostic
const results = []
try {
  fixture = await startFixture(values.exe)
  await fixture.command('usb-acm-mode apply-failed')
  await fixture.command('hugepage-process-mode d-state')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  for (const spec of [
    { namespace: 'host-preferences', endpoint: 'host-preference-operations', resource: 'host/preferences', path: 'settings/vm', prefix: 'host-preferences', locale: 'zh-CN', lang: 'zh-Hans', stats: 'host-preferences-stats', unknown: 'hostPreferencesUnknown' },
    { namespace: 'disk-defaults', endpoint: 'disk-default-operations', resource: 'host/disk-defaults', path: 'settings/disks', prefix: 'disk-defaults', locale: 'en-GB', lang: 'en', stats: 'disk-defaults-stats', unknown: 'hostPreferencesUnknown' },
    { namespace: 'usb-acm', endpoint: 'usb-acm-operations', resource: 'host/usb-acm', path: 'settings/usb-acm', prefix: 'usb-acm', locale: 'zh-TW', lang: 'zh-Hant', stats: 'usb-acm-stats', unknown: 'usbAcmUnknown' },
    { namespace: 'hugepage-processes', endpoint: 'hugepage-process-operations', resource: 'host/hugepage-processes/auto', path: 'hugepages/processes', prefix: 'hugepage-process', locale: 'zh-CN', lang: 'zh-Hans', stats: 'hugepage-process-stats', unknown: 'hpProcessUnknown' },
  ]) {
    const { namespace, endpoint, lang } = spec
    const messages = JSON.parse(readFileSync(new URL(`../../../webui/src/shared/i18n/locales/${lang}.json`, import.meta.url), 'utf8'))
    const context = await browser.newContext({ locale: spec.locale, viewport: { width: 320, height: 568 } })
    const page = await context.newPage(), errors = [], posts = [], lookups = []
    diagnostic = { page, namespace, lang, posts, lookups }
    page.setDefaultTimeout(10000)
    page.on('pageerror', error => errors.push(error.message))
    let extraDiskWrites = 0, resourceReads = 0
    page.on('request', request => {
      const path = new URL(request.url()).pathname
      if (request.method() === 'POST' && path === '/api/v1/disk-operations') extraDiskWrites++
      if (request.method() === 'GET' && path === `/api/v1/${spec.resource}`) resourceReads++
    })
    const ui = key => page.getByTestId(`${spec.prefix}-${key}`)
    const journalKey = `droidvm.operations.v1:${namespace}`
    const operationPath = `/api/v1/${endpoint}`
    let hideLookup = true, admitted = 0, terminalReads = 0, submittedIdentity = null, submittedSignal = null
    await page.route(`**${operationPath}`, async route => {
      if (route.request().method() !== 'POST') return route.continue()
      const request = route.request().postDataJSON()
      posts.push(request.requestId)
      if (namespace === 'hugepage-processes') {
        submittedIdentity = request.expected
        submittedSignal = request.signal
      }
      // Send the actual operation to the native server, then lose only its receipt.
      const response = await route.fetch()
      assert.equal(response.status(), 202)
      admitted++
      await route.abort('connectionfailed').catch(() => {})
    })
    await page.route(`**${operationPath}/*`, async route => {
      assert.equal(route.request().method(), 'GET')
      lookups.push(decodeURIComponent(new URL(route.request().url()).pathname.split('/').at(-1)))
      if (hideLookup) return route.abort('connectionfailed')
      const response = await route.fetch()
      assert.equal(response.status(), 200)
      const task = (await response.json()).task
      if (task && ['succeeded', 'failed', 'cancelled'].includes(task.state)) terminalReads++
      await route.fulfill({ response })
    })
    const read = async path => {
      const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
      assert.equal(response.status(), 200)
      return response.json()
    }
    const noOverflow = async () => {
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      for (const dialog of await page.getByRole('dialog').all())
        assert(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth))
    }
    const capture = async name => {
      await noOverflow()
      await page.screenshot({ path: join(output, `${namespace}-${lang}-${name}-320.png`), fullPage: true })
    }
    await page.goto(`${fixture.origin}/#/${spec.path}`)
    await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
    await page.getByRole('button', { name: messages.sessionLogin, exact: true }).click()
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', lang)
    const session = await read('session'), before = await read(spec.resource), beforeStats = await fixture.command(spec.stats)
    let editable, change, confirm
    if (namespace === 'host-preferences') {
      editable = page.getByTestId('host-preference-vm_clear_logs_before_start')
      change = async () => editable.setChecked(!before.values.vm_clear_logs_before_start)
      confirm = async () => { await ui('review').click(); await ui('confirm').click() }
    } else if (namespace === 'disk-defaults') {
      editable = ui('compression')
      change = async () => editable.selectOption('none')
      confirm = async () => { await ui('review').click(); await ui('confirm').click() }
    } else if (namespace === 'usb-acm') {
      editable = ui('enabled')
      change = async () => { await editable.check(); await ui('ports').selectOption('2') }
      confirm = async () => {
        await ui('review').click()
        await expect(ui('confirm')).toBeDisabled()
        await ui('confirm-disconnect').check()
        await ui('confirm').click()
      }
    } else {
      editable = ui('term-42420')
      change = async () => {}
      confirm = async () => {
        await editable.click()
        await expect(ui('confirm')).toBeDisabled()
        await ui('confirm-check').check()
        await ui('confirm').click()
      }
    }
    const query = async () => {
      const control = ui('query')
      if (await control.count()) await control.click()
    }
    const assertJournal = async () => {
      const result = await page.evaluate(({ key, requestId, csrf, identity, signal }) => {
        const raw = sessionStorage.getItem(key), data = raw && JSON.parse(raw)
        const record = data?.records?.find(item => item.request?.requestId === requestId)
        return { tracked: !!record, secret: !!raw && (/csrfToken|pairingToken|password|cookie/i.test(raw) || raw.includes(csrf)),
          sameProcess: !identity || (JSON.stringify(record?.request.expected) === JSON.stringify(identity) && record?.request.signal === signal) }
      }, { key: journalKey, requestId: posts[0], csrf: session.csrfToken, identity: submittedIdentity, signal: submittedSignal })
      assert(result.tracked)
      assert.equal(result.secret, false)
      assert(result.sameProcess, 'The journal must preserve the original process identity and signal')
    }
    await expect(editable).toBeEnabled()
    await change()
    await confirm()
    await expect.poll(() => admitted).toBe(1)
    await expect(ui('receipt')).toContainText(messages[spec.unknown])
    await assertJournal()
    const priorLookups = lookups.length
    await page.reload()
    await expect(ui('receipt')).toBeVisible()
    await query()
    await expect.poll(() => lookups.length).toBeGreaterThan(priorLookups)
    await expect(editable).toBeDisabled()
    assert.equal(posts.length, 1)
    assert(lookups.every(id => id === posts[0]))
    await assertJournal()
    await capture('reloaded-unknown')
    const readsBeforeRecovery = resourceReads
    hideLookup = false
    await expect(async () => {
      await query()
      await expect(editable).toBeEnabled()
    }).toPass({ timeout: 10000 })
    assert(terminalReads > 0)
    assert(resourceReads > readsBeforeRecovery, 'A completed task must be followed by a fresh resource observation')
    const task = (await read(`${endpoint}/${encodeURIComponent(posts[0])}`)).task
    assert.equal(task.state, namespace === 'usb-acm' ? 'failed' : 'succeeded')
    assert.equal(task.requestId, posts[0])
    const after = await read(spec.resource), afterStats = await fixture.command(spec.stats)
    if (namespace === 'host-preferences') {
      assert.equal(after.values.vm_clear_logs_before_start, !before.values.vm_clear_logs_before_start)
      assert.equal(afterStats.effects, beforeStats.effects + 1)
    } else if (namespace === 'disk-defaults') {
      assert.equal(after.values.optimizeCompression, 'none')
      assert.equal(afterStats.effects, beforeStats.effects + 1)
      assert.equal(extraDiskWrites, 0, 'Restoring a defaults editor must not start disk conversion')
    } else if (namespace === 'usb-acm') {
      assert.equal(task.usbAcmResult.saved, true)
      assert.equal(task.usbAcmResult.applied, false)
      assert.equal(task.usbAcmResult.uncertain, false)
      assert.deepEqual(after.desired, { enabled: true, ports: 2 })
      assert.equal(afterStats.saves, beforeStats.saves + 1)
      assert.equal(afterStats.applies, beforeStats.applies + 1)
      // Configure includes one application attempt. An observed failure must
      // not trigger another attempt on reload, lookup or restored availability.
      await fixture.command('usb-acm-mode ready')
      await ui('refresh').click()
      await expect(ui('apply')).toBeEnabled()
      const readyStats = await fixture.command(spec.stats)
      assert.equal(readyStats.saves, afterStats.saves)
      assert.equal(readyStats.applies, afterStats.applies)
    } else {
      const original = before.entries.find(entry => entry.pid === 42420 || entry.pid === '42420')
      assert(original?.expected)
      assert.deepEqual(submittedIdentity, original.expected)
      assert.equal(submittedSignal, 'term')
      assert.equal(task.hugepageProcessResult.sent, true)
      assert.equal(task.hugepageProcessResult.exited, false)
      assert.equal(afterStats.effects, beforeStats.effects + 1)
      assert.deepEqual(after.entries.find(entry => entry.pid === original.pid).expected, original.expected)
      await query()
      assert.equal((await fixture.command(spec.stats)).effects, afterStats.effects)
    }
    await capture('recovered')
    const notice = page.getByTestId('operation-tracking-notice')
    const checked = page.getByTestId('operation-tracking-confirm')
    const clear = page.getByTestId('operation-tracking-clear')
    if (namespace === 'host-preferences') {
      await page.evaluate(key => sessionStorage.setItem(key, '{broken'), journalKey)
      await page.reload()
      await expect(notice).toHaveAttribute('data-state', 'invalid')
      await expect(editable).toBeDisabled()
      await expect(clear).toBeDisabled()
      await expect(notice).toContainText(messages.operationTrackingConfirm)
      await capture('invalid-journal')
      await checked.check()
      await clear.click()
      await expect(notice).toHaveCount(0)
      await expect(editable).toBeEnabled()
      assert.equal((await fixture.command(spec.stats)).effects, afterStats.effects)
    } else if (namespace === 'disk-defaults') {
      await page.evaluate(key => {
        const original = Storage.prototype.setItem
        window.__restoreSettingsOperationStorage = () => { Storage.prototype.setItem = original }
        Storage.prototype.setItem = function(name, value) {
          if (this === sessionStorage && name === key) throw new DOMException('Fixture quota failure', 'QuotaExceededError')
          return original.call(this, name, value)
        }
      }, journalKey)
      await editable.selectOption('ask')
      await confirm()
      await expect(notice).toHaveAttribute('data-state', 'unavailable')
      await expect(editable).toBeDisabled()
      await expect(clear).toBeDisabled()
      assert.equal(posts.length, 1)
      await capture('storage-unavailable')
      await checked.check()
      await clear.click()
      await expect(notice).toHaveAttribute('data-state', 'unavailable')
      await page.evaluate(() => window.__restoreSettingsOperationStorage())
      if (!(await checked.isChecked())) await checked.check()
      await clear.click()
      await expect(notice).toHaveCount(0)
      await expect(editable).toBeEnabled()
      assert.equal((await fixture.command(spec.stats)).effects, afterStats.effects)
    }
    assert.equal(posts.length, 1)
    assert.equal(extraDiskWrites, 0)
    assert(lookups.every(id => id === posts[0]))
    await noOverflow()
    assert.deepEqual(errors, [])
    results.push({ domain: namespace, language: lang, posts: posts.length, lookups: lookups.length,
      terminalReads, width: 320, recoveredWithoutResubmission: true })
    await context.close()
  }
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, results,
    checks: ['four-domains-real-admission-before-lost-receipt', 'full-reload-queries-original-request-only',
      'terminal-task-followed-by-resource-observation', 'one-side-effect-per-domain',
      'usb-saved-but-apply-failed-does-not-retry-apply', 'original-process-identity-and-term-not-replayed',
      'corrupt-journal-requires-confirmed-clear', 'failed-storage-prevents-submission',
      'disk-default-editor-does-not-start-conversion', 'no-credentials-in-journal', 'three-languages-320px'],
    execution: 'Production browser pages and native HTTP/TaskService with synthetic preferences, USB ACM and process signals. No real process signals, disk conversions, gadget changes or Android validation. The separate disk-default remember owner requires its focused model checks.' }, null, 2))
  console.log(`PASS Settings operation recovery after reload: ${output}`)
} catch (failure) {
  if (diagnostic && !diagnostic.page.isClosed()) {
    const { page, namespace, lang, posts, lookups } = diagnostic
    const prefix = `${namespace}-${lang}-failure`
    const dom = await page.evaluate(() => [...document.querySelectorAll(
      '[data-testid$="-receipt"], [data-testid$="-stale"], [data-testid="operation-tracking-notice"]',
    )].slice(0, 12).map(element => ({ testid: element.getAttribute('data-testid'), state: element.getAttribute('data-state'),
      text: element.textContent.replace(/[a-f0-9]{32,}/gi, '[redacted-id]').replace(/\s+/g, ' ').trim().slice(0, 700) }))).catch(() => [])
    writeFileSync(join(output, `${prefix}.json`), JSON.stringify({ domain: namespace, language: lang, posts: posts.length, lookups: lookups.length, dom }, null, 2))
    await page.screenshot({ path: join(output, `${prefix}.png`), fullPage: true,
      mask: [page.locator('#pairing-token, input[type="password"]')] }).catch(() => {})
    console.error(`Settings operation recovery diagnostic: ${join(output, prefix)}`)
  }
  throw failure
} finally {
  await browser?.close()
  await fixture?.close()
}
