// Real HTTP and mobile UI, with synthetic configuration storage/commit outcomes.
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
const output = mkdtempSync(join(resolve(values.output), 'native-configuration-backup-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 }, acceptDownloads: true })
  const page = await browserContext.newPage(), errors = [], writes = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/more`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  for (const key of ['settingsCategoryGeneral', 'settingsCategoryVm', 'settingsCategoryBackup', 'settingsCategoryService'])
    await expect(page.getByRole('heading', { name: labels[key], exact: true })).toBeVisible()
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await noOverflow()
  await page.screenshot({ path: join(output, 'settings-320.png'), fullPage: true })
  await page.getByRole('link', { name: labels.backupTitle, exact: true }).click()
  await expect(page.locator('.backup-panel')).toBeVisible()
  const button = key => page.getByRole('button', { name: labels[key], exact: true })
  const sessionResponse = await page.request.get(`${fixture.origin}/api/v1/session`)
  assert.equal(sessionResponse.status(), 200)
  const session = await sessionResponse.json()
  assert(session.server.capabilities.includes('configuration.backup.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const headers = { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken }
  const post = async (operation, data, status = 200, extra = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/configuration-backup/${operation}`, { headers: { ...headers, ...extra }, data: { context, ...data } })
    assert.equal(response.status(), status)
    return status === 200 ? response.json() : undefined
  }
  await post('begin', { bytes: 3 }, 403, { 'X-DroidVM-CSRF': '' })
  await post('begin', { bytes: 3, context: { ...context, daemonEpoch: 'stale' } }, 409)
  await post('begin', { bytes: 31 * 1024 * 1024 + 1 }, 400)
  const fileUrl = `${fixture.origin}/api/v1/configuration-backup/file`
  assert.equal((await fetch(fileUrl)).status, 401)
  assert.equal((await page.request.head(fileUrl)).status(), 405)
  assert.equal((await page.request.get(fileUrl, { headers: { Range: 'bytes=0-9' } })).status(), 416)
  const waiting = page.waitForEvent('download')
  await page.getByRole('link', { name: labels.backupDownload, exact: true }).click()
  const download = await waiting
  assert.equal(download.suggestedFilename(), 'droidvm-config-backup.json')
  const saved = join(output, download.suggestedFilename())
  await download.saveAs(saved)
  assert(readFileSync(saved, 'utf8').includes('18446744073709551615'))

  page.on('request', request => {
    if (request.method() === 'POST' && request.url().includes('/api/v1/configuration-backup/'))
      writes.push({ path: new URL(request.url()).pathname, body: request.postDataJSON() })
  })
  const prepare = async outcome => {
    const raw = `{"version":1,"fixture_outcome":"${outcome}","vms":[{"id":"native-http-vm","opaque":{"counter":18446744073709551615},"note":"${'备份'.repeat(10000)}"}],"networks":[],"disks":[]}`
    const previewResponse = page.waitForResponse(response => response.url().endsWith('/configuration-backup/preview'))
    await page.locator('input[type=file]').setInputFiles({ name: '配置备份.json', mimeType: 'application/json', buffer: Buffer.from(raw) })
    await button('backupPreview').click()
    const response = await previewResponse
    assert.equal(response.status(), 200)
    const preview = await response.json()
    assert(!JSON.stringify(preview).includes('fixture-backup-proof'))
    assert.equal(preview.categories.vms.count, 1)
    assert.equal(preview.categories.networks.count, 0)
    await expect(page.locator('.preview')).toBeVisible()
    await page.getByLabel(labels.backupConfirm, { exact: true }).check()
  }
  const finish = async text => {
    await expect(async () => {
      if (await button('queryStatus').isVisible()) await button('queryStatus').click()
      await expect(page.locator('.status')).toContainText(text)
    }).toPass({ timeout: 10000 })
    await noOverflow()
  }
  await prepare('success')
  const chunks = writes.filter(row => row.path.endsWith('/append'))
  assert(chunks.length >= 2)
  assert(chunks.every(row => Buffer.from(row.body.data, 'base64').length <= 49152))
  assert(Buffer.concat(chunks.map(row => Buffer.from(row.body.data, 'base64'))).toString('utf8').includes('18446744073709551615'))
  await noOverflow()
  await page.screenshot({ path: join(output, 'backup-preview-320.png'), fullPage: true })
  await page.route('**/api/v1/configuration-backup/commit', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await button('backupCommit').click()
  await finish(labels.backupSucceeded)
  assert.equal(writes.filter(row => row.path.endsWith('/commit')).length, 1)
  assert(writes.some(row => row.path.endsWith('/status')))
  assert.deepEqual(await fixture.command('backup-stats'), { commits: 1, uint64Preserved: true })

  for (const outcome of ['partial', 'uncertain']) {
    await prepare(outcome)
    await button('backupCommit').click()
    await finish(outcome === 'partial' ? 'CONFLICT' : labels.backupRecoveryRequired)
    await expect(page.locator('.recovery')).toBeVisible()
    await expect(page.locator('input[type=file]')).toBeDisabled()
    await expect(button('backupRestart')).toBeDisabled()
    const status = await post('status', { requestId: writes.filter(row => row.path.endsWith('/commit')).at(-1).body.requestId })
    assert(!JSON.stringify(status).includes('fixture-backup-result'))
    assert.deepEqual(status.task.configurationBackup.completed, ['networks'])
    await page.screenshot({ path: join(output, `backup-${outcome}-320.png`), fullPage: true })
    await page.getByLabel(labels.backupRecoveryConfirm, { exact: true }).check()
    await button('backupRestart').click()
    await expect(page.locator('input[type=file]')).toBeEnabled()
  }
  assert.equal((await fixture.command('backup-stats')).commits, 3)
  assert.equal(writes.filter(row => row.path.endsWith('/commit')).length, 3)
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['app-settings-categories', 'csrf-context-input-limit', 'authenticated-download-uint64-preserved',
      'head-range-rejected', '48KiB-chunk-upload', 'preview-private-projection',
      'lost-commit-receipt-query-no-resubmit', 'partial-and-uncertain-results', 'review-before-new-import', 'no-horizontal-overflow'],
    execution: 'Synthetic configuration storage and commit outcomes; Android persistence not executed.' }, null, 2))
  console.log(`PASS Configuration backup HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
