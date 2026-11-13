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
  // The main page mirrors the App; parameters, CMA lever and maintenance live on the
  // advanced page. Switches are md-switch elements, reached by role for enabled checks.
  const main = async () => {
    if (!(await ui('advanced-link').isVisible())) await page.locator('a[href="#/hugepages"]').first().click()
    await expect(ui('advanced-link')).toBeVisible()
    await noOverflow()
  }
  const advanced = async () => {
    if (await ui('advanced-link').isVisible()) await ui('advanced-link').click()
    await expect(ui('group-advanced')).toBeVisible()
    await noOverflow()
  }
  const toggle = name => page.getByRole('switch', { name, exact: true })
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
    const control = typeof name === 'string' ? ui(name) : name
    await expect(control).toBeEnabled()
    await control.click()
    const dialog = page.getByRole('dialog').last()
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
  await advanced()
  for (const name of ['advanced', 'cma', 'maintenance']) await expect(ui(`group-${name}`)).toBeVisible()
  checks.push('advanced-sections-at-320px')
  await main()
  await expect(ui('cma-lent')).toContainText('GiB')
  checks.push('cma-lent-shown')

  await expect(ui('runtime-disable')).toBeDisabled()
  const boot = await action(toggle('开机加载模块'))
  assert.equal(boot.saved, true)
  assert.equal(boot.applied, false)
  assert.equal((await state()).loaded, true)
  await advanced()
  await expect(ui('unload')).toBeDisabled()
  checks.push('consumers-block-disable-and-unload', 'boot-save-does-not-unload')
  await capture('module')

  await ui('advanced-system_reserve_mb').click()
  await ui('advanced-input-system_reserve_mb').fill('896')
  const reserve = await action('advanced-save-system_reserve_mb', true)
  assert.equal(reserve.saved, true)
  assert.equal(reserve.applied, false)
  let row = (await state()).advanced.find(item => item.key === 'system_reserve_mb')
  assert.equal(row.saved, '896'); assert.equal(row.live, '512'); assert.equal(row.reloadRequired, true)
  await mode('readonly')
  await ui('advanced-cma_reservoir_floor_mb').click()
  await ui('advanced-input-cma_reservoir_floor_mb').fill('96')
  const floor = await action('advanced-save-cma_reservoir_floor_mb')
  assert.equal(floor.saved, true); assert.equal(floor.applied, false)
  row = (await state()).advanced.find(item => item.key === 'cma_reservoir_floor_mb')
  assert.equal(row.saved, '96'); assert.equal(row.live, '64'); assert.equal(row.application, 'next-load')
  checks.push('system-reserve-explicit-risk-confirmation', 'readonly-advanced-saved-only')
  await capture('advanced')

  // The CMA switch saves like the App: off persists 0, on persists the with-CMA size above
  // using the lever chosen on the advanced page.
  await mode('idle')
  await main()
  const off = await action(toggle('启用 CMA'))
  assert.equal(off.saved, true); assert.equal(off.applied, true)
  assert.equal((await state()).actualCmaPages, '0')
  await advanced()
  await ui('cma-lever').selectOption('hook')
  await main()
  const on = await action(toggle('启用 CMA'), true)
  assert.equal(on.saved, true); assert.equal(on.applied, true)
  assert.equal(writes.at(-1).operation.persist, true)
  assert.equal((await state()).actualCmaPages, '2048')
  assert.equal((await state()).savedCmaPages, '2048')
  assert.equal((await state()).cma.savedLever, 'hook')
  checks.push('cma-switch-saves-off-and-on')
  await capture('cma')

  await action('runtime-disable')
  assert.equal((await state()).loaded, true)
  assert.equal((await state()).actualPoolPages, '0')
  await advanced()
  await action('unload', true)
  assert.equal((await state()).loaded, false)
  await main()
  await action('runtime-enable')
  assert.equal((await state()).loaded, true)
  checks.push('soft-disable-keeps-module', 'explicit-unload-and-runtime-enable')

  await mode('legacy')
  await expect(toggle('启用 CMA')).toHaveCount(0)
  await action('dismiss-crash')
  await advanced()
  await action('migrate-settings')
  assert.equal((await state()).hasLegacySettings, false)
  assert.equal((await state()).hasCrash, false)
  checks.push('legacy-capability-hides-cma', 'explicit-migration-and-crash-acknowledgement')
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
