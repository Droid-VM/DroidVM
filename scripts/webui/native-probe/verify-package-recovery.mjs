// Native HTTP admission and browser reload recovery; package/storage receipts are synthetic.
import assert from 'node:assert/strict'
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
const output = mkdtempSync(join(resolve(values.output), 'native-package-recovery-'))
const endpoints = ['package-import-plans', 'package-imports', 'package-exports', 'package-downloads']
let fixture, browser, diagnostic
const results = []
try {
  fixture = await startFixture(values.exe)
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  for (const spec of [
    { locale: 'zh-CN', lang: 'zh-Hans', uncertain: true },
  ]) {
    const { lang } = spec
    const labels = JSON.parse(readFileSync(new URL(`../../../webui/src/shared/i18n/locales/${lang}.json`, import.meta.url), 'utf8'))
    const context = await browser.newContext({ locale: spec.locale, viewport: { width: 320, height: 568 }, acceptDownloads: true })
    const page = await context.newPage(), errors = [], posts = [], reads = [], deletes = [], fileReads = []
    diagnostic = { page, lang, posts, reads, deletes }
    const blocked = new Set(endpoints), admitted = new Set()
    let downloads = 0
    page.setDefaultTimeout(10000)
    page.on('pageerror', error => errors.push(error.message))
    page.on('dialog', async dialog => {
      assert.equal(dialog.type(), 'beforeunload')
      await dialog.accept()
    })
    page.on('download', () => downloads++)
    page.on('request', request => {
      const path = new URL(request.url()).pathname
      if (/\/api\/v1\/package-downloads\/[^/]+\/files\//.test(path)) fileReads.push(path)
    })
    for (const endpoint of endpoints) {
      await page.route(`**/api/v1/${endpoint}`, async route => {
        assert.equal(route.request().method(), 'POST')
        const body = route.request().postDataJSON()
        posts.push({ endpoint, body })
        const response = await route.fetch()
        assert.equal(response.status(), endpoint === 'package-downloads' ? 200 : 202)
        admitted.add(endpoint)
        await route.abort('connectionfailed').catch(() => {})
      })
      await page.route(new RegExp(`/api/v1/${endpoint}/[^/]+$`), async route => {
        const id = new URL(route.request().url()).pathname.split('/').at(-1)
        if (route.request().method() === 'DELETE') {
          assert.equal(endpoint, 'package-downloads')
          deletes.push(id)
          assert.equal((await route.fetch()).status(), 204)
          await route.abort('connectionfailed').catch(() => {})
          return
        }
        assert.equal(route.request().method(), 'GET')
        reads.push({ endpoint, id })
        if (blocked.has(endpoint)) await route.abort('connectionfailed').catch(() => {})
        else await route.continue()
      })
    }
    const button = key => page.getByRole('button', { name: labels[key], exact: true })
    const field = key => page.getByLabel(labels[key])
    const journal = namespace => page.evaluate(name => sessionStorage.getItem(`droidvm.operations.v1:${name}`), namespace)
    const count = endpoint => posts.filter(row => row.endpoint === endpoint).length
    const noOverflow = async () => expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const capture = async name => {
      await noOverflow()
      await page.screenshot({ path: join(output, `${lang}-${name}-320.png`), fullPage: true })
    }
    const settle = async check => expect(async () => {
      // Import's query reconciles both preview and import, so the other button may disappear.
      const query = button('queryStatus').first()
      if (await query.isVisible() && await query.isEnabled()) await query.click()
      await check()
    }).toPass({ timeout: 10000 })
    const restoreUnknown = async (endpoint, namespace, container) => {
      await expect.poll(() => admitted.has(endpoint)).toBe(true)
      await expect(button('queryStatus').first()).toBeEnabled()
      const original = posts.find(row => row.endpoint === endpoint).body.requestId
      const saved = await journal(namespace)
      assert(saved, `${namespace} must be durable before a reload`)
      assert(saved.includes(original))
      const readStart = reads.length
      await page.reload()
      await expect(page.locator(container)).toBeVisible()
      await settle(async () => {
        assert(reads.slice(readStart).some(row => row.endpoint === endpoint && row.id === original))
      })
      assert.equal(await journal(namespace), saved)
      assert.equal(count(endpoint), 1)
      assert(reads.filter(row => row.endpoint === endpoint).every(row => row.id === original))
      blocked.delete(endpoint)
      return original
    }

    await page.goto(`${fixture.origin}/#/packages/import`)
    await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
    await button('sessionLogin').click()
    await expect(page.locator('.import-form')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', lang)
    const source = `/fixture/${lang}-recovery.vmpkg.001`
    await field('packageImportSource').fill(source)
    await button('packageImportReadPreview').click()
    const previewId = await restoreUnknown('package-import-plans', 'package-import-preview', '.import-form')
    await settle(() => expect(page.locator('.summary')).toContainText(source.replace(/\.001$/, '')))
    await expect(field('packageImportSource')).toHaveValue(source)
    await expect(button('packageImportStart')).toHaveCount(0)
    assert.equal(count('package-imports'), 0)
    await capture('preview-restored')
    await field('packageImportTarget').fill(spec.uncertain ? '/fixture/uncertain' : `/fixture/${lang}-imported`)
    await field('packageImportNetworkAction').selectOption('skip')
    await button('packageImportReview').click()
    await expect(field('packageImportConfirm')).not.toBeChecked()
    await expect(button('packageImportStart')).toBeDisabled()
    await field('packageImportConfirm').check()
    await button('packageImportStart').click()
    await restoreUnknown('package-imports', 'package-import', '.import-form')
    assert.equal(posts.find(row => row.endpoint === 'package-imports').body.planRequestId, previewId)
    if (spec.uncertain) {
      await settle(() => expect(page.locator('.import-form')).toContainText('/fixture/uncertain/retained-vm'))
      await expect(field('packageImportRecoveryConfirm')).not.toBeChecked()
      await expect(button('packageImportNewFlow')).toBeDisabled()
      await expect(button('packageImportStart')).toHaveCount(0)
      await expect(page.getByRole('link', { name: labels.packageImportOpenVm, exact: true })).toHaveCount(0)
    } else {
      await settle(() => expect(page.getByRole('link', { name: labels.packageImportOpenVm, exact: true })).toBeVisible())
    }
    assert.equal(count('package-import-plans'), 1)
    assert.equal(count('package-imports'), 1)
    await capture('import-restored')

    await page.evaluate(() => { location.hash = '/packages/export/native-http-vm' })
    const filename = `${lang}-recovery.vmpkg`
    await field('packageExportDestination').fill(`/fixture/${filename}`)
    await field('packageExportVolume').fill('16777216')
    await button('packageExportNoDisks').click()
    await button('packageExportReview').click()
    await expect(field('packageExportOverwriteConfirm')).not.toBeChecked()
    await expect(button('packageExportStart')).toBeDisabled()
    await field('packageExportOverwriteConfirm').check()
    await button('packageExportStart').click()
    // Remove the route VM ID to exercise restoration of the request's target as well.
    await page.evaluate(() => { location.hash = '/packages/export' })
    const exportId = await restoreUnknown('package-exports', 'package-export', '.export-form')
    await settle(() => expect(button('transferPrepareDownload')).toBeEnabled())
    await expect(field('packageExportVm')).toHaveValue('native-http-vm')
    await expect(button('packageExportStart')).toHaveCount(0)
    assert.equal(count('package-downloads'), 0)
    await capture('export-restored')

    await button('transferPrepareDownload').click()
    const downloadId = await restoreUnknown('package-downloads', 'package-download', '.transfers')
    assert.equal(posts.find(row => row.endpoint === 'package-downloads').body.exportRequestId, exportId)
    // The reusable native storage fixture has a fixed file name, independent of the export path.
    const link = page.getByTestId('package-download-file').first()
    await settle(() => expect(link).toBeVisible())
    await expect(link).toHaveText('浏览器导出.vmpkg')
    await expect(link).toHaveAttribute('href', `/api/v1/package-downloads/${downloadId}/files/0`)
    await page.reload()
    await settle(() => expect(link).toBeVisible())
    assert.equal(count('package-downloads'), 1)
    assert.equal(downloads, 0)
    assert.deepEqual(fileReads, [])
    await capture('download-restored')

    blocked.add('package-downloads')
    await button('transferDeleteDownload').click()
    await button('transferConfirmDelete').click()
    await expect(button('queryStatus')).toBeEnabled()
    assert.equal(deletes.length, 1)
    const deleteJournal = await journal('package-download')
    assert(deleteJournal?.includes(downloadId))
    await page.reload()
    await expect(button('transferDeleteDownload')).toBeVisible()
    await settle(() => expect(button('transferForget')).toBeEnabled())
    assert.equal(await journal('package-download'), deleteJournal)
    assert.deepEqual(deletes, [downloadId])
    blocked.delete('package-downloads')
    const missing = page.waitForResponse(response => response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `/api/v1/package-downloads/${downloadId}` && response.status() === 404)
    await button('queryStatus').click()
    await missing
    await expect(button('transferForget')).toBeEnabled()
    assert.equal(await journal('package-download'), deleteJournal)
    await capture('download-delete-unknown')
    await button('transferForget').click()
    await expect(button('transferConfirmForget')).toBeVisible()
    assert.equal(await journal('package-download'), deleteJournal)
    await button('transferConfirmForget').click()
    await expect(button('transferDeleteDownload')).toHaveCount(0)
    assert.equal(await journal('package-download'), null)
    assert.deepEqual(deletes, [downloadId])
    assert.equal(posts.length, 4)
    assert.equal(downloads, 0)
    assert.deepEqual(fileReads, [])
    assert.deepEqual(errors, [])
    results.push({ language: lang, width: 320, posts: posts.length, queries: reads.length,
      deletes: deletes.length, downloads, uncertainRegistration: !!spec.uncertain })
    await context.close()
  }
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, results,
    checks: ['preview-import-export-download-admitted-before-receipt-loss', 'full-reload-original-request-get-only',
      'preview-source-restored-without-confirmation', 'import-result-without-plan', 'uncertain-registration-requires-explicit-review',
      'export-target-and-result-without-route-id', 'ready-download-survives-reload-without-file-request',
      'delete-receipt-loss-and-404-retain-tracking-until-explicit-forget', 'browser-language-and-320px-layout'],
    execution: 'Real native HTTP/session/task handlers; synthetic package and storage receipts. No actual unpacking, host package creation or Android filesystem persistence.' }, null, 2))
  console.log(`PASS Package recovery after reload: ${output}`)
} catch (failure) {
  if (diagnostic && !diagnostic.page.isClosed()) {
    const { page, lang, posts, reads, deletes } = diagnostic
    const dom = await page.locator('.import-form, .export-form, .transfers').allTextContents().catch(() => [])
    writeFileSync(join(output, `${lang}-failure.json`), JSON.stringify({ language: lang,
      posts: posts.map(row => row.endpoint), queries: reads.length, deletes: deletes.length, dom }, null, 2))
    await page.screenshot({ path: join(output, `${lang}-failure.png`), fullPage: true,
      mask: [page.locator('#pairing-token, input[type=password]')] }).catch(() => {})
    console.error(`Package recovery diagnostic: ${output}`)
  }
  throw failure
} finally {
  await browser?.close()
  await fixture?.close()
}
