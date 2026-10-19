// Actual HTTP, bounded streaming and phone UI; synthetic package/storage source.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { get as httpGet } from 'node:http'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const labels = JSON.parse(readFileSync(new URL('../../../webui/src/shared/i18n/locales/zh-Hans.json', import.meta.url), 'utf8'))
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-package-transfers-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 }, acceptDownloads: true })
  const page = await browserContext.newPage(), errors = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/packages/transfers`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.transfers')).toBeVisible()
  const button = key => page.getByRole('button', { name: labels[key], exact: true })
  const field = key => page.getByLabel(labels[key])
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200)
    return response.json()
  }
  const session = await get('session')
  assert(session.server.capabilities.includes('packages.transfer.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const headers = { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken }
  const post = async (path, data, status = 200, extra = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/${path}`, { headers: { ...headers, ...extra }, data: { context, ...data } })
    assert.equal(response.status(), status)
    return status === 200 ? response.json() : undefined
  }
  const begin = { requestId: 'guard-upload', files: [{ name: 'guard.vmpkg', sizeBytes: '3' }] }
  await post('package-uploads', begin, 403, { 'X-DroidVM-CSRF': '' })
  await post('package-uploads', { ...begin, context: { ...context, daemonEpoch: 'stale' } }, 409)
  await post('package-uploads', { ...begin, files: [{ name: '../guard.vmpkg', sizeBytes: '3' }] }, 400)
  const opened = await post('package-uploads', begin)
  assert.equal(opened.transfer.limits.chunkBytes, 262144)
  assert(!JSON.stringify(opened).includes('fixture-transfer-private'))
  await post('package-uploads', begin, 409)
  await post('package-uploads/guard-upload/chunks', { index: 0, offset: '1', data: 'YWJj' }, 409)
  await post('package-uploads/guard-upload/chunks', { index: 0, offset: '0', data: 'YWJj' })
  await post('package-uploads/guard-upload/chunks', { index: 0, offset: '0', data: 'YWJj' }, 409)
  await post('package-uploads/guard-upload/finish', {})
  assert.equal((await page.request.delete(`${fixture.origin}/api/v1/package-uploads/guard-upload`, { headers })).status(), 204)

  const main = Buffer.alloc(600003)
  for (let i = 0; i < main.length; i++) main[i] = i % 251
  const volume = Buffer.from('浏览器分卷内容')
  await page.locator('input[type=file]').setInputFiles([
    { name: '手机上传.vmpkg', mimeType: 'application/octet-stream', buffer: main },
    { name: '手机上传.vmpkg.001', mimeType: 'application/octet-stream', buffer: volume },
  ])
  const writes = []
  page.on('request', request => {
    if (request.method() === 'POST' && /\/api\/v1\/package-(uploads|downloads)/.test(request.url()))
      writes.push({ path: new URL(request.url()).pathname, body: request.postDataJSON() })
  })
  await page.route('**/api/v1/package-uploads/*/chunks', async route => {
    assert.equal((await route.fetch()).status(), 200)
    await route.abort()
  }, { times: 1 })
  await button('transferStart').click()
  await expect(button('queryStatus')).toBeVisible()
  assert.equal(writes.filter(row => row.path.endsWith('/chunks')).length, 1)
  await button('queryStatus').click()
  await expect(button('transferResume')).toBeVisible()
  assert.equal(writes.filter(row => row.path.endsWith('/chunks')).length, 1)
  await button('transferResume').click()
  await expect(button('transferImport')).toBeEnabled()
  const chunks = writes.filter(row => row.path.endsWith('/chunks'))
  assert.deepEqual(chunks.map(row => [row.body.index, row.body.offset]), [[0, '0'], [0, '262144'], [0, '524288'], [1, '0']])
  assert(chunks.every(row => Buffer.from(row.body.data, 'base64').length <= 262144))
  const stats = await fixture.command('transfer-stats')
  assert.equal(stats.uploadBytes, String(3 + main.length + volume.length))
  let hash = 2166136261
  for (const byte of Buffer.concat([Buffer.from('abc'), main, volume])) hash = Math.imul(hash ^ byte, 16777619) >>> 0
  assert.equal(stats.uploadHash, hash)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'package-upload-320.png'), fullPage: true })
  await button('transferImport').click()
  await expect(field('packageImportSource')).toHaveValue('/fixture/upload/手机上传.vmpkg')
  await button('packageImportReadPreview').click()
  await expect(async () => {
    if (await button('queryStatus').isVisible()) await button('queryStatus').click()
    await expect(page.locator('.summary')).toContainText('/fixture/upload/手机上传.vmpkg')
  }).toPass({ timeout: 10000 })

  await page.evaluate(() => { location.hash = '/packages/export/native-http-vm' })
  await field('packageExportDestination').fill('/fixture/浏览器导出.vmpkg')
  await field('packageExportVolume').fill('16777216')
  await button('packageExportNoDisks').click()
  await button('packageExportReview').click()
  await field('packageExportOverwriteConfirm').check()
  await button('packageExportStart').click()
  await expect(async () => {
    if (await button('queryStatus').isVisible()) await button('queryStatus').click()
    await expect(button('transferPrepareDownload')).toBeEnabled()
  }).toPass({ timeout: 10000 })
  await page.route('**/api/v1/package-downloads', async route => {
    assert.equal((await route.fetch()).status(), 200)
    await route.abort()
  }, { times: 1 })
  await button('transferPrepareDownload').click()
  await expect(page.locator('.transfers')).toBeVisible()
  await button('queryStatus').click()
  const link = page.getByRole('link', { name: '浏览器导出.vmpkg', exact: true })
  await expect(link).toBeVisible()
  assert.equal(writes.filter(row => row.path.endsWith('/package-downloads')).length, 1)
  const downloadPath = await link.getAttribute('href')
  const downloadId = downloadPath.split('/')[4]
  assert.equal((await fetch(`${fixture.origin}${downloadPath}`)).status, 401)
  assert.equal((await page.request.get(`${fixture.origin}${downloadPath}`, { headers: { Range: 'bytes=0-9' } })).status(), 416)
  assert.equal((await page.request.head(`${fixture.origin}${downloadPath}`)).status(), 405)
  const waiting = page.waitForEvent('download')
  await link.click()
  const download = await waiting
  assert.equal(download.suggestedFilename(), '浏览器导出.vmpkg')
  const saved = join(output, download.suggestedFilename())
  await download.saveAs(saved)
  const data = readFileSync(saved)
  assert.equal(data.length, (8 << 20) + 7)
  for (let i = 0; i < data.length; i++) assert.equal(data[i], i % 251)
  const downloaded = await fixture.command('transfer-stats')
  assert.equal(downloaded.maxReadBytes, 262144)
  assert(downloaded.readCalls > 32)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'package-download-320.png'), fullPage: true })

  // A different paired owner cannot reuse the transfer ID, even with its own CSRF.
  const second = await browser.newContext()
  const paired = await second.request.post(`${fixture.origin}/api/v1/session`, { headers: { Origin: fixture.origin }, data: { pairingToken: (await fixture.command('pair')).pairingToken } })
  assert.equal(paired.status(), 201)
  assert.equal((await second.request.get(`${fixture.origin}/api/v1/package-downloads/${downloadId}`)).status(), 404)
  await second.close()
  // Revocation interrupts later reads of an in-flight file, never appends JSON.
  const cookies = await browserContext.cookies(fixture.origin)
  let revoked = false, observed = 0, revokeWork
  await new Promise((resolveDownload, reject) => {
    const request = httpGet(`${fixture.origin}${downloadPath}`, { headers: { Cookie: cookies.map(cookie => `${cookie.name}=${cookie.value}`).join('; ') } }, response => {
      assert.equal(response.statusCode, 200)
      response.on('data', chunk => {
        observed += chunk.length
        if (!revoked) { revoked = true; revokeWork = fixture.command(`revoke ${session.id}`) }
      })
      response.on('end', resolveDownload)
      response.on('error', resolveDownload)
    })
    request.on('error', reject)
    request.setTimeout(10000, () => request.destroy(new Error('Revoked stream did not close')))
  })
  await revokeWork
  assert(revoked && observed > 0 && observed < data.length)
  assert.equal((await page.request.get(`${fixture.origin}${downloadPath}`)).status(), 401)
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['csrf-and-context', 'basename-validation', 'offset-conflict', 'private-projection',
      'lost-upload-chunk-query-explicit-resume-no-replay', 'uploaded-source-to-import-preview',
      'lost-download-prepare-query-only', 'browser-8MiB-download-exact-bytes', 'bounded-256KiB-reader',
      'range-and-head-explicitly-unsupported', 'owner-isolation', 'mid-stream-session-revocation', 'no-horizontal-overflow'],
    execution: 'Synthetic storage and package receipts; no POSIX temporary file or Android package integrity validation.' }, null, 2))
  console.log(`PASS Package transfers HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
