// Complete management UI against synthetic module state, never a real KO.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-hugepage-management-'))
const fixture = await startFixture(values.exe)
let browser
try {
  await fixture.command('hugepage-mode ready')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await context.newPage(), errors = [], writes = [], checks = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    if (request.method() === 'POST' && request.url().endsWith('/api/v1/hugepage-operations')) writes.push(request.postDataJSON())
  })
  const ui = key => page.getByTestId(`hugepages-${key}`)
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200)
    return response.json()
  }
  const state = () => get('host/hugepages')
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const capture = async name => {
    await expect(ui('refresh')).toBeEnabled()
    const response = page.waitForResponse(response => response.url().endsWith('/api/v1/host/hugepages') && response.status() === 200)
    await ui('refresh').click()
    await response
    await expect(ui('refresh')).toBeEnabled()
    await page.evaluate(() => document.activeElement?.blur())
    await page.screenshot({ path: join(output, `${name}-320.png`), fullPage: true })
  }
  const group = async name => {
    await ui(`tab-${name}`).click()
    await expect(ui(`group-${name}`)).toBeVisible()
    await noOverflow()
  }
  const mode = async name => {
    await fixture.command(`hugepage-mode ${name}`)
    const fingerprint = (await state()).fingerprint
    const response = page.waitForResponse(async response => response.url().endsWith('/api/v1/host/hugepages') && response.status() === 200 && (await response.json()).fingerprint === fingerprint)
    await ui('refresh').click()
    await response
    await expect(ui('refresh')).toBeEnabled()
  }
  const action = async (name, risk = false) => {
    const before = writes.length
    await expect(ui(name)).toBeEnabled()
    await ui(name).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    if (risk) {
      await expect(ui('confirm')).toBeDisabled()
      await dialog.getByRole('checkbox').check()
    }
    await ui('confirm').click()
    await expect(async () => assert.equal(writes.length, before + 1)).toPass()
    let task
    await expect(async () => {
      task = (await get(`hugepage-operations/${writes.at(-1).requestId}`)).task
      assert.equal(task?.state, 'succeeded')
    }).toPass()
    await expect(ui('receipt')).toBeVisible()
    await ui('query').click()
    await expect(ui('refresh')).toBeEnabled()
    await noOverflow()
    return task.hugepageResult
  }
  await page.goto(`${fixture.origin}/#/hugepages`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(ui('page')).toBeVisible()
  assert((await get('session')).server.capabilities.includes('host.hugepages.manage.v1'))
  for (const name of ['overview', 'reserve', 'module', 'cma', 'advanced', 'maintenance']) await group(name)
  checks.push('six-categories-at-320px')

  await group('module')
  await expect(ui('runtime-disable')).toBeDisabled()
  await expect(ui('unload')).toBeDisabled()
  const boot = await action('boot-disable')
  assert.equal(boot.saved, true)
  assert.equal(boot.applied, false)
  assert.equal((await state()).loaded, true)
  checks.push('consumers-block-disable-and-unload', 'boot-save-does-not-unload')
  await capture('module')

  await group('advanced')
  await ui('advanced-input-system_reserve_mb').fill('896')
  const reserve = await action('advanced-save-system_reserve_mb', true)
  assert.equal(reserve.saved, true)
  assert.equal(reserve.applied, false)
  let row = (await state()).advanced.find(item => item.key === 'system_reserve_mb')
  assert.equal(row.saved, '896'); assert.equal(row.live, '512'); assert.equal(row.reloadRequired, true)
  await mode('readonly')
  await ui('advanced-input-cma_reservoir_floor_mb').fill('96')
  const floor = await action('advanced-save-cma_reservoir_floor_mb')
  assert.equal(floor.saved, true); assert.equal(floor.applied, false)
  row = (await state()).advanced.find(item => item.key === 'cma_reservoir_floor_mb')
  assert.equal(row.saved, '96'); assert.equal(row.live, '64'); assert.equal(row.application, 'next-load')
  checks.push('system-reserve-explicit-risk-confirmation', 'readonly-advanced-saved-only')
  await capture('advanced')

  await mode('idle')
  await group('cma')
  await ui('cma-trial-target').fill('3072')
  await ui('cma-lever').selectOption('hook')
  const trial = await action('cma-trial', true)
  assert.equal(trial.saved, false); assert.equal(trial.applied, true)
  assert.equal((await state()).actualCmaPages, '3072')
  assert.equal((await state()).savedCmaPages, '2048')
  const save = await action('cma-save')
  assert.equal(save.saved, true); assert.equal(save.applied, false)
  assert.equal(writes.at(-1).operation.kind, 'cma-save')
  assert.equal((await state()).savedCmaPages, '3072')
  checks.push('cma-trial-does-not-save', 'cma-save-does-not-repeat-trial')
  await capture('cma')

  await group('module')
  await action('runtime-disable')
  assert.equal((await state()).loaded, true)
  assert.equal((await state()).actualPoolPages, '0')
  await action('unload', true)
  assert.equal((await state()).loaded, false)
  await action('runtime-enable')
  assert.equal((await state()).loaded, true)
  checks.push('soft-disable-keeps-module', 'explicit-unload-and-runtime-enable')

  await mode('legacy')
  await group('cma')
  await expect(ui('cma-trial')).toBeDisabled()
  await group('maintenance')
  await action('migrate-settings')
  await action('dismiss-crash')
  assert.equal((await state()).hasLegacySettings, false)
  assert.equal((await state()).hasCrash, false)
  checks.push('legacy-capability-disables-cma', 'explicit-migration-and-crash-acknowledgement')
  await noOverflow()
  assert.deepEqual(errors, [])
  const stats = await fixture.command('hugepage-stats')
  assert.equal(stats.effects, writes.length)
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320, checks,
    posts: writes.length, fixture: stats,
    execution: 'Synthetic module state through real native HTTP and production UI. Native service contracts validate coordinator behavior separately. No Android KO, boot or device deployment validation.' }, null, 2))
  console.log(`PASS Hugepage management HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
