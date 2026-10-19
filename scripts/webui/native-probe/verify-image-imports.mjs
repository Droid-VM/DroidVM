// Real HTTP/session/tasks and phone UI; synthetic image observations and registration.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const labels = JSON.parse(readFileSync(new URL('../../../webui/src/shared/i18n/locales/zh-Hans.json', import.meta.url), 'utf8'))
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-image-imports-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = [], writes = [], reads = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/resources/disk`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('link', { name: labels.imageImportTitle, exact: true }).click()
  const ui = key => page.getByTestId(`image-import-${key}`)
  await expect(ui('source')).toBeVisible()
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const sessionResponse = await page.request.get(`${fixture.origin}/api/v1/session`)
  assert.equal(sessionResponse.status(), 200)
  const session = await sessionResponse.json()
  assert(session.server.capabilities.includes('images.import.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const headers = { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken }
  const reject = async (path, data, status, extra = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/${path}`, {
      headers: { ...headers, ...extra }, data: { context, ...data },
    })
    assert.equal(response.status(), status)
  }
  const request = { requestId: 'probe-invalid-image', source: '/fixture/imported.qcow2' }
  await reject('image-import-plans', request, 403, { 'X-DroidVM-CSRF': '' })
  await reject('image-import-plans', { ...request, context: { ...context, daemonEpoch: 'stale' } }, 409)
  await reject('image-import-plans', { ...request, source: 'relative.qcow2' }, 400)
  await reject('image-imports', { requestId: 'probe-injected-image', planRequestId: 'unknown', source: request.source }, 400)
  assert.equal((await fetch(`${fixture.origin}/api/v1/image-imports/unknown`)).status, 401)
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/v1/image-import')) return
    if (request.method() === 'POST') writes.push({ path, body: request.postDataJSON() })
    if (request.method() === 'GET') reads.push(path)
  })
  const finish = async text => {
    await expect(async () => {
      if (await ui('reconcile').isEnabled()) await ui('reconcile').click()
      await expect(ui('result')).toContainText(text)
    }).toPass({ timeout: 10000 })
    await noOverflow()
  }
  const preview = async source => {
    await ui('source').fill(source)
    await ui('preview').click()
    await expect(async () => {
      if (await ui('reconcile').isEnabled()) await ui('reconcile').click()
      await expect(page.locator('.chain li')).toHaveCount(3)
    }).toPass({ timeout: 10000 })
    const requestId = writes.filter(row => row.path.endsWith('/image-import-plans')).at(-1).body.requestId
    const response = await page.request.get(`${fixture.origin}/api/v1/image-import-plans/${requestId}`)
    assert.equal(response.status(), 200)
    const payload = await response.json()
    assert(!JSON.stringify(payload).includes('fixture-image-private-proof'))
    assert(!JSON.stringify(payload).includes('_files'))
    assert.equal(payload.task.imageImportPlan.members[0].virtualSizeBytes, '9007199254740993')
    await expect(page.locator('.chain li').first()).toContainText('9007199254740993')
    await expect(page.locator('.attachment')).toContainText(labels.imageImportReadOnly)
    await expect(page.locator('.attachment')).toContainText(labels.imageImportWritable)
    await expect(ui('submit')).toBeDisabled()
    await ui('acknowledge').check()
    await noOverflow()
    return payload.task.imageImportPlan
  }
  const plan = await preview('/fixture/imported.qcow2')
  await page.screenshot({ path: join(output, 'image-preview-320.png'), fullPage: true })
  await page.route('**/api/v1/image-imports', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await ui('submit').click()
  await finish(labels.imageImportSuccess)
  assert.equal(writes.filter(row => row.path.endsWith('/image-imports')).length, 1)
  assert(reads.some(path => path.startsWith('/api/v1/image-imports/')))
  assert.deepEqual(await fixture.command('image-import-stats'), { plans: 1, commits: 1 })
  const commit = writes.filter(row => row.path.endsWith('/image-imports')).at(-1).body
  assert.deepEqual(Object.keys(commit).sort(), ['context', 'planRequestId', 'requestId'])
  const receipt = await (await page.request.get(`${fixture.origin}/api/v1/image-imports/${commit.requestId}`)).json()
  assert(!JSON.stringify(receipt).includes('fixture-image-private-result'))
  assert.equal(receipt.task.imageImport.added, 2)
  await page.screenshot({ path: join(output, 'image-result-320.png'), fullPage: true })
  await expect(page.getByRole('link', { name: labels.createVm, exact: true })).toHaveAttribute('href', '#/vms/new')
  await page.getByRole('link', { name: labels.diskViewResult, exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`disk=${plan.diskId}`))
  await expect(page.getByRole('dialog')).toContainText('imported.qcow2')
  const catalog = await (await page.request.get(`${fixture.origin}/api/v1/disks`)).json()
  assert(JSON.stringify(catalog).includes(plan.diskId))
  assert(JSON.stringify(catalog).includes('import-parent.qcow2'))
  await page.goBack()
  await expect(ui('result')).toContainText(labels.imageImportSuccess)
  await expect(ui('restart')).toBeDisabled()
  await page.getByLabel(labels.imageImportOutcomeConfirm, { exact: true }).check()
  await ui('restart').click()
  await preview('/fixture/uncertain.qcow2')
  await ui('submit').click()
  await finish(labels.imageImportRecovery)
  await expect(ui('source')).toBeDisabled()
  await expect(ui('restart')).toBeDisabled()
  await expect(ui('submit')).toHaveCount(0)
  await page.screenshot({ path: join(output, 'image-uncertain-320.png'), fullPage: true })
  await page.getByLabel(labels.imageImportOutcomeConfirm, { exact: true }).check()
  await ui('restart').click()
  await expect(ui('source')).toBeEnabled()
  assert.deepEqual(await fixture.command('image-import-stats'), { plans: 2, commits: 2 })
  assert.equal(writes.filter(row => row.path.endsWith('/image-imports')).length, 2)
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['disk-catalog-entry', 'csrf-context-path-and-plan-only-request', 'complete-chain-and-readonly-preview',
      'exact-integer-capacity', 'private-plan-and-receipt-projection', 'lost-receipt-query-no-resubmit',
      'catalog-refresh-and-result-disk-link', 'existing-vm-create-link', 'uncertain-review-before-new-flow', 'no-horizontal-overflow'],
    execution: 'Synthetic image observations and registration; no Android files or qemu-img execution.' }, null, 2))
  console.log(`PASS Image imports HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
