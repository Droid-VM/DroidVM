// Real native HTTP admission with browser-side receipt loss and full-page reload.
// Operations mutate synthetic fixture state, not actual VMs, USB or disk files.
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
const output = mkdtempSync(join(resolve(values.output), 'native-operation-recovery-'))
let browser, fixture, activeDiagnostic
const results = []
try {
  fixture = await startFixture(values.exe)
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  for (const [domain, locale, lang, routePath] of [
    ['vm', 'zh-CN', 'zh-Hans', 'vms'],
    ['usb', 'en-GB', 'en', 'resources/usb'],
    ['disk', 'zh-TW', 'zh-Hant', 'resources/disk'],
  ]) {
    const messages = JSON.parse(readFileSync(new URL(`../../../webui/src/shared/i18n/locales/${lang}.json`, import.meta.url), 'utf8'))
    const context = await browser.newContext({ locale, viewport: { width: 320, height: 568 } })
    const page = await context.newPage(), errors = []
    page.setDefaultTimeout(10000)
    page.on('pageerror', error => errors.push(error.message))
    const button = (key, scope = page) => scope.getByRole('button', { name: messages[key], exact: true })
    const journalKey = `droidvm.operations.v1:${domain}`
    const operationPath = `/api/v1/${domain}-operations`
    const posts = [], lookups = [], fetched = []
    activeDiagnostic = { page, domain, lang, posts, lookups }
    let hideLookup = true, loseReceipt = true, holdReceipt = false, releaseReceipt
    let rejectVmRefresh = false
    await page.route(`**${operationPath}`, async route => {
      if (route.request().method() !== 'POST') return route.continue()
      const request = route.request().postDataJSON()
      posts.push(request.requestId)
      // Execute against the native server first. Only delivery is disturbed.
      const response = await route.fetch()
      assert.equal(response.status(), 202)
      fetched.push(request.requestId)
      if (holdReceipt) await new Promise(resolve => { releaseReceipt = resolve })
      if (loseReceipt) await route.abort('connectionfailed').catch(() => {})
      else await route.fulfill({ response }).catch(() => {})
    })
    await page.route(`**${operationPath}/*`, async route => {
      lookups.push(decodeURIComponent(new URL(route.request().url()).pathname.split('/').at(-1)))
      if (hideLookup) return route.abort('connectionfailed')
      return route.continue()
    })
    if (domain === 'vm') await page.route('**/api/v1/vms', route => rejectVmRefresh ? route.abort('connectionfailed') : route.continue())
    await page.goto(`${fixture.origin}/#/${routePath}`)
    await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
    await button('sessionLogin').click()
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', lang)
    const session = await (await page.request.get(`${fixture.origin}/api/v1/session`)).json()
    const assertJournal = async requestId => {
      const checked = await page.evaluate(({ key, requestId, csrf }) => {
        const raw = sessionStorage.getItem(key)
        const data = raw && JSON.parse(raw)
        return { present: !!data?.records?.some(record => record.request?.requestId === requestId),
          sensitive: !!raw && (/csrfToken|pairingToken|password|cookie/i.test(raw) || raw.includes(csrf)) }
      }, { key: journalKey, requestId, csrf: session.csrfToken })
      assert.equal(checked.present, true)
      assert.equal(checked.sensitive, false, 'Journal must contain no pairing, password, cookie or CSRF secrets')
    }
    const noOverflow = async () => expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    let actionButton, query, submit, unlocked, status
    if (domain === 'vm') {
      const card = page.locator('[data-vm="native-http-vm"]')
      await expect(card).toBeVisible()
      const vm = (await (await page.request.get(`${fixture.origin}/api/v1/vms`)).json()).items.find(item => item.id === 'native-http-vm')
      const action = vm.lifecycle.actions.includes('stop') ? 'stop' : 'start'
      actionButton = () => button(action, card)
      unlocked = () => button(action === 'stop' ? 'start' : 'stop', card)
      query = async () => {
        const control = button('queryStatus', card)
        if (await control.count()) await control.click()
      }
      status = card
      submit = async () => {
        await actionButton().click()
        await button(action, page.getByRole('dialog', { name: messages[action], exact: true })).click()
      }
    } else if (domain === 'usb') {
      const card = page.locator('[data-usb-id="1-1.2#3"]')
      await expect(card).toBeVisible()
      actionButton = () => button('usbChangeTarget', card)
      unlocked = actionButton
      query = () => button('queryStatus').click()
      status = page.locator('[data-usb-operation="1-1.2#3"]')
      submit = async () => {
        await actionButton().click()
        const dialog = page.getByRole('dialog', { name: messages.usbChangeTarget, exact: true })
        await dialog.getByRole('combobox', { name: messages.usbTarget, exact: true }).selectOption('host')
        await button('confirm', dialog).click()
      }
      // Reload while the actual admission response is still pending, so a
      // posting request also survives; the server has already executed it.
      holdReceipt = true
    } else {
      actionButton = () => button('diskCreate')
      unlocked = actionButton
      status = page.locator('.disk-status')
      query = () => button('queryStatus', status).click()
      submit = async () => {
        await actionButton().click()
        const dialog = page.getByRole('dialog')
        await dialog.getByLabel(messages.diskHostFolder, { exact: true }).fill('/fixture')
        await dialog.getByLabel(messages.diskFileName, { exact: true }).fill('fixture-uncertain.qcow2')
        await button('diskReview', dialog).click()
        await dialog.getByLabel(messages.diskAcknowledge, { exact: true }).check()
        await button('diskExecute', dialog).click()
      }
    }
    const vmEffectsBefore = domain === 'vm' ? (await fixture.command('stats')).vmEffects : null
    await submit()
    await expect.poll(() => fetched.length).toBe(1)
    await assertJournal(posts[0])
    if (domain !== 'usb') await expect(status).toContainText(messages.vmOperationUnknown)
    const lookupsBeforeReload = lookups.length
    await page.reload()
    releaseReceipt?.()
    holdReceipt = false
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect(status).toBeVisible()
    await query()
    await expect.poll(() => lookups.length).toBeGreaterThan(lookupsBeforeReload)
    assert(lookups.every(id => id === posts[0]), 'Only the original request may be queried')
    await expect(unlocked()).toBeDisabled()
    assert.equal(posts.length, 1, 'Reload must never POST an operation again')
    await assertJournal(posts[0])
    await noOverflow()
    await page.screenshot({ path: join(output, `${domain}-${lang}-reloaded-unknown-320.png`), fullPage: true })
    hideLookup = false
    if (domain === 'vm') {
      // A real terminal task does not unlock until a fresh resource read succeeds.
      rejectVmRefresh = true
      await query()
      // Inventory intentionally clears stale cards after a failed read. Its
      // error-state retry button is the available user path back to the list.
      const retryInventory = page.locator('.instances .empty-state').getByRole('button')
      await expect(retryInventory).toBeEnabled()
      await expect(status).toHaveCount(0)
      assert.equal(posts.length, 1)
      rejectVmRefresh = false
      await retryInventory.click()
      await expect(status).toBeVisible()
    }
    await expect(async () => {
      await query()
      if (domain === 'disk') await expect(status).toContainText('COMMIT_UNCERTAIN')
      else await expect(unlocked()).toBeEnabled()
    }).toPass({ timeout: 10000 })
    assert.equal(posts.length, 1)
    if (domain === 'vm') assert.equal((await fixture.command('stats')).vmEffects, vmEffectsBefore + 1)
    if (domain === 'disk') {
      await expect(unlocked()).toBeDisabled()
      await page.reload()
      await expect(async () => {
        await query()
        await expect(status).toContainText('COMMIT_UNCERTAIN')
      }).toPass({ timeout: 10000 })
      await expect(unlocked()).toBeDisabled()
      assert.equal(posts.length, 1, 'Reload must preserve the uncertain-commit gate')
      await noOverflow()
      await page.screenshot({ path: join(output, `${domain}-${lang}-uncertain-320.png`), fullPage: true })
    } else {
      // A corrupt journal locks even without a usable recovered entry; clearing
      // it is a separate user-confirmed action, never a replay of its contents.
      await page.evaluate(key => sessionStorage.setItem(key, '{broken'), journalKey)
      await page.reload()
      const notice = page.getByTestId('operation-tracking-notice')
      const confirmation = page.getByTestId('operation-tracking-confirm')
      const clear = page.getByTestId('operation-tracking-clear')
      await expect(notice).toHaveAttribute('data-state', 'invalid')
      await expect(unlocked()).toBeDisabled()
      await expect(clear).toBeDisabled()
      await expect(notice).toContainText(messages.operationTrackingConfirm)
      await noOverflow()
      await notice.scrollIntoViewIfNeeded()
      await page.screenshot({ path: join(output, `${domain}-${lang}-invalid-journal-320.png`) })
      await confirmation.check()
      await clear.click()
      await expect(notice).toHaveCount(0)
      await expect(unlocked()).toBeEnabled()
      assert.equal(posts.length, 1)
      if (domain === 'vm') {
        // Fail only operation-journal writes; authentication storage and HTTP
        // remain healthy. The attempted operation must not reach the server.
        await page.evaluate(key => {
          const original = Storage.prototype.setItem
          window.__restoreOperationStorage = () => { Storage.prototype.setItem = original }
          Storage.prototype.setItem = function(name, value) {
            if (this === sessionStorage && name === key) throw new DOMException('Fixture quota failure', 'QuotaExceededError')
            return original.call(this, name, value)
          }
        }, journalKey)
        const actionLabel = (await unlocked().innerText()).trim()
        await unlocked().click()
        await page.getByRole('dialog').getByRole('button', { name: actionLabel, exact: true }).click()
        await expect(notice).toHaveAttribute('data-state', 'unavailable')
        await expect(unlocked()).toBeDisabled()
        assert.equal(posts.length, 1, 'Failed persistence must prevent submission')
        await expect(clear).toBeDisabled()
        await confirmation.check()
        await clear.click()
        await expect(notice).toHaveAttribute('data-state', 'unavailable')
        assert.equal(posts.length, 1)
        await page.evaluate(() => window.__restoreOperationStorage())
        // Retrying explicit cleanup may require confirming again after a failure.
        if (!(await confirmation.isChecked())) await confirmation.check()
        await clear.click()
        await expect(notice).toHaveCount(0)
        await expect(unlocked()).toBeEnabled()
        assert.equal(posts.length, 1)
      }
    }
    assert.deepEqual(errors, [])
    results.push({ domain, language: lang, posts: posts.length, lookups: lookups.length,
      reloadQueriesOnly: true, uncertainCommitLocked: domain === 'disk', width: 320 })
    await context.close()
  }
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, results,
    checks: ['real-http-admission-before-receipt-loss', 'posting-and-unknown-survive-full-reload',
      'original-request-get-only-no-replay', 'terminal-task-and-successful-refresh-required',
      'disk-commit-uncertain-survives-reload', 'corrupt-storage-blocks-until-confirmed-clear',
      'storage-write-failure-prevents-post', 'no-credentials-in-journal', 'three-browser-languages-320px'],
    execution: 'Real native HTTP/TaskService with synthetic VM, USB and disk state. Browser routes simulate receipt loss and temporary lookup failure; no real device mutations.' }, null, 2))
  console.log(`PASS Operation recovery after full reload: ${output}`)
} catch (failure) {
  if (activeDiagnostic && !activeDiagnostic.page.isClosed()) {
    const { page, domain, lang, posts, lookups } = activeDiagnostic
    const prefix = `${domain}-${lang}-failure`
    const dom = await page.evaluate(() => [...document.querySelectorAll(
      '.vm-actions [role="status"], .instances .empty-state, .disk-status, .usb-operations, [data-testid="operation-tracking-notice"]',
    )].slice(0, 12).map(element => ({
      tag: element.tagName, class: element.className, state: element.getAttribute('data-state'),
      text: element.textContent.replace(/[a-f0-9]{32,}/gi, '[redacted-id]').replace(/\s+/g, ' ').trim().slice(0, 700),
    }))).catch(() => [])
    writeFileSync(join(output, `${prefix}.json`), JSON.stringify({ domain, language: lang,
      posts: posts.length, lookups: lookups.length, dom }, null, 2))
    await page.screenshot({ path: join(output, `${prefix}.png`), fullPage: true,
      mask: [page.locator('#pairing-token, input[type="password"]')] }).catch(() => {})
    console.error(`Operation recovery diagnostic: ${join(output, prefix)}`)
  }
  throw failure
} finally {
  await browser?.close()
  await fixture?.close()
}
