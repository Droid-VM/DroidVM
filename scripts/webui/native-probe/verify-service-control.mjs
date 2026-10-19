// Actual WebService, listeners, sessions and task workers; persistence/discovery are injected.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture, freePort } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const labels = JSON.parse(readFileSync(new URL('../../../webui/src/shared/i18n/locales/zh-Hans.json', import.meta.url), 'utf8'))
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-service-control-'))
const fixture = await startFixture(values.exe, undefined, { extraArgs: ['--enabled', '--ipv4'] })
const control = value => fixture.command(JSON.stringify(value))
const ipc = (command, request = {}, identity) => control({ op: 'ipc', command, request, identity })
let browser, origin = fixture.origin
try {
  await control({ op: 'tick' })
  await expect(async () => assert.equal((await ipc('web_status')).running, true)).toPass({ timeout: 10000 })
  assert.equal((await ipc('web_status', {}, 'browser')).errorCode, 'PERMISSION_DENIED')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = [], writes = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('dialog', async dialog => {
    assert.equal(dialog.type(), 'beforeunload')
    await dialog.accept()
  })
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/web-service/operations'))
      writes.push(request.postDataJSON())
  })
  const button = key => page.getByRole('button', { name: labels[key], exact: true })
  const field = key => page.getByLabel(labels[key], { exact: true })
  const login = async () => {
    await page.goto(`${origin}/#/settings/service`)
    await page.locator('#pairing-token').fill((await ipc('web_pair')).pairingToken)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await expect(page.locator('.service-panel')).toBeVisible()
  }
  await login()
  const get = async path => {
    const response = await page.request.get(`${origin}/api/v1/${path}`)
    assert.equal(response.status(), 200)
    return response.json()
  }
  let session = await get('session')
  assert(session.server.capabilities.includes('web.service.v1'))
  const post = async (data, status = 202, options = {}) => {
    const response = await page.request.post(`${origin}/api/v1/web-service/operations`, {
      headers: { Origin: origin, 'X-DroidVM-CSRF': session.csrfToken, ...options.headers },
      data: { context: { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }, ...data },
    })
    assert.equal(response.status(), status)
    return response.json()
  }
  const task = async requestId => {
    let result
    await expect(async () => {
      result = (await get(`web-service/operations/${requestId}`)).task
      assert(result && ['succeeded', 'failed', 'cancelled'].includes(result.state))
    }).toPass({ timeout: 10000 })
    return result
  }
  const apply = async () => {
    // Real one-second grace; the fixture deliberately has no automatic maintenance timer.
    await new Promise(resolve => setTimeout(resolve, 1100))
    await control({ op: 'tick' })
    await expect(async () => assert.equal((await ipc('web_status')).applyPending, false)).toPass({ timeout: 10000 })
  }
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  let status = await get('web-service')
  assert(!('sessions' in status))
  const guard = { requestId: 'guard', action: 'restart', expected: status.settings, settings: status.settings }
  await post(guard, 403, { headers: { 'X-DroidVM-CSRF': '' } })
  await post({ ...guard, context: { daemonEpoch: 'stale', sessionId: session.id } }, 409)
  await post({ ...guard, settings: { ...status.settings, port: 80 } }, 400)
  await post({ ...guard, settings: { ...status.settings, publicOrigins: ['https://example.test'] } }, 400)
  assert.equal((await fetch(`${origin}/api/v1/web-service`)).status, 401)

  await expect(field('webServicePort')).toHaveValue(String(status.settings.port))
  await field('webServiceOrigins').fill('http://example.test:8080')
  await button('webServiceReview').click()
  await field('webServiceConfirm').check()
  await noOverflow()
  await page.screenshot({ path: join(output, 'service-confirm-320.png'), fullPage: true })
  await page.route('**/api/v1/web-service/operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await button('webServiceSave').click()
  await expect(button('queryStatus')).toBeVisible()
  await button('queryStatus').click()
  const submitted = writes[0]
  assert.equal((await task(submitted.requestId)).state, 'succeeded')
  assert.equal(writes.length, 1)
  status = await get('web-service')
  assert.equal(status.applyPending, true)
  assert(!status.addresses.includes('http://example.test:8080'))
  assert.equal((await control({ op: 'stats' })).saves, 1)
  // Same owner/signature reuses the original task; conflicting reuse is rejected.
  await post(submitted)
  await post({ ...submitted, settings: { ...submitted.settings, port: submitted.settings.port === 1024 ? 1025 : 1024 } }, 409)
  assert.equal((await control({ op: 'stats' })).saves, 1)
  await apply()
  await button('webServiceVerify').click()
  await expect(page.locator('.service-panel')).toContainText(labels.webServiceApplied)
  assert((await get('web-service')).addresses.includes('http://example.test:8080'))
  await noOverflow()
  await page.screenshot({ path: join(output, 'service-applied-320.png'), fullPage: true })

  await button('webServiceRestartReview').click()
  await field('webServiceConfirm').check()
  await button('webServiceRestart').click()
  await task(writes.at(-1).requestId)
  await apply()
  await button('webServiceVerify').click()
  await expect(page.locator('.service-panel')).toContainText(labels.webServiceApplied)
  assert.equal((await control({ op: 'stats' })).saves, 1)
  assert.equal((await get('session')).id, session.id)

  // Change the actual listening port and reconnect with the same host cookie.
  status = await get('web-service')
  const moved = { ...status.settings, port: await freePort() }
  await post({ requestId: 'move-port', action: 'configure', expected: status.settings, settings: moved })
  await task('move-port')
  assert.equal((await get('web-service')).addresses[0], origin)
  await apply()
  const oldOrigin = origin
  origin = `http://127.0.0.1:${moved.port}`
  await assert.rejects(fetch(`${oldOrigin}/api/v1/session`))
  await page.goto(`${origin}/#/settings/service`)
  await expect(field('webServicePort')).toHaveValue(String(moved.port))
  session = await get('session')
  await field('webServiceEnabled').uncheck()
  await button('webServiceReview').click()
  await field('webServiceConfirm').check()
  await button('webServiceSave').click()
  await task(writes.at(-1).requestId)
  await apply()
  status = await ipc('web_status')
  assert.equal(status.running, false)
  assert.equal(status.state, 'disabled')
  await assert.rejects(fetch(`${origin}/api/v1/session`))
  await ipc('web_configure', { expected: status.settings, settings: { ...status.settings, enabled: true } })
  await login()
  session = await get('session')

  // A save that changed storage but failed its confirmation closes on maintenance;
  // only local App reload resolves the uncertain persistent configuration.
  status = await get('web-service')
  await control({ op: 'save', mode: 2 })
  const uncertainSettings = { ...status.settings, publicOrigins: ['http://recovered.test:8080'] }
  await post({ requestId: 'uncertain-save', action: 'configure', expected: status.settings, settings: uncertainSettings })
  assert.equal((await task('uncertain-save')).errorCode, 'COMMIT_UNCERTAIN')
  assert.equal((await get('web-service')).error, 'configuration_write_failed')
  await apply()
  assert.equal((await ipc('web_status')).running, false)
  await control({ op: 'save', mode: 0 })
  const recovered = await ipc('web_reload')
  assert.deepEqual(recovered.settings, uncertainSettings)
  assert.equal(recovered.running, true)
  assert.equal((await page.request.get(`${origin}/api/v1/session`)).status(), 401)
  assert.deepEqual(errors, [])
  assert.equal((await control({ op: 'stop' })).workersAlive, true)
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['local-ipc-identity-still-required', 'http-auth-csrf-context', 'settings-validation',
      'lost-receipt-query-no-replay', 'task-deduplication', 'pending-vs-active-addresses',
      'delayed-listener-apply', 'restart-preserves-session', 'actual-port-move',
      'disable-requires-local-reenable', 'uncertain-save-local-recovery', 'shared-workers-survive', 'no-horizontal-overflow'],
    execution: 'Real WebService and loopback listener replacement; injected storage/discovery, no Android deployment.' }, null, 2))
  console.log(`PASS Web service control and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
