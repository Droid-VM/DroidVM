// Focused browser -> native log/history integration; no unrelated regression suite.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { startFixture } from './server-fixture.mjs'

const repo = fileURLToPath(new URL('../../../', import.meta.url))
const require = createRequire(join(repo, 'webui/package.json'))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: join(repo, 'build/webui-native') } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-logs-'))
const fixture = await startFixture(values.exe)
process.env.DROIDVM_DEV_API_TARGET = fixture.origin
let vite, browser
const errors = []
try {
  const { createServer } = await import(pathToFileURL(join(repo, 'webui/node_modules/vite/dist/node/index.js')))
  vite = await createServer({ root: join(repo, 'webui'), configFile: join(repo, 'webui/vite.config.ts'), server: { host: '127.0.0.1', port: 5178, strictPort: true } })
  await vite.listen()
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const context = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await context.newPage()
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('http://127.0.0.1:5178/#/vms/native-http-vm')
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('link', { name: '运行日志', exact: true }).click()
  const log = page.locator('.log-output'), dialog = page.getByRole('dialog')
  await expect(log).toContainText('<script>plain text</script>')
  await expect(log.locator('script')).toHaveCount(0)
  await expect(page.getByRole('combobox', { name: '日志流' }).locator('option')).toHaveCount(1)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'logs-320.png'), fullPage: true })
  await page.getByRole('checkbox', { name: '跟随输出', exact: true }).uncheck()
  await fixture.command('log appended-after-pause')
  await dialog.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(log).toContainText('appended-after-pause')
  await fixture.command('log-fill')
  await dialog.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(dialog).toContainText('日志已清空或旧内容已被覆盖')
  await dialog.getByRole('button', { name: '加载保留历史', exact: true }).click()
  await expect(log).toContainText('中-end')
  const expected = 'x'.repeat(65535) + '中-end\n'
  await expect(log).toHaveText(expected)
  const pending = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载当前日志', exact: true }).click()
  const download = await pending
  await download.saveAs(join(output, 'download.log'))
  assert.equal(readFileSync(join(output, 'download.log'), 'utf8'), expected)
  const session = await (await context.request.get('http://127.0.0.1:5178/api/v1/session')).json()
  await fixture.command(`revoke ${session.id}`)
  await dialog.getByRole('button', { name: '刷新列表', exact: true }).click()
  await expect(page.locator('#pairing-token')).toBeVisible()
  await expect(page.locator('.log-output')).toHaveCount(0)
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'result.json'), JSON.stringify({ passed: true, checks: ['native-buffer', 'mobile-320', 'plain-text', 'stream-filter', 'manual-follow', 'clear-reset', 'utf8-chunk-boundary', 'download-bytes', 'revocation-clears-data'] }, null, 2))
  console.log(`Native VM log integration passed; ${output}`)
} finally {
  await browser?.close(); await vite?.close(); await fixture.close()
  writeFileSync(join(output, 'service.log'), fixture.log())
}
