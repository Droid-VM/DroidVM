// Real HTTP/session/task/browser boundaries; fixed Agent receipts, no guest writes.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'
import { TaskResponse, VmOperationLookup, AgentRecoveryStatus } from '../../../webui/src/generated/validators.js'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: {
  exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' },
  'resize-grow': { type: 'boolean', default: false },
  rescue: { type: 'boolean', default: false },
} })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-agent-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/resources/disk`)
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
  for (const capability of ['agent.operations.v1', 'agent.recovery.v1'])
    assert(session.server.capabilities.includes(capability))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const post = async (path, body, status = 202, overrides = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/${path}`, {
      headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken, ...overrides },
      data: { context, ...body },
    })
    assert.equal(response.status(), status)
    const result = await response.json()
    if (status === 202) assert(TaskResponse(result), JSON.stringify(TaskResponse.errors))
    return result
  }
  const waitTask = async (path, id) => {
    let task
    await expect(async () => {
      task = (await get(`${path}/${id}`, VmOperationLookup)).task
      assert(task && ['succeeded', 'failed', 'cancelled'].includes(task.state))
    }).toPass({ timeout: 8000 })
    return task
  }
  assert.equal(await page.evaluate(async () => (await fetch('/api/v1/agent-operations/no-session', { credentials: 'omit' })).status), 401)
  const created = await post('disk-operations', {
    requestId: 'agent-fixture-disk', revision: 'fixture-disk-revision', vmRevision: 'fixture-reference-revision',
    operation: { action: 'create', folder: '/fixture', name: 'agent.qcow2', format: 'qcow2', size: '8589934592' },
  })
  assert.equal(created.task.kind, 'disk_operation')
  const diskId = (await waitTask('disk-operations', 'agent-fixture-disk')).diskId
  assert(diskId)
  if (values.rescue) {
    const { verifyRescue } = await import('./agent-rescue.mjs')
    await verifyRescue({ page, expect, post, get, waitTask, diskId, output, errors, fixture })
  } else if (values['resize-grow']) {
    const { verifyResizeGrow } = await import('./agent-resize.mjs')
    await verifyResizeGrow({ page, expect, post, get, waitTask, diskId, output, errors })
  } else {
  const operation = {
    requestId: 'agent-api-once', revision: 'fixture-disk-revision', vmRevision: 'fixture-reference-revision',
    operation: { action: 'chpasswd', diskId, password: 'Fixture123!', normalUsers: false },
  }
  await post('agent-operations', operation, 403, { 'X-DroidVM-CSRF': '' })
  await post('agent-operations', { ...operation, context: { ...context, daemonEpoch: 'stale' } }, 409)
  await post('agent-operations', operation)
  const complete = await waitTask('agent-operations', operation.requestId)
  assert.equal(complete.state, 'succeeded')
  assert.equal(complete.agentResult.durabilityConfirmed, true)
  assert(!JSON.stringify(complete).includes(operation.operation.password))
  await post('agent-operations', operation, 409)
  assert.equal((await get('agent-operations/never-submitted', VmOperationLookup)).task, null)
  assert.equal((await get('agent-recovery', AgentRecoveryStatus)).pendingRecords, 0)

  // UI scenarios are intentionally limited to this new feature at phone width.
  await page.reload()
  const card = page.locator('article').filter({ has: page.getByRole('heading', { name: 'agent.qcow2', exact: true }) })
  await expect(card).toBeVisible()
  const openDetails = () => card.getByRole('button', { name: '磁盘详情', exact: true }).click()
  const writes = []
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/agent-operations'))
      writes.push({ requestId: request.postDataJSON().requestId, action: request.postDataJSON().operation.action })
  })
  await openDetails()
  await page.getByRole('dialog').getByRole('button', { name: '修改系统密码', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('新密码', { exact: true }).fill('Browser123!')
  await dialog.getByLabel('确认新密码', { exact: true }).fill('Browser123!')
  await dialog.getByRole('checkbox').last().check()
  await page.screenshot({ path: join(output, 'agent-password-320.png') })
  await page.route('**/api/v1/agent-operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await page.getByTestId('agent-submit').click()
  await expect(page.locator('.agent-status')).toBeVisible()
  await expect(async () => {
    const query = page.locator('.agent-status').getByRole('button', { name: '查询当前状态', exact: true })
    if (await query.isVisible()) await query.click()
    await expect(page.locator('.agent-status')).toContainText('已完成')
  }).toPass({ timeout: 10000 })
  assert.equal(writes.length, 1)
  assert.equal(await page.locator('input[type=password]').count(), 0)
  assert.equal(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }).includes('Browser123!')), false)
  await openDetails()
  await page.getByRole('dialog').getByRole('button', { name: '扩展分区与文件系统', exact: true }).click()
  await dialog.getByRole('checkbox').last().check()
  await page.getByTestId('agent-submit').click()
  await expect(async () => {
    const query = page.locator('.agent-status').getByRole('button', { name: '查询当前状态', exact: true })
    if (await query.isVisible()) await query.click()
    assert.equal((await waitTask('agent-operations', writes[1].requestId)).agentResult.outcome, 'uncertain')
  }).toPass({ timeout: 10000 })
  assert.equal(writes.length, 2)
  const recovery = page.locator('.agent-recovery')
  await recovery.getByRole('button', { name: '查询当前状态', exact: true }).click()
  await recovery.getByRole('button', { name: '清理遗留资源', exact: true }).click()
  await dialog.getByRole('button', { name: '清理遗留资源', exact: true }).click()
  await expect(async () => {
    await recovery.getByRole('button', { name: '查询当前状态', exact: true }).click()
    assert.equal((await get('agent-recovery', AgentRecoveryStatus)).pendingRecords, 0)
  }).toPass({ timeout: 10000 })
  // Cleanup does not turn the original unknown action into a success.
  assert.equal((await waitTask('agent-operations', writes[1].requestId)).agentResult.outcome, 'uncertain')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'agent-recovery-320.png'), fullPage: true })
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['authentication', 'csrf', 'context', 'structured-results', 'duplicate-post-conflict',
      'lost-response-query-only', 'password-form-cleared', 'uncertain-cleanup', 'explicit-recovery', 'no-horizontal-overflow'],
    execution: 'Synthetic receipts only; no guest password or disk mutation.' }, null, 2))
  console.log(`PASS Agent HTTP and phone UI: ${output}`)
  }
} finally {
  await browser?.close()
  await fixture.close()
}
