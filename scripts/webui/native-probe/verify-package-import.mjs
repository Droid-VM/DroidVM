// Real HTTP/session/task/browser boundaries, synthetic import receipts only.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'
import { TaskResponse, VmOperationLookup } from '../../../webui/src/generated/validators.js'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const labels = JSON.parse(readFileSync(new URL('../../../webui/src/shared/i18n/locales/zh-Hans.json', import.meta.url), 'utf8'))
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-package-import-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = []
  page.setDefaultTimeout(8000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/packages/import`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.app-shell')).toBeVisible()
  const button = key => page.getByRole('button', { name: labels[key], exact: true })
  const field = key => page.getByLabel(labels[key])
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200)
    return response.json()
  }
  const session = await get('session')
  assert(session.server.capabilities.includes('packages.import.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const post = async (path, data, status = 202, headers = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/${path}`, {
      headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken, ...headers }, data: { context, ...data },
    })
    assert.equal(response.status(), status)
    if (status === 202) { const value = await response.json(); assert(TaskResponse(value), JSON.stringify(TaskResponse.errors)) }
  }
  const waitTask = async (path, id) => {
    let task
    await expect(async () => {
      const value = await get(`${path}/${id}`)
      assert(VmOperationLookup(value), JSON.stringify(VmOperationLookup.errors))
      task = value.task
      assert(task && ['succeeded', 'failed', 'cancelled'].includes(task.state))
    }).toPass({ timeout: 8000 })
    return task
  }
  const preview = { requestId: 'package-preview-api', source: '/fixture/import.vmpkg.001' }
  await post('package-import-plans', preview, 403, { 'X-DroidVM-CSRF': '' })
  await post('package-import-plans', { ...preview, context: { ...context, daemonEpoch: 'stale' } }, 409)
  await post('package-import-plans', preview)
  const planned = await waitTask('package-import-plans', preview.requestId)
  assert.equal(planned.packageImportPlan.source, '/fixture/import.vmpkg')
  assert(!JSON.stringify(planned).includes('fixture-internal-source-observation'))
  await post('package-import-plans', preview, 409)
  const request = { requestId: 'package-import-api', planRequestId: preview.requestId,
    operation: { targetDirectory: '/fixture/imported', networks: [{ ref: 'network-0', action: 'join', networkId: 'fixture-existing-network' }], confirmed: true } }
  await post('package-imports', request)
  assert.equal((await waitTask('package-imports', request.requestId)).packageImport.registered, true)
  await post('package-imports', request, 409)

  const writes = []
  page.on('request', request => {
    if (request.method() === 'POST' && /\/api\/v1\/package-import(?:s|-plans)$/.test(request.url()))
      writes.push({ path: new URL(request.url()).pathname, body: request.postDataJSON() })
  })
  const loseOnce = path => page.route(`**/api/v1/${path}`, async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  const settle = async check => expect(async () => {
    const queries = page.getByRole('button', { name: labels.queryStatus, exact: true })
    for (const query of await queries.all()) if (await query.isEnabled()) await query.click()
    await check()
  }).toPass({ timeout: 10000 })
  await field('packageImportSource').fill('/fixture/手机导入.vmpkg.001')
  await loseOnce('package-import-plans')
  await button('packageImportReadPreview').click()
  await settle(() => expect(page.locator('.summary')).toContainText('/fixture/手机导入.vmpkg'))
  assert.equal(writes.length, 1)
  await expect(page.locator('.summary')).toContainText('包内备注 <script>仅作为文本显示</script>')
  await field('packageImportTarget').fill('/fixture/imported')
  await field('packageImportNetworkAction').selectOption('create')
  await button('packageImportReview').click()
  await field('packageImportConfirm').check()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'package-import-confirm-320.png'), fullPage: true })
  await loseOnce('package-imports')
  await button('packageImportStart').click()
  await settle(() => expect(page.getByRole('link', { name: labels.packageImportOpenVm, exact: true })).toBeVisible())
  assert.equal(writes.length, 2)
  assert.equal(writes[1].body.operation.networks[0].action, 'create')

  await button('packageImportReadPreview').click()
  await settle(() => expect(page.locator('.summary')).toBeVisible())
  await field('packageImportTarget').fill('/fixture/uncertain')
  await field('packageImportNetworkAction').selectOption('skip')
  await button('packageImportReview').click()
  await field('packageImportConfirm').check()
  await button('packageImportStart').click()
  await settle(() => expect(page.locator('.import-form')).toContainText('/fixture/uncertain/retained-vm'))
  assert.equal(writes.length, 4)
  assert.equal(await page.getByRole('link', { name: labels.packageImportOpenVm, exact: true }).count(), 0)
  await expect(button('packageImportNewFlow')).toBeDisabled()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'package-import-retained-320.png'), fullPage: true })
  await field('packageImportRecoveryConfirm').check()
  await button('packageImportNewFlow').click()
  await expect(button('packageImportReadPreview')).toBeEnabled()
  assert.equal(writes.length, 4) // Acknowledging a new flow never replays an import.
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['csrf', 'context', 'master-path-normalization', 'private-plan-projection', 'duplicate-post-conflict',
      'preview-and-import-lost-response-query-only', 'network-create-and-skip', 'plain-text-notes',
      'uncertain-registration-retained-files', 'explicit-new-flow-no-replay', 'no-horizontal-overflow'],
    execution: 'Synthetic import receipts only; no unpacking or host configuration writes.' }, null, 2))
  console.log(`PASS Package import HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
