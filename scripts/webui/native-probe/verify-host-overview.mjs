// Real native HTTP/authentication and production UI; explicit synthetic sensors.
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
const output = mkdtempSync(join(resolve(values.output), 'native-host-overview-'))
const fixture = await startFixture(values.exe)
let browser
try {
  await fixture.command('host-overview-mode ready')
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = [], reads = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => { if (new URL(request.url()).pathname === '/api/v1/host/overview') reads.push(Date.now()) })
  await page.goto(`${fixture.origin}/#/host`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.login-screen')).toHaveCount(0)
  const ui = key => page.getByTestId(`host-${key}`)
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await expect(ui('overview')).toBeVisible()
  await expect(ui('cpu')).toContainText('37.5%')
  await expect(ui('memory')).toContainText('3 GiB')
  await expect(ui('memory')).toContainText('8 GiB')
  await expect(ui('storage')).toContainText('60 GiB')
  await expect(ui('storage')).toContainText('64 GiB')
  await expect(ui('storage')).toContainText('128 GiB')
  await expect(ui('daemon-pid')).toHaveText('4242')
  await expect(ui('kernel')).toHaveText('6.1.0-fixture')
  await expect(ui('vm-count')).toContainText('3')
  await expect(ui('network-count')).toContainText('2')
  await expect(ui('disk-count')).toHaveText('4')
  await expect(ui('sampled-at')).toBeVisible()
  assert.equal((await fetch(`${fixture.origin}/api/v1/host/overview`)).status, 401)
  const session = await (await page.request.get(`${fixture.origin}/api/v1/session`)).json()
  assert(session.server.capabilities.includes('host.overview.v1'))
  const ready = await (await page.request.get(`${fixture.origin}/api/v1/host/overview`)).json()
  assert.deepEqual(ready.context, { daemonEpoch: session.server.daemonEpoch, sessionId: session.id })
  assert.equal(ready.inventory.diskVirtualBytes, null)
  assert.equal(ready.storage.freeBytes, '68719476736')
  assert.equal(ready.storage.availableBytes, '64424509440')
  await noOverflow()
  await page.screenshot({ path: join(output, 'host-ready-320.png'), fullPage: true })

  await fixture.command('host-overview-mode first')
  await ui('refresh').click()
  await expect(ui('cpu').locator('strong')).toHaveText('—')
  await expect(ui('cpu').getByRole('progressbar')).toHaveCount(0)
  await expect(ui('memory')).toContainText('3 GiB')
  await fixture.command('host-overview-mode unavailable')
  await ui('refresh').click()
  for (const metric of ['cpu', 'memory', 'storage']) {
    await expect(ui(metric).locator('strong')).toHaveText('—')
    await expect(ui(metric).getByRole('progressbar')).toHaveCount(0)
  }
  await expect(ui('vm-count')).toContainText('—')
  await expect(ui('disk-count')).toHaveText('—')
  await expect(ui('daemon-pid')).toHaveText('4242')
  await noOverflow()
  await page.screenshot({ path: join(output, 'host-unknown-320.png'), fullPage: true })

  await fixture.command('host-overview-mode ready')
  await ui('refresh').click()
  await expect(ui('cpu')).toContainText('37.5%')
  await page.route('**/api/v1/host/overview', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'unavailable' } }) }))
  await ui('refresh').click()
  await expect(ui('stale')).toBeVisible()
  await expect(ui('cpu')).toContainText('37.5%')
  await page.screenshot({ path: join(output, 'host-stale-320.png'), fullPage: true })
  await page.unroute('**/api/v1/host/overview')
  await ui('refresh').click()
  await expect(ui('stale')).toHaveCount(0)

  // The VM overview starts monitoring only when expanded; both existing entry
  // points use the same production model, not independent fixture/UI numbers.
  await page.goto(`${fixture.origin}/#/vms`)
  await page.locator('details.overview > summary').click()
  await expect(ui('cpu')).toContainText('37.5%')
  await noOverflow()
  await page.screenshot({ path: join(output, 'vm-host-metrics-320.png'), fullPage: true })
  await page.goto(`${fixture.origin}/#/more`)
  await expect(ui('summary')).toBeVisible()
  await expect(ui('summary')).toContainText('Fixture Phone')
  await noOverflow()
  await page.screenshot({ path: join(output, 'more-host-summary-320.png'), fullPage: true })
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['authenticated-context-bound-observation', 'cpu-first-sample-unknown',
      'memory-available-and-data-filesystem-capacity', 'free-and-available-remain-distinct',
      'unknown-metrics-and-counts-are-not-zero', 'last-snapshot-retained-and-marked-stale-on-failure',
      'manual-refresh-recovers', 'vm-and-more-pages-use-host-model', 'no-horizontal-overflow'],
    readCount: reads.length,
    execution: 'Synthetic host sensor values through native HTTP and production UI. Native parser contracts are separate; no Android hardware or performance validation.' }, null, 2))
  console.log(`PASS Host overview HTTP and phone UI: ${output}`)
} finally {
  await browser?.close()
  await fixture.close()
}
