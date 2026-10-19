// Actual native HTTP upload bytes and browser full-reload recovery.
// Fixture storage/installation is synthetic; it does not validate real image or package formats.
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-upload-recovery-'))
let fixture, browser, diagnostic
const results = []
try {
  fixture = await startFixture(values.exe)
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  for (const spec of [
    { domain: 'image', path: 'resources/disk/import', locale: 'zh-CN', lang: 'zh-Hans', filename: '恢复镜像.raw', stats: 'image-upload-stats' },
    { domain: 'package', path: 'packages/transfers', locale: 'en-GB', lang: 'en', filename: 'recovery.vmpkg', stats: 'transfer-stats' },
  ]) {
    const { domain, lang } = spec
    const labels = JSON.parse(readFileSync(new URL(`../../../webui/src/shared/i18n/locales/${lang}.json`, import.meta.url), 'utf8'))
    const context = await browser.newContext({ locale: spec.locale, viewport: { width: 320, height: 568 } })
    const page = await context.newPage(), errors = [], uploads = [], chunks = [], finishes = [], uploadReads = [], installs = [], installReads = []
    diagnostic = { page, domain, lang, uploads, chunks, finishes, installs }
    page.setDefaultTimeout(10000)
    page.on('pageerror', error => errors.push(error.message))
    const ui = key => page.getByTestId(`${domain}-upload-${key}`)
    const button = key => page.getByRole('button', { name: labels[key], exact: true })
    const endpoint = `/api/v1/${domain}-uploads`
    let importWrites = 0, lostMiddleChunk = false, finishedOnServer = 0, installAdmitted = 0, hideInstallLookup = true
    page.on('request', request => {
      const path = new URL(request.url()).pathname
      if (request.method() === 'POST' && /^\/api\/v1\/(image|package)-import/.test(path)) importWrites++
    })
    await page.route(`**${endpoint}`, async route => {
      assert.equal(route.request().method(), 'POST')
      uploads.push(route.request().postDataJSON().requestId)
      const response = await route.fetch()
      assert.equal(response.status(), 200)
      await route.fulfill({ response })
    })
    await page.route(`**${endpoint}/*/chunks`, async route => {
      const body = route.request().postDataJSON()
      const payload = Buffer.from(body.data, 'base64')
      chunks.push({ id: new URL(route.request().url()).pathname.split('/')[4], index: body.index ?? 0,
        offset: body.offset, bytes: payload.length, sha256: createHash('sha256').update(payload).digest('hex') })
      const response = await route.fetch()
      assert.equal(response.status(), 200)
      if (!lostMiddleChunk && body.offset === '262144' && (body.index ?? 0) === 0) {
        lostMiddleChunk = true
        await route.abort('connectionfailed').catch(() => {})
      } else await route.fulfill({ response })
    })
    await page.route(`**${endpoint}/*/finish`, async route => {
      finishes.push(new URL(route.request().url()).pathname.split('/')[4])
      const response = await route.fetch()
      assert.equal(response.status(), 200)
      finishedOnServer++
      await route.abort('connectionfailed').catch(() => {})
    })
    await page.route(new RegExp(`/api/v1/${domain}-uploads/[^/]+$`), async route => {
      assert.equal(route.request().method(), 'GET')
      uploadReads.push(new URL(route.request().url()).pathname.split('/').at(-1))
      await route.continue()
    })
    if (domain === 'image') {
      await page.route('**/api/v1/image-installs', async route => {
        const body = route.request().postDataJSON()
        installs.push({ requestId: body.requestId, uploadId: body.uploadId })
        const response = await route.fetch()
        assert.equal(response.status(), 202)
        installAdmitted++
        await route.abort('connectionfailed').catch(() => {})
      })
      await page.route('**/api/v1/image-installs/*', route => {
        assert.equal(route.request().method(), 'GET')
        installReads.push(new URL(route.request().url()).pathname.split('/').at(-1))
        return hideInstallLookup ? route.abort('connectionfailed') : route.continue()
      })
    }
    const read = async path => {
      const response = await page.request.get(`${fixture.origin}${path}`)
      assert.equal(response.status(), 200)
      return response.json()
    }
    const noOverflow = async () => expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const capture = async (name, language = lang) => {
      await noOverflow()
      await page.screenshot({ path: join(output, `${domain}-${language}-${name}-320.png`), fullPage: true })
    }
    await page.goto(`${fixture.origin}/#/${spec.path}`)
    await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
    await button('sessionLogin').click()
    await expect(page.locator('.app-shell')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', lang)
    const session = await read('/api/v1/session'), beforeStats = await fixture.command(spec.stats)
    const main = Buffer.alloc(600003)
    for (let i = 0; i < main.length; i++) main[i] = i % 251
    const selectedFiles = [{ name: spec.filename, mimeType: 'application/octet-stream', buffer: main }]
    if (domain === 'package') selectedFiles.push({ name: `${spec.filename}.001`, mimeType: 'application/octet-stream', buffer: Buffer.from('完整分卷内容 🌏') })
    const fileInput = domain === 'image' ? ui('file') : page.locator('.transfers input[type=file]').first()
    await fileInput.setInputFiles(selectedFiles)
    const start = domain === 'image' ? ui('start') : button('transferStart')
    await expect(start).toBeEnabled()
    await start.click()
    await expect.poll(() => lostMiddleChunk).toBe(true)
    await expect(ui('phase')).toHaveAttribute('data-phase', 'unknown')
    assert.equal(uploads.length, 1)
    assert.equal(chunks.length, 2)
    const originalId = uploads[0]
    const snapshot = (await read(`${endpoint}/${encodeURIComponent(originalId)}`)).transfer
    assert.equal(snapshot.state, 'receiving')
    assert.equal(snapshot.files[0].offsetBytes, '524288')
    const digests = selectedFiles.map(file => createHash('sha256').update(file.buffer).digest('hex'))
    const assertJournal = async () => {
      const result = await page.evaluate(({ namespace, uploadId, expectedDigests, csrf }) => {
        const raw = sessionStorage.getItem(`droidvm.operations.v1:${namespace}`) ?? ''
        return { present: raw.includes(uploadId), bounded: raw.length < 65536,
          fullDigests: expectedDigests.every(digest => raw.toLowerCase().includes(digest)),
          secret: /csrfToken|pairingToken|password|cookie/i.test(raw) || raw.includes(csrf) }
      }, { namespace: `${domain}-upload`, uploadId: originalId, expectedDigests: digests, csrf: session.csrfToken })
      assert(result.present && result.bounded && result.fullDigests, 'Journal must retain bounded metadata and full-file SHA256, without file contents')
      assert.equal(result.secret, false)
    }
    await assertJournal()
    const readsBeforeReload = uploadReads.length
    await page.reload()
    await expect(ui('restored')).toBeVisible()
    await ui('inspect').click()
    await expect.poll(() => uploadReads.length).toBeGreaterThan(readsBeforeReload)
    await expect(ui('reselect')).toBeVisible()
    await expect(ui('reselect')).toBeEnabled()
    assert.equal(chunks.length, 2, 'Reload and original-ID GET must not replay bytes')
    assert.equal(finishes.length, 0)
    assert.equal(uploads.length, 1)
    await assertJournal()

    // Only the last byte differs: equal name/size and an equal uploaded prefix
    // must still fail full-file hashing, not pass a prefix-only comparison.
    const changedMain = Buffer.from(main)
    changedMain[changedMain.length - 1] ^= 0xff
    const wrongFiles = selectedFiles.map((file, index) => index ? file : { ...file, buffer: changedMain })
    await ui('reselect').setInputFiles(wrongFiles)
    await expect(ui('problem')).toHaveAttribute('data-code', 'conflict')
    await expect(ui('problem')).toContainText(labels.transferFileMismatchHint)
    await expect(ui('problem')).not.toContainText(labels.transferRestoreUnavailable)
    await expect(ui('hashing')).toHaveCount(0)
    if (await ui('resume').count()) await expect(ui('resume')).toBeDisabled()
    assert.equal(chunks.length, 2)
    await capture('different-content-rejected')
    await ui('reselect').setInputFiles(selectedFiles)
    await expect(ui('resume')).toBeEnabled()
    await expect(ui('hashing')).toHaveCount(0)
    assert.equal(chunks.length, 2, 'Matching File selection still requires an explicit resume')
    await capture('verified-explicit-resume')
    await ui('resume').click()
    await expect.poll(() => finishedOnServer).toBe(1)
    await expect(ui('phase')).toHaveAttribute('data-phase', 'unknown')
    const expectedChunks = [[0, '0'], [0, '262144'], [0, '524288'], ...(domain === 'package' ? [[1, '0']] : [])]
    assert.deepEqual(chunks.map(chunk => [chunk.index, chunk.offset]), expectedChunks)
    for (const chunk of chunks) {
      assert.equal(chunk.id, originalId)
      assert(chunk.bytes > 0 && chunk.bytes <= 262144)
      const expected = selectedFiles[chunk.index].buffer.subarray(Number(chunk.offset), Number(chunk.offset) + chunk.bytes)
      assert.equal(chunk.sha256, createHash('sha256').update(expected).digest('hex'))
    }
    const chunkCount = chunks.length, readyReads = uploadReads.length
    await page.reload()
    await expect(ui('restored')).toBeVisible()
    await ui('inspect').click()
    await expect(ui('phase')).toHaveAttribute('data-phase', 'ready')
    await expect.poll(() => uploadReads.length).toBeGreaterThan(readyReads)
    await expect(ui('reselect')).toHaveCount(0)
    assert.equal(chunks.length, chunkCount)
    assert.equal(finishes.length, 1)
    assert(finishes.every(id => id === originalId))
    assert(uploadReads.every(id => id === originalId))
    for (const input of await page.locator('input[type=file]').all())
      assert.equal(await input.evaluate(element => element.files?.length ?? 0), 0, 'Ready recovery requires no selected browser File')
    const ready = (await read(`${endpoint}/${encodeURIComponent(originalId)}`)).transfer
    assert.equal(ready.state, 'ready')
    assert(ready.files.every(file => file.offsetBytes === file.sizeBytes))
    await capture('ready-without-file')
    if (domain === 'image') {
      await ui('folder').fill('/fixture')
      await ui('name').fill('刷新恢复镜像.raw')
      await ui('acknowledge').check()
      await ui('install').click()
      await expect.poll(() => installAdmitted).toBe(1)
      await expect(ui('result')).toContainText(labels.imageUploadUnknown)
      assert.equal(await page.evaluate(({ requestId, uploadId, csrf }) => {
        const raw = sessionStorage.getItem('droidvm.operations.v1:image-install') ?? ''
        return raw.includes(requestId) && raw.includes(uploadId) &&
          !/csrfToken|pairingToken|password|cookie/i.test(raw) && !raw.includes(csrf)
      }, { requestId: installs[0].requestId, uploadId: originalId, csrf: session.csrfToken }), true)
      const installReadCount = installReads.length
      await page.reload()
      await expect(page.getByTestId('image-install-restored')).toBeVisible()
      await ui('reconcile').click()
      await expect.poll(() => installReads.length).toBeGreaterThan(installReadCount)
      assert.equal(installs.length, 1)
      assert.equal(installs[0].uploadId, originalId)
      hideInstallLookup = false
      await expect(async () => {
        await ui('reconcile').click()
        await expect(ui('import')).toBeEnabled()
      }).toPass({ timeout: 10000 })
      assert(installReads.every(id => id === installs[0].requestId))
      await expect(ui('result')).toContainText('/fixture/刷新恢复镜像.raw')
      await expect(page.getByTestId('image-import-source')).toHaveValue('')
      assert.equal(importWrites, 0)
      await capture('installed-without-import')
      // A third representative locale uses the actual language selector; it
      // revisits the restored result without repeating transfer/install writes.
      await page.goto(`${fixture.origin}/#/more`)
      await page.getByRole('combobox', { name: labels.language, exact: true }).selectOption('zh-Hant')
      await page.goto(`${fixture.origin}/#/${spec.path}`)
      await expect(page.locator('html')).toHaveAttribute('lang', 'zh-Hant')
      await expect(async () => {
        await ui('reconcile').click()
        await expect(ui('import')).toBeEnabled()
      }).toPass({ timeout: 10000 })
      await capture('restored-install', 'zh-Hant')
      assert.equal(installs.length, 1)
      assert.equal(importWrites, 0)
    } else {
      await expect(button('transferImport')).toBeEnabled()
      assert.equal(importWrites, 0)
    }
    const stats = await fixture.command(spec.stats)
    const bytes = selectedFiles.reduce((sum, file) => sum + file.buffer.length, 0)
    assert.equal(BigInt(stats.uploadBytes) - BigInt(beforeStats.uploadBytes), BigInt(bytes))
    if (domain === 'image') {
      assert.equal(stats.chunks - beforeStats.chunks, chunks.length)
      assert.equal(stats.installs - beforeStats.installs, 1)
    } else {
      let hash = beforeStats.uploadHash
      for (const file of selectedFiles) for (const byte of file.buffer) hash = Math.imul(hash ^ byte, 16777619) >>> 0
      assert.equal(stats.uploadHash, hash, 'Native peer must receive the exact original main and volume bytes')
    }
    assert.equal(uploads.length, 1)
    assert.equal(chunks.length, chunkCount)
    assert.equal(finishes.length, 1)
    assert.equal(importWrites, 0)
    assert.deepEqual(errors, [])
    await noOverflow()
    results.push({ domain, language: lang, creates: uploads.length, chunks: chunks.length,
      finishes: finishes.length, installs: installs.length, importWrites, bytes, width: 320 })
    await context.close()
  }
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, results,
    checks: ['real-middle-chunk-admission-then-receipt-loss', 'reload-original-id-get-no-automatic-write',
      'full-file-sha256-rejects-same-name-size-different-tail', 'matching-files-require-explicit-resume',
      'exact-original-bytes-and-offsets-no-replayed-chunks', 'lost-finish-ready-recovery-needs-no-file',
      'lost-image-install-receipt-reload-get-only', 'no-automatic-install-import-or-package-preview',
      'bounded-journal-full-file-hashes-without-credentials', 'three-language-320px-representative-screens'],
    execution: 'Real browser File bytes through native HTTP upload handlers; package fixture validates rolling byte hash, image fixture counts bytes/chunks. Cache storage, installation and import sources are synthetic. No real POSIX publication, image/package format validation or Android deployment.' }, null, 2))
  console.log(`PASS Upload recovery after reload: ${output}`)
} catch (failure) {
  if (diagnostic && !diagnostic.page.isClosed()) {
    const { page, domain, lang, uploads, chunks, finishes, installs } = diagnostic
    const prefix = `${domain}-${lang}-failure`
    const dom = await page.evaluate(() => [...document.querySelectorAll(
      '[data-testid$="-phase"], [data-testid$="-restored"], [data-testid$="upload-problem"], [data-testid="image-upload-result"], [data-testid="operation-tracking-notice"]',
    )].slice(0, 12).map(element => ({ testid: element.getAttribute('data-testid'), phase: element.getAttribute('data-phase'),
      text: element.textContent.replace(/[a-f0-9]{32,}/gi, '[redacted-id]').replace(/\s+/g, ' ').trim().slice(0, 700) }))).catch(() => [])
    writeFileSync(join(output, `${prefix}.json`), JSON.stringify({ domain, language: lang, creates: uploads.length,
      chunks: chunks.length, finishes: finishes.length, installs: installs.length, dom }, null, 2))
    await page.screenshot({ path: join(output, `${prefix}.png`), fullPage: true,
      mask: [page.locator('#pairing-token, input[type="password"]')] }).catch(() => {})
    console.error(`Upload recovery diagnostic: ${join(output, prefix)}`)
  }
  throw failure
} finally {
  await browser?.close()
  await fixture?.close()
}
