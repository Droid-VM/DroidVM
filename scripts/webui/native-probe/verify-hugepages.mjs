// Native HTTP and production phone UI, with explicitly synthetic module state.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-hugepages-'))
const fixture = await startFixture(values.exe)
let browser
try {
  await fixture.command('hugepage-mode ready')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await context.newPage(), errors = [], writes = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/hugepage-operations')) writes.push(request.postDataJSON())
  })
  await page.goto(`${fixture.origin}/#/hugepages`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByTestId('hugepages-page')).toBeVisible()
  const ui = key => page.getByTestId(`hugepages-${key}`)
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200)
    return response.json()
  }
  const session = await get('session')
  assert(session.server.capabilities.includes('host.hugepages.v1'))
  const post = async (data, status = 202, headers = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/hugepage-operations`, {
      headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken, ...headers },
      data: { context: { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }, ...data },
    })
    assert.equal(response.status(), status)
    return response.json()
  }
  const terminal = async requestId => {
    let task
    await expect(async () => {
      task = (await get(`hugepage-operations/${requestId}`)).task
      assert(task && ['succeeded', 'failed', 'cancelled'].includes(task.state))
    }).toPass({ timeout: 10000 })
    return task
  }
  let snapshot = await get('host/hugepages')
  assert.equal(snapshot.actualPoolPages, '1024')
  assert.equal(snapshot.consumerPages, '512')
  assert(!('settings' in snapshot) && !('controls' in snapshot))
  assert.equal((await fetch(`${fixture.origin}/api/v1/host/hugepages`)).status, 401)
  const guard = { requestId: 'guard', expected: { fingerprint: snapshot.fingerprint }, operation: { kind: 'acquire', mode: 2 } }
  await post(guard, 403, { 'X-DroidVM-CSRF': '' })
  await post({ ...guard, context: { daemonEpoch: 'stale', sessionId: session.id } }, 409)
  await post({ ...guard, operation: { kind: 'shell', command: 'true' } }, 400)
  await fixture.command('hugepage-touch')
  await post(guard)
  assert.equal((await terminal(guard.requestId)).errorCode, 'CONFLICT')
  assert.equal((await fixture.command('hugepage-stats')).effects, 0)
  await ui('refresh').click()
  await expect(ui('refresh')).toBeEnabled()
  await noOverflow()
  await page.screenshot({ path: join(output, 'hugepages-ready-320.png'), fullPage: true })

  await ui('tab-reserve').click()
  await ui('pool-target').fill('1536')
  await ui('cma-target').fill('2048')
  await ui('save').click()
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click()
  await expect(async () => assert.equal((await get('host/hugepages')).actualPoolPages, '1536')).toPass()
  await expect(ui('receipt')).toBeVisible()
  assert.equal((await terminal(writes.at(-1).requestId)).hugepageResult.saved, true)

  // Lose only the response after real admission: the UI must query this ID.
  await page.route('**/api/v1/hugepage-operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await ui('acquire-2').click()
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click()
  await expect(async () => assert.equal((await get('host/hugepages')).acquireActive, true)).toPass()
  const count = writes.length
  await ui('query').click()
  await expect(ui('receipt')).toBeVisible()
  assert.equal(writes.length, count)
  assert.equal((await terminal(writes.at(-1).requestId)).state, 'succeeded')
  snapshot = await get('host/hugepages')
  assert.equal(snapshot.acquireActive, true) // Task completion is not pool completion.
  await noOverflow()
  await page.screenshot({ path: join(output, 'hugepages-acquiring-320.png'), fullPage: true })
  await ui('stop').click()
  await page.getByRole('dialog').getByRole('button', { name: '确认', exact: true }).click()
  await expect(async () => assert.equal((await get('host/hugepages')).acquireActive, false)).toPass()

  await page.route('**/api/v1/host/hugepages', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'unavailable' } }) }))
  await ui('refresh').click()
  await expect(ui('stale')).toBeVisible()
  await expect(ui('save')).toBeDisabled()
  await page.unroute('**/api/v1/host/hugepages')
  await ui('refresh').click()
  await expect(ui('stale')).toHaveCount(0)
  await fixture.command('hugepage-mode unknown')
  await ui('refresh').click()
  assert.equal((await get('host/hugepages')).poolAvailablePages, null)
  await noOverflow()
  await page.screenshot({ path: join(output, 'hugepages-unknown-320.png'), fullPage: true })
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['http-auth-csrf-context', 'http-action-allowlist', 'stale-observation-no-effect',
      'targets-persist-and-readback', 'lost-response-query-without-resubmit', 'acquire-trigger-is-not-completion',
      'explicit-stop', 'stale-disables-mutations', 'unknown-not-zero', 'no-horizontal-overflow'],
    posts: writes.length, fixture: await fixture.command('hugepage-stats'),
    execution: 'Synthetic module state through real native HTTP and production UI. Shared native coordinator contracts run separately. No Android module, boot, VM, or device deployment validation.' }, null, 2))
  console.log(`PASS Hugepages HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
