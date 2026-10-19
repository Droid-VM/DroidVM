// Production phone UI and native HTTP/tasks; image content and registry effects are synthetic.
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
const output = mkdtempSync(join(resolve(values.output), 'native-disk-optimization-'))
const fixture = await startFixture(values.exe)
let browser
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await browser.newPage({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  page.setDefaultTimeout(10000)
  const errors = [], diskWrites = [], preferenceWrites = [], imports = [], compressionReads = [], preferenceLookups = [], checks = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST') {
      if (path === '/api/v1/disk-operations') diskWrites.push(request.postDataJSON())
      if (path === '/api/v1/disk-default-operations') preferenceWrites.push(request.postDataJSON())
      if (path === '/api/v1/image-imports') imports.push(request.postDataJSON())
    }
    if (request.method() === 'GET') {
      if (/^\/api\/v1\/disks\/[^/]+\/compression$/.test(path)) compressionReads.push(path)
      if (path.startsWith('/api/v1/disk-default-operations/')) preferenceLookups.push(path.split('/').at(-1))
    }
  })
  const ui = key => page.getByTestId(`image-import-${key}`)
  const optimization = key => page.getByTestId(`disk-optimize-${key}`)
  const dialog = () => page.getByRole('dialog')
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200)
    return response.json()
  }
  const terminal = async (path, id) => {
    let task
    await expect(async () => {
      task = (await get(`${path}/${encodeURIComponent(id)}`)).task
      assert(task && ['succeeded', 'failed', 'cancelled'].includes(task.state))
    }).toPass({ timeout: 10000 })
    assert.equal(task.requestId, id)
    return task
  }
  const capture = async name => {
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth))
    const count = await dialog().count()
    assert(count <= 1, 'Optimization must not stack a disk details dialog underneath')
    if (count) assert(await dialog().evaluate(el => el.scrollWidth <= el.clientWidth))
    await page.evaluate(() => document.activeElement?.blur())
    await page.screenshot({ path: join(output, `${name}-320.png`), fullPage: true })
  }
  await page.goto(`${fixture.origin}/#/resources/disk/import`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(ui('source')).toBeVisible()
  const session = await get('session')
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  let serial = 0
  const saveDefaults = async changes => {
    const current = await get('host/disk-defaults')
    const requestId = `optimization-default-${++serial}`
    const response = await page.request.post(`${fixture.origin}/api/v1/disk-default-operations`, {
      headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken },
      data: { context, requestId, expected: current.expected, changes },
    })
    assert.equal(response.status(), 202)
    assert.equal((await terminal('disk-default-operations', requestId)).state, 'succeeded')
  }
  await saveDefaults({ optimizeCompression: 'ask', qemuImgCpus: [2, 3] })
  const importImage = async (source, count) => {
    await ui('source').fill(source)
    await ui('preview').click()
    await expect(async () => {
      if (await ui('reconcile').isEnabled()) await ui('reconcile').click()
      await expect(page.locator('.chain li')).toHaveCount(count)
    }).toPass({ timeout: 10000 })
    await ui('acknowledge').check()
    await ui('submit').click()
    await expect(async () => {
      if (await ui('reconcile').isEnabled()) await ui('reconcile').click()
      await expect(ui('result')).toContainText(labels.imageImportSuccess)
    }).toPass({ timeout: 10000 })
    const receipt = await terminal('image-imports', imports.at(-1).requestId)
    assert.equal(receipt.state, 'succeeded')
    assert.equal(receipt.imageImport.added, count === 1 ? 1 : 2)
    await expect(ui('optimize')).toBeEnabled()
    return receipt.imageImport.diskId
  }
  const enterFromImport = async () => {
    await ui('optimize').click()
    try { await expect(page).toHaveURL(/#\/resources\/disk$/) }
    catch (error) {
      const state = await page.evaluate(() => {
        const provides = document.querySelector('#app').__vue_app__._context.provides
        const model = provides[Object.getOwnPropertySymbols(provides).find(key => key.description === 'disk-actions')]
        return { allowed: model.allowed.value, checking: model.checking.value, recovery: model.recovery.settled.value, resizeGrow: model.resizeGrow.pending.value, entries: Object.values(model.entries.value).map(row => ({ state: row.state, task: row.task?.state, problem: row.problem?.code })) }
      })
      writeFileSync(join(output, 'failure-state.json'), JSON.stringify(state, null, 2))
      console.log(state)
      throw error
    }
    await expect(dialog()).toHaveCount(1)
    await expect(optimization('check')).toBeVisible()
  }
  const confirmCompression = async () => {
    await expect(optimization('compression-confirm')).toBeVisible()
    await optimization('compression-confirm').check()
  }
  const review = async () => {
    await page.getByTestId('disk-operation-review').click()
    await dialog().getByLabel(labels.diskAcknowledge, { exact: true }).check()
  }
  const execute = () => page.getByTestId('disk-operation-execute').click()
  const finishDisk = async expectedCount => {
    await expect(async () => assert.equal(diskWrites.length, expectedCount)).toPass()
    const task = await terminal('disk-operations', diskWrites.at(-1).requestId)
    assert.equal(task.state, 'succeeded')
    await expect(dialog()).toHaveCount(0)
    await expect(async () => {
      const query = page.locator('.disk-status').getByRole('button', { name: labels.queryStatus, exact: true })
      if (await query.isEnabled()) await query.click()
      await expect(page.locator('.disk-status')).toContainText(labels.taskSucceeded)
    }).toPass({ timeout: 10000 })
    return task
  }

  const standalone = await importImage('/fixture/standalone.qcow2', 1)
  assert.equal(diskWrites.length, 0)
  assert.equal(compressionReads.length, 0, 'Import and polling must not start effective-compression probes')
  await capture('registered-without-rewrite')
  await enterFromImport()
  await confirmCompression()
  await dialog().getByText(labels.diskAdvanced, { exact: true }).click()
  const cpus = () => dialog().getByLabel(labels.diskCpuAffinity, { exact: true })
  await expect(cpus()).toHaveValue('2-3')
  await saveDefaults({ qemuImgCpus: [0, 1] })
  await expect(cpus()).toHaveValue('2-3')
  await review()
  await capture('explicit-optimize-review')
  assert.equal(diskWrites.length, 0)
  await execute()
  await finishDisk(1)
  assert.deepEqual(diskWrites[0].operation, {
    action: 'convert', diskId: standalone, format: 'qcow2', compress: 'none', preallocate: false, cpus: [2, 3],
  })
  assert.equal(diskWrites[0].revision, 'fixture-disk-revision')
  assert.equal(diskWrites[0].vmRevision, 'fixture-reference-revision')
  const after = await get(`disks/${encodeURIComponent(standalone)}`)
  assert.equal(after.disk.path, '/fixture/standalone.qcow2')
  assert.equal(after.disk.parentId, null)
  assert.equal(after.inspection.format, 'qcow2')
  checks.push('registration-does-not-probe-or-rewrite', 'explicit-review-before-convert', 'actual-format-and-path-preserved', 'saved-cpus-frozen-before-default-changes')

  // Revisiting the same confirmed registration offers a fresh check, never replays its rewrite.
  await page.goto(`${fixture.origin}/#/resources/disk/import`)
  await expect(ui('optimize')).toBeEnabled()
  assert.equal(diskWrites.length, 1)
  await enterFromImport()
  await expect(optimization('check')).toContainText(labels.diskOptimizeNoneSkip)
  await expect(page.getByTestId('disk-operation-execute')).toHaveCount(0)
  assert.equal(diskWrites.length, 1)
  await capture('already-uncompressed-skip')
  await page.keyboard.press('Escape')
  await page.goto(`${fixture.origin}/#/resources/disk/import`)
  await page.getByLabel(labels.imageImportOutcomeConfirm, { exact: true }).check()
  await ui('restart').click()
  const overlay = await importImage('/fixture/imported.qcow2', 3)
  const readsBeforeOverlay = compressionReads.length
  await enterFromImport()
  await expect(optimization('check')).toContainText(labels.diskOptimizeOverlaySkip)
  assert.equal(diskWrites.length, 1)
  assert.equal(compressionReads.length, readsBeforeOverlay)
  assert.notEqual(overlay, standalone)
  await capture('overlay-skip')
  await page.keyboard.press('Escape')
  checks.push('revisited-import-rechecks-none-without-rewrite', 'imported-overlay-skips-with-chain-preserved')

  // Remembering compression is a separate CAS write. A lost receipt must stop the disk action.
  await saveDefaults({ optimizeCompression: 'ask', qemuImgCpus: [4, 5] })
  await page.goto(`${fixture.origin}/#/resources/disk?disk=fixture-copy-disk`)
  await expect(optimization('open')).toBeEnabled()
  await optimization('open').click()
  await expect(dialog()).toHaveCount(1)
  await confirmCompression()
  await optimization('remember').check()
  await review()
  let allowPreferenceLookup = false
  await page.route('**/api/v1/disk-default-operations/*', route => allowPreferenceLookup ? route.continue() : route.abort())
  await page.route('**/api/v1/disk-default-operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await execute()
  await expect(async () => assert.equal(preferenceWrites.length, 1)).toPass()
  const saved = preferenceWrites[0]
  assert.deepEqual(saved.changes, { optimizeCompression: 'none' })
  assert.equal((await terminal('disk-default-operations', saved.requestId)).state, 'succeeded')
  await expect(optimization('preference-state')).toContainText(labels.diskOptimizePreferenceUnknown)
  await expect(optimization('preference-state')).toBeInViewport()
  assert(await optimization('preference-state').evaluate(el => el.getBoundingClientRect().top >= el.closest('dialog').querySelector('header').getBoundingClientRect().bottom), 'Preference result must not sit behind the sticky dialog header')
  assert.equal(diskWrites.length, 1)
  await capture('remember-receipt-unknown')
  allowPreferenceLookup = true
  await optimization('preference-query').click()
  await expect(optimization('preference-state')).toContainText(labels.diskOptimizePreferenceSaved)
  assert.equal(preferenceWrites.length, 1)
  assert.equal(diskWrites.length, 1)
  assert(preferenceLookups.includes(saved.requestId))
  assert(preferenceLookups.every(id => id === saved.requestId))
  await expect(page.getByTestId('disk-operation-execute')).toBeEnabled()
  await execute()
  await finishDisk(2)
  assert.deepEqual(diskWrites[1].operation, {
    action: 'convert', diskId: 'fixture-copy-disk', format: 'qcow2', compress: 'none', preallocate: false, cpus: [4, 5],
  })
  assert.equal(preferenceWrites.length, 1)
  await capture('manual-optimize-completed')
  checks.push('manual-resource-optimize-entry', 'remember-cas-lost-receipt-stops-rewrite', 'query-original-preference-id-without-resubmit', 'saved-preference-needs-explicit-second-execute', '320px-with-one-dialog-and-no-horizontal-overflow')
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({
    passed: true, width: 320, checks, diskPosts: diskWrites.length,
    rememberPosts: preferenceWrites.length, compressionReads: compressionReads.length,
    execution: 'Production Vue UI, real native HTTP, task admission and shared-default CAS with synthetic registry and image observations. No Android image rewrite, CPU scheduling, effective-cluster probing or on-device durability is claimed.',
  }, null, 2))
  console.log(`PASS Disk optimization HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
