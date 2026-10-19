// Browser reload recovery over actual native HTTP/TaskService admission.
// Resource changes are synthetic; this does not change host networking or a KO.
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
const output = mkdtempSync(join(resolve(values.output), 'native-host-operation-recovery-'))
let fixture, browser, diagnostic
const results = []
try {
  fixture = await startFixture(values.exe)
  await fixture.command('hugepage-mode ready')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  for (const spec of [
    { domain: 'network', namespace: 'network-runtime', locale: 'zh-CN', lang: 'zh-Hans', path: 'resources/network', resource: 'networks/fixture-network' },
    { domain: 'hugepage', namespace: 'hugepages', locale: 'en-GB', lang: 'en', path: 'hugepages', resource: 'host/hugepages' },
  ]) {
    const { domain, namespace, locale, lang } = spec
    const messages = JSON.parse(readFileSync(new URL(`../../../webui/src/shared/i18n/locales/${lang}.json`, import.meta.url), 'utf8'))
    const context = await browser.newContext({ locale, viewport: { width: 320, height: 568 } })
    const page = await context.newPage(), errors = [], posts = [], lookups = []
    page.setDefaultTimeout(10000)
    page.on('pageerror', error => errors.push(error.message))
    diagnostic = { page, domain, lang, posts, lookups }
    const button = (key, scope = page) => scope.getByRole('button', { name: messages[key], exact: true })
    const hp = key => page.getByTestId(`hugepages-${key}`)
    const journalKey = `droidvm.operations.v1:${namespace}`
    const operationPath = `/api/v1/${domain}-operations`
    let hideLookup = true, rejectRefresh = false, admitted = 0, terminalReads = 0, rejectedReads = 0
    await page.route(`**${operationPath}`, async route => {
      if (route.request().method() !== 'POST') return route.continue()
      posts.push(route.request().postDataJSON().requestId)
      // Admission and execution reach the actual server before the response is lost.
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
      const data = await response.json()
      if (data.task?.state === 'succeeded') terminalReads++
      await route.fulfill({ response })
    })
    await page.route(`**/api/v1/${spec.resource}`, route => {
      if (rejectRefresh) { rejectedReads++; return route.abort('connectionfailed') }
      return route.continue()
    })
    const read = async resource => {
      const response = await page.request.get(`${fixture.origin}/api/v1/${resource}`)
      assert.equal(response.status(), 200)
      return response.json()
    }
    const noOverflow = async () => {
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
      for (const dialog of await page.getByRole('dialog').all())
        assert(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth), 'Dialog must fit horizontally')
    }
    const capture = async name => {
      await noOverflow()
      await page.screenshot({ path: join(output, `${domain}-${lang}-${name}-320.png`), fullPage: true })
    }
    await page.goto(`${fixture.origin}/#/${spec.path}`)
    await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
    await button('sessionLogin').click()
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', lang)
    const session = await read('session')
    const before = await read(spec.resource)
    const beforeEffects = domain === 'hugepage' ? (await fixture.command('hugepage-stats')).effects : null
    const assertJournal = async () => {
      const result = await page.evaluate(({ key, requestId, csrf }) => {
        const raw = sessionStorage.getItem(key), data = raw && JSON.parse(raw)
        return { tracked: !!data?.records?.some(item => item.request?.requestId === requestId),
          secret: !!raw && (/csrfToken|pairingToken|cookie|password/i.test(raw) || raw.includes(csrf)) }
      }, { key: journalKey, requestId: posts[0], csrf: session.csrfToken })
      assert(result.tracked)
      assert.equal(result.secret, false)
    }
    let status, query, refresh, openAfterReload, unlocked, submit
    if (domain === 'network') {
      assert.equal(before.runtime.state, 'STOPPED')
      const details = page.locator('.network-details')
      status = page.getByTestId('network-operation-status')
      await button('networkDetails').click()
      await expect(button('networkStart', details)).toBeEnabled()
      unlocked = () => button('networkStop', details)
      query = async () => {
        const control = button('queryStatus', status)
        if (await control.count()) await control.click()
      }
      refresh = () => button('refreshList', details).click()
      openAfterReload = async () => {
        await expect(page.getByTestId('network-operation-recovery')).toBeVisible()
        await page.getByRole('button', { name: `${messages.networkResumeAction} · fixture-network`, exact: true }).click()
        await expect(status).toBeVisible()
      }
      submit = async () => {
        await button('networkStart', details).click()
        await button('confirm', page.getByRole('dialog', { name: messages.networkStart, exact: true })).click()
      }
    } else {
      assert.equal(before.bootEnabled, true)
      await hp('tab-module').click()
      await expect(hp('boot-disable')).toBeEnabled()
      status = hp('receipt')
      unlocked = () => hp('boot-enable')
      query = () => hp('query').click()
      refresh = () => hp('refresh').click()
      openAfterReload = async () => {
        await expect(hp('page')).toBeVisible()
        await hp('tab-module').click()
        await expect(hp('tracking-restored')).toBeVisible()
      }
      submit = async () => {
        await hp('boot-disable').click()
        await hp('confirm').click()
      }
    }
    await submit()
    await expect.poll(() => admitted).toBe(1)
    await expect(status).toContainText(messages[domain === 'network' ? 'networkActionUnknown' : 'hugepagesUnknown'])
    await assertJournal()
    const previousLookups = lookups.length
    await page.reload()
    await openAfterReload()
    await query()
    await expect.poll(() => lookups.length).toBeGreaterThan(previousLookups)
    assert(lookups.every(id => id === posts[0]))
    assert.equal(posts.length, 1)
    await expect(unlocked()).toBeDisabled()
    await assertJournal()
    await capture('reloaded-unknown')

    // Returning a successful original task alone must not enable a new write.
    // Its resource observation remains unavailable until a separate user refresh.
    rejectRefresh = true
    hideLookup = false
    await query()
    await expect.poll(() => terminalReads).toBeGreaterThan(0)
    await expect.poll(() => rejectedReads).toBeGreaterThan(0)
    await expect(status).toContainText(messages.taskSucceeded)
    if (await unlocked().count()) await expect(unlocked()).toBeDisabled()
    else assert.equal(domain, 'network', 'Only the network detail is cleared on a read failure')
    assert.equal(posts.length, 1)
    rejectRefresh = false
    await refresh()
    await expect(async () => {
      await query()
      await expect(unlocked()).toBeEnabled()
    }).toPass({ timeout: 10000 })
    assert.equal(posts.length, 1)
    await capture('recovered')
    const after = await read(spec.resource)
    if (domain === 'network') {
      assert.equal(after.runtime.state, 'RUNNING')
      assert(BigInt(after.runtime.generation) > BigInt(before.runtime.generation))
    } else {
      assert.equal(after.bootEnabled, false)
      assert.equal(after.loaded, before.loaded, 'Boot preference must not unload the module')
      assert.equal((await fixture.command('hugepage-stats')).effects, beforeEffects + 1)
    }
    const notice = page.getByTestId('operation-tracking-notice')
    const confirmation = page.getByTestId('operation-tracking-confirm')
    const clear = page.getByTestId('operation-tracking-clear')
    if (domain === 'network') {
      await page.evaluate(key => sessionStorage.setItem(key, '{broken'), journalKey)
      await page.reload()
      await expect(notice).toHaveAttribute('data-state', 'invalid')
      await expect(clear).toBeDisabled()
      await expect(notice).toContainText(messages.operationTrackingConfirm)
      await capture('invalid-journal')
      // Invalid tracking blocks actions even after inspecting a fresh resource.
      await button('networkDetails').click()
      await expect(unlocked()).toBeDisabled()
      await button('close', page.getByRole('dialog')).click()
      await confirmation.check()
      await clear.click()
      await expect(notice).toHaveCount(0)
      await button('networkDetails').click()
      await expect(unlocked()).toBeEnabled()
      assert.equal((await read(spec.resource)).runtime.generation, after.runtime.generation)
    } else {
      await page.evaluate(key => {
        const original = Storage.prototype.setItem
        window.__restoreHostOperationStorage = () => { Storage.prototype.setItem = original }
        Storage.prototype.setItem = function(name, value) {
          if (this === sessionStorage && name === key) throw new DOMException('Fixture write failure', 'QuotaExceededError')
          return original.call(this, name, value)
        }
      }, journalKey)
      await unlocked().click()
      await hp('confirm').click()
      await expect(notice).toHaveAttribute('data-state', 'unavailable')
      await expect(unlocked()).toBeDisabled()
      await expect(clear).toBeDisabled()
      assert.equal(posts.length, 1, 'Failed journal persistence must prevent the second submission')
      await capture('storage-unavailable')
      await confirmation.check()
      await clear.click()
      await expect(notice).toHaveAttribute('data-state', 'unavailable')
      await page.evaluate(() => window.__restoreHostOperationStorage())
      if (!(await confirmation.isChecked())) await confirmation.check()
      await clear.click()
      await expect(notice).toHaveCount(0)
      await expect(unlocked()).toBeEnabled()
      assert.equal((await fixture.command('hugepage-stats')).effects, beforeEffects + 1)
    }
    assert.equal(posts.length, 1)
    assert(lookups.every(id => id === posts[0]))
    await noOverflow()
    assert.deepEqual(errors, [])
    results.push({ domain, language: lang, posts: posts.length, lookups: lookups.length,
      originalTaskTerminalReads: terminalReads, blockedResourceReads: rejectedReads, width: 320 })
    await context.close()
  }
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, results,
    checks: ['real-http-admission-before-receipt-loss', 'full-reload-queries-original-request-without-post',
      'terminal-task-requires-fresh-business-observation', 'network-corrupt-storage-requires-confirmed-clear',
      'hugepage-storage-write-failure-prevents-post', 'no-secrets-in-journal', 'two-browser-languages-320px'],
    execution: 'Real native HTTP/TaskService with synthetic network and hugepage resources. Browser routes disturb response delivery and observation reads; no real host networking or kernel module changes.' }, null, 2))
  console.log(`PASS Host operation recovery after reload: ${output}`)
} catch (failure) {
  if (diagnostic && !diagnostic.page.isClosed()) {
    const { page, domain, lang, posts, lookups } = diagnostic
    const prefix = `${domain}-${lang}-failure`
    const dom = await page.evaluate(() => [...document.querySelectorAll(
      '[data-testid="network-operation-status"], [data-testid="network-operation-recovery"], [data-testid="hugepages-receipt"], [data-testid="hugepages-stale"], [data-testid="operation-tracking-notice"]',
    )].map(element => ({ state: element.getAttribute('data-state'), testid: element.getAttribute('data-testid'),
      text: element.textContent.replace(/[a-f0-9]{32,}/gi, '[redacted-id]').replace(/\s+/g, ' ').trim().slice(0, 700) }))).catch(() => [])
    writeFileSync(join(output, `${prefix}.json`), JSON.stringify({ domain, language: lang, posts: posts.length, lookups: lookups.length, dom }, null, 2))
    await page.screenshot({ path: join(output, `${prefix}.png`), fullPage: true,
      mask: [page.locator('#pairing-token, input[type="password"]')] }).catch(() => {})
    console.error(`Host operation recovery diagnostic: ${join(output, prefix)}`)
  }
  throw failure
} finally {
  await browser?.close()
  await fixture?.close()
}
