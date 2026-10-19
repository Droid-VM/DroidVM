// Real HTTP/session/tasks and phone UI; synthetic storage and image inspection.
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
const output = mkdtempSync(join(resolve(values.output), 'native-image-transfers-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = [], writes = [], reads = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/resources/disk/import`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  const ui = key => page.getByTestId(`image-upload-${key}`)
  await expect(ui('file')).toBeVisible()
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const sessionResponse = await page.request.get(`${fixture.origin}/api/v1/session`)
  assert.equal(sessionResponse.status(), 200)
  const session = await sessionResponse.json()
  assert(session.server.capabilities.includes('images.upload.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const headers = { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken }
  const post = async (path, data, status = 200, extra = {}) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/${path}`, {
      headers: { ...headers, ...extra }, data: { context, ...data },
    })
    assert.equal(response.status(), status, `${path}: ${await response.text()}`)
    return status === 200 || status === 202 ? response.json() : undefined
  }
  const begin = { requestId: 'image-upload-guard', files: [{ name: 'guard.raw', sizeBytes: '3' }] }
  await post('image-uploads', begin, 403, { 'X-DroidVM-CSRF': '' })
  await post('image-uploads', { ...begin, context: { ...context, daemonEpoch: 'stale' } }, 409)
  await post('image-uploads', { ...begin, files: [{ name: '../guard.raw', sizeBytes: '3' }] }, 400)
  await post('image-uploads', { ...begin, files: [...begin.files, { name: 'second.raw', sizeBytes: '3' }] }, 400)
  assert.equal((await fetch(`${fixture.origin}/api/v1/image-uploads/missing`)).status, 401)

  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (!/^\/api\/v1\/image-(uploads|installs)/.test(path)) return
    if (request.method() === 'POST') writes.push({ path, body: request.postDataJSON() })
    if (request.method() === 'GET') reads.push(path)
  })
  const bytes = Buffer.alloc(600003)
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251
  await ui('file').setInputFiles({ name: '手机镜像.raw', mimeType: 'application/octet-stream', buffer: bytes })
  await page.route('**/api/v1/image-uploads/*/chunks', async route => {
    assert.equal((await route.fetch()).status(), 200)
    await route.abort()
  }, { times: 1 })
  await ui('start').click()
  await expect(ui('inspect')).toBeEnabled()
  assert.equal(writes.filter(row => row.path.endsWith('/chunks')).length, 1)
  await ui('inspect').click()
  await expect(ui('resume')).toBeEnabled()
  assert.equal(writes.filter(row => row.path.endsWith('/chunks')).length, 1)
  await ui('resume').click()
  await expect(ui('folder')).toBeEnabled()
  await expect(ui('file')).toHaveCount(0)
  await expect(ui('files')).toContainText('手机镜像.raw')
  const uploadId = writes.find(row => row.path.endsWith('/image-uploads')).body.requestId
  const readyResponse = await page.request.get(`${fixture.origin}/api/v1/image-uploads/${uploadId}`)
  assert.equal(readyResponse.status(), 200)
  const ready = await readyResponse.json()
  assert(!JSON.stringify(ready).includes('source'))
  assert(!JSON.stringify(ready).includes('private'))
  const chunks = writes.filter(row => row.path.endsWith('/chunks'))
  assert.deepEqual(chunks.map(row => row.body.offset), ['0', '262144', '524288'])
  assert(chunks.every(row => Buffer.from(row.body.data, 'base64').length <= 262144))
  await ui('folder').fill('/fixture')
  await ui('name').fill('手机持久镜像.raw')
  await ui('acknowledge').check()
  await noOverflow()
  await page.screenshot({ path: join(output, 'image-upload-ready-320.png'), fullPage: true })
  await page.route('**/api/v1/image-installs', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await ui('install').click()
  await expect(async () => {
    if (await ui('reconcile').isEnabled()) await ui('reconcile').click()
    await expect(ui('import')).toBeEnabled()
  }).toPass({ timeout: 10000 })
  assert.equal(writes.filter(row => row.path.endsWith('/image-installs')).length, 1)
  assert(reads.some(path => path.startsWith('/api/v1/image-installs/')))
  await expect(ui('result')).toContainText('/fixture/手机持久镜像.raw')
  await noOverflow()
  await page.screenshot({ path: join(output, 'image-installed-320.png'), fullPage: true })
  await ui('import').click()
  await expect(page.getByTestId('image-import-source')).toHaveValue('/fixture/手机持久镜像.raw')
  await page.getByTestId('image-import-preview').click()
  await expect(async () => {
    if (await page.getByTestId('image-import-reconcile').isEnabled()) await page.getByTestId('image-import-reconcile').click()
    await expect(page.locator('.chain li').first()).toContainText('手机持久镜像.raw')
  }).toPass({ timeout: 10000 })
  await noOverflow()
  const stats = await fixture.command('image-upload-stats')
  assert.equal(String(stats.uploadBytes), String(bytes.length))
  assert.equal(stats.installs, 1)
  // A fresh page has its own upload flow; uncertain publication cannot hand off,
  // delete the cache, reset, or submit another installation.
  const uncertainPage = await browserContext.newPage()
  uncertainPage.on('pageerror', error => errors.push(error.message))
  const uncertain = key => uncertainPage.getByTestId(`image-upload-${key}`)
  await uncertainPage.goto(`${fixture.origin}/#/resources/disk/import`)
  await uncertain('file').setInputFiles({ name: 'uncertain.raw', mimeType: 'application/octet-stream', buffer: Buffer.from('raw') })
  await uncertain('start').click()
  await uncertain('folder').fill('/fixture')
  await uncertain('name').fill('uncertain.raw')
  await uncertain('acknowledge').check()
  await uncertain('install').click()
  await expect(async () => {
    if (await uncertain('reconcile').isEnabled()) await uncertain('reconcile').click()
    await expect(uncertain('result')).toContainText(labels.imageUploadRecovery)
  }).toPass({ timeout: 10000 })
  await expect(uncertain('remove')).toBeDisabled()
  await expect(uncertain('install')).toHaveCount(0)
  await expect(uncertain('import')).toHaveCount(0)
  await expect(uncertain('reset')).toHaveCount(0)
  assert.equal(await uncertainPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await uncertainPage.screenshot({ path: join(output, 'image-install-uncertain-320.png'), fullPage: true })
  assert.equal((await fixture.command('image-upload-stats')).installs, 2)
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['csrf-context-and-owner-required', 'single-file-basename-validation',
      'lost-chunk-query-explicit-resume-without-replay', 'bounded-chunks-exact-offsets',
      'ready-upload-hides-temporary-source', 'lost-install-receipt-query-with-one-write',
      'explicit-durable-source-handoff-to-existing-import', 'uncertain-install-blocks-handoff-removal-and-restart', 'no-horizontal-overflow'],
    execution: 'Synthetic storage and image inspection; does not execute POSIX installation or Android qemu-img.' }, null, 2))
  console.log(`PASS Image upload HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
