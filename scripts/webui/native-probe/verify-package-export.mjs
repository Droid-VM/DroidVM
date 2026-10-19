// Real HTTP/session/task/browser boundaries, synthetic export receipts only.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'
import { PackageExportPlan, TaskResponse, VmOperationLookup } from '../../../webui/src/generated/validators.js'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-package-export-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/packages/export/native-http-vm`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.app-shell')).toBeVisible()
  const get = async (path, validator) => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200)
    const result = await response.json()
    if (validator) assert(validator(result), JSON.stringify(validator.errors))
    return result
  }
  const session = await get('session')
  assert(session.server.capabilities.includes('packages.export.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const plan = await get('package-export-plans/native-http-vm', PackageExportPlan)
  const body = { context, requestId: 'package-export-api', vmId: plan.vmId,
    vmRevision: plan.vmRevision, diskRevision: plan.diskRevision, networkRevision: plan.networkRevision,
    operation: { destination: '/fixture/export.vmpkg', compression: 'NONE', volumeBytes: '0', disks: [], overwriteConfirmed: true } }
  const post = async (data, status = 202, headers = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/package-exports`, {
      headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken, ...headers }, data,
    })
    assert.equal(response.status(), status)
    if (status === 202) { const value = await response.json(); assert(TaskResponse(value), JSON.stringify(TaskResponse.errors)) }
  }
  const waitTask = async (id) => {
    let task
    await expect(async () => {
      task = (await get(`package-exports/${id}`, VmOperationLookup)).task
      assert(task && ['succeeded', 'failed', 'cancelled'].includes(task.state))
    }).toPass({ timeout: 8000 })
    return task
  }
  assert.equal(await page.evaluate(async () => (await fetch('/api/v1/package-export-plans/native-http-vm', { credentials: 'omit' })).status), 401)
  await post(body, 403, { 'X-DroidVM-CSRF': '' })
  await post({ ...body, context: { ...context, daemonEpoch: 'stale' } }, 409)
  await post({ ...body, requestId: 'stale-package-export', networkRevision: 'stale' })
  assert.equal((await waitTask('stale-package-export')).state, 'failed')
  await post(body)
  assert.equal((await waitTask(body.requestId)).packageExport.volumeCount, 0)
  await post(body, 409)
  assert.equal((await get('package-exports/never-submitted', VmOperationLookup)).task, null)

  await expect(page.locator('#main').getByRole('heading', { name: '导出虚拟机包', exact: true })).toBeVisible()
  await page.getByLabel('宿主导出目标完整路径', { exact: true }).fill('/fixture/手机导出.vmpkg')
  await page.getByLabel('分卷大小（字节）', { exact: true }).fill('16777216')
  await page.getByRole('button', { name: '不包含磁盘', exact: true }).click()
  await page.getByRole('button', { name: '确认导出参数', exact: true }).click()
  await page.getByRole('checkbox', { name: '我确认以上参数，并允许覆盖目标文件及同名分卷。' }).check()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'package-export-confirm-320.png'), fullPage: true })
  const writes = []
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/package-exports')) writes.push(request.postDataJSON())
  })
  await page.route('**/api/v1/package-exports', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await page.getByRole('button', { name: '开始导出', exact: true }).click()
  await expect(async () => {
    const query = page.locator('.export-status').getByRole('button', { name: '查询当前状态', exact: true })
    if (await query.isVisible()) await query.click()
    await expect(page.locator('.export-status')).toContainText('导出完成：主文件及 2 个 .NNN 分卷。')
  }).toPass({ timeout: 10000 })
  assert.equal(writes.length, 1)
  assert.deepEqual(writes[0].operation.disks, [])
  assert.equal((await waitTask(writes[0].requestId)).packageExport.volumeCount, 2)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'package-export-result-320.png'), fullPage: true })
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['authentication', 'csrf', 'context', 'stale-config', 'empty-disk-selection', 'duplicate-post-conflict',
      'lost-response-query-only', 'split-result', 'no-horizontal-overflow'],
    execution: 'Synthetic export receipts only; no host package files written.' }, null, 2))
  console.log(`PASS Package export HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
