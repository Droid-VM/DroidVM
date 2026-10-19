// Native HTTP and production UI; all process identities/signals are synthetic.
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
const output = mkdtempSync(join(resolve(values.output), 'native-hugepage-processes-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await browser.newPage({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const errors = [], writes = [], checks = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/hugepage-process-operations')) writes.push(request.postDataJSON())
  })
  const ui = key => page.getByTestId(`hugepage-process-${key}`)
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200); return response.json()
  }
  const state = (source = 'auto') => get(`host/hugepage-processes/${source}`)
  const terminal = async id => {
    let task
    await expect(async () => {
      task = (await get(`hugepage-process-operations/${id}`)).task
      assert(task && ['succeeded', 'failed', 'cancelled'].includes(task.state))
    }).toPass({ timeout: 10000 })
    return task
  }
  const refresh = async () => {
    await expect(ui('refresh')).toBeEnabled()
    const response = page.waitForResponse(response => response.url().includes('/api/v1/host/hugepage-processes/') && response.status() === 200)
    await ui('refresh').click(); await response; await expect(ui('refresh')).toBeEnabled()
  }
  const mode = async value => { await fixture.command(`hugepage-process-mode ${value}`); await refresh() }
  const noOverflow = async () => assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
  const screenshot = async name => {
    await noOverflow(); await page.evaluate(() => document.activeElement?.blur())
    await page.screenshot({ path: join(output, `${name}-320.png`), fullPage: true })
  }
  const confirm = async signal => {
    const before = writes.length
    await ui(`${signal}-42420`).click()
    await expect(ui('confirm')).toBeDisabled()
    await ui('confirm-check').check(); await ui('confirm').click()
    await expect(async () => assert.equal(writes.length, before + 1)).toPass()
  }
  await page.goto(`${fixture.origin}/#/hugepages/processes`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByTestId('hugepage-processes-page')).toBeVisible()
  await expect(ui('42420')).toBeVisible(); await expect(ui('term-42421')).toBeDisabled()
  const session = await get('session')
  assert(session.server.capabilities.includes('host.hugepages.processes.v1'))
  const post = async (data, status = 202, headers = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/hugepage-process-operations`, {
      headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken, ...headers },
      data: { context: { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }, ...data },
    })
    assert.equal(response.status(), status); return response.json()
  }
  const initial = await state(), expected = initial.entries[0].expected
  assert(!('private' in initial.entries[0]))
  assert.equal((await fetch(`${fixture.origin}/api/v1/host/hugepage-processes`)).status, 401)
  const guard = { requestId: 'guard', expected, signal: 'term' }
  await post(guard, 403, { 'X-DroidVM-CSRF': '' })
  await post({ ...guard, signal: '9' }, 400)
  await post({ ...guard, context: { daemonEpoch: 'old', sessionId: session.id } }, 409)
  await fixture.command('hugepage-process-touch')
  await post(guard)
  const rejected = await terminal('guard')
  assert.equal(rejected.errorCode, 'CONFLICT')
  assert.equal(rejected.hugepageProcessResult.sent, false)
  assert.equal((await fixture.command('hugepage-process-stats')).effects, 0)
  await refresh(); await screenshot('ko')
  checks.push('auth-csrf-context-and-fixed-signal', 'stale-birth-has-no-effect', 'legacy-owner-cannot-be-signalled', 'private-fields-filtered')

  await ui('source').selectOption('scan')
  await expect(ui('42421')).toHaveCount(0)
  assert.equal((await state('scan')).coverage, 'running-vms')
  await ui('source').selectOption('auto')
  await mode('legacy')
  assert.equal((await state()).entries[0].expected.source, 'scan')
  assert.equal((await state()).entries[0].attributionVerified, false)
  await expect(ui('term-42420')).toBeEnabled()
  checks.push('scan-is-limited-to-vms', 'legacy-attribution-distinct-from-current-vm-identity')

  await mode('d-state')
  await ui('stack-42420').click()
  await expect(ui('stack-text')).toContainText('fixture_wait')
  assert.equal((await fixture.command('hugepage-process-stats')).effects, 0)
  await screenshot('stack')
  await page.keyboard.press('Escape')
  checks.push('d-state-stack-is-read-only')
  await page.route('**/api/v1/hugepage-process-operations', async route => {
    assert.equal((await route.fetch()).status(), 202); await route.abort()
  }, { times: 1 })
  await confirm('term')
  const term = await terminal(writes.at(-1).requestId)
  assert.equal(term.hugepageProcessResult.sent, true)
  assert.equal(term.hugepageProcessResult.exited, false)
  const count = writes.length
  await expect(ui('query')).toBeEnabled(); await ui('query').click()
  await expect(ui('kill-42420')).toBeEnabled()
  assert.equal(writes.length, count)
  checks.push('lost-signal-response-only-queries-original-id', 'sent-signal-is-not-observed-exit')

  await mode('ready')
  await confirm('kill')
  const killed = await terminal(writes.at(-1).requestId)
  assert.equal(killed.hugepageProcessResult.signal, 'kill')
  assert.equal(killed.hugepageProcessResult.exited, true)
  await refresh(); await expect(ui('term-42420')).toBeDisabled()
  checks.push('explicit-kill-uses-new-reviewed-identity', 'exited-process-disables-control')

  await mode('partial')
  assert.equal((await state()).entries[0].bytes, null)
  await expect(ui('partial')).toBeVisible()
  await screenshot('partial')
  await page.route('**/api/v1/host/hugepage-processes/*', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'unavailable' } }) }))
  await ui('refresh').click()
  await expect(ui('stale')).toBeVisible(); await expect(ui('term-42420')).toBeDisabled()
  checks.push('partial-usage-is-not-zero', 'stale-observation-disables-signals', '320px-no-horizontal-overflow')
  assert.deepEqual(errors, [])
  const stats = await fixture.command('hugepage-process-stats')
  assert.equal(stats.effects, writes.length)
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320, checks, posts: writes.length, fixture: stats,
    execution: 'Synthetic process identities and signals through real native HTTP and production UI. Native service contracts run separately; no real KO, process signalling, VM teardown or Android device validation.' }, null, 2))
  console.log(`PASS Hugepage processes HTTP and phone UI: ${output}`)
} finally { await browser?.close(); await fixture.close() }
