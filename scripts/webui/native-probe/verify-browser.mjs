// Browser-to-native integration, not the JavaScript HTTP fixture used by unit UI checks.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { startFixture } from './server-fixture.mjs'

const repo = fileURLToPath(new URL('../../../', import.meta.url))
const require = createRequire(join(repo, 'webui/package.json'))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, static: { type: 'boolean', default: false }, output: { type: 'string', default: join(repo, 'build/webui-native') } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-browser-'))
const fixture = await startFixture(values.exe)
process.env.DROIDVM_DEV_API_TARGET = fixture.origin
const origin = values.static ? fixture.origin : 'http://127.0.0.1:5178'
let vite, browser
const results = []
const diagnostics = []
try {
  if (!values.static) {
    const { createServer } = await import(pathToFileURL(join(repo, 'webui/node_modules/vite/dist/node/index.js')))
    vite = await createServer({ root: join(repo, 'webui'), configFile: join(repo, 'webui/vite.config.ts'), server: { host: '127.0.0.1', port: 5178, strictPort: true } })
    await vite.listen()
  }
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  for (const [locale, lang, loginLabel, logoutLabel, refreshLabel, cancelLabel, cancelledLabel] of [
    ['zh-CN', 'zh-Hans', '登录', '退出登录', '刷新列表', '取消任务', '已取消'],
    ['zh-TW', 'zh-Hant', '登入', '登出', '重新整理清單', '取消工作', '已取消'],
    ['en-GB', 'en', 'Sign in', 'Sign out', 'Refresh list', 'Cancel task', 'Cancelled'],
  ]) {
    const context = await browser.newContext({ locale, viewport: { width: 390, height: 844 } })
    const page = await context.newPage()
    const errors = []
    page.on('pageerror', error => { errors.push(error.message); diagnostics.push(error.message) })
    page.on('console', message => {
      if (message.type() === 'error') {
        diagnostics.push(message.text())
        if (/Content Security Policy|violates.*directive/i.test(message.text())) errors.push('CSP rejected production content')
      }
    })
    if (values.static) page.on('request', request => {
      if (!request.url().startsWith(origin + '/')) errors.push('Unexpected external request')
    })
    const signIn = async () => {
      const grant = await fixture.command('pair')
      await page.locator('#pairing-token').fill(grant.pairingToken)
      await page.getByRole('button', { name: loginLabel, exact: true }).click()
      await expect(page.locator('.app-shell')).toBeVisible()
    }
    await page.goto(`${origin}/#/vms/native-http-vm`)
    await expect(page.locator('#pairing-token')).toBeVisible()
    await expect(page.locator('html')).toHaveAttribute('lang', lang)
    await page.screenshot({ path: join(output, `${lang}-login.png`), fullPage: true })
    await signIn()
    await expect(page.getByRole('dialog')).toContainText('原生 HTTP 虚拟机')
    await expect(page.getByRole('dialog')).toContainText('2,048 MiB')
    const cookie = (await context.cookies()).find(entry => entry.name === 'droidvm-session')
    assert(cookie?.httpOnly && cookie.sameSite === 'Strict' && cookie.path === '/')
    assert(!(await page.evaluate(() => document.cookie)).includes('droidvm-session'))
    assert.equal(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }).includes('csrfToken')), false)
    await page.reload()
    await expect(page.getByRole('dialog')).toContainText('原生 HTTP 虚拟机')
    await page.keyboard.press('Escape')
    await expect(page.locator('[data-vm]')).toHaveCount(1)
    await page.screenshot({ path: join(output, `${lang}-vms.png`), fullPage: true })
    const labels = {
      'zh-Hans': { stop: '停止', start: '启动', suspend: '挂起', resume: '恢复', reboot: '重启', details: '详情', query: '查询当前状态', unknown: '操作结果待核实' },
      'zh-Hant': { stop: '停止', start: '啟動', suspend: '暫停', resume: '繼續執行', reboot: '重新啟動', details: '詳細資訊', query: '查詢目前狀態', unknown: '操作結果待確認' },
      en: { stop: 'Stop', start: 'Start', suspend: 'Suspend', resume: 'Resume', reboot: 'Restart', details: 'Details', query: 'Check current status', unknown: 'The operation outcome is unknown' },
    }[lang]
    const vmCard = page.locator('[data-vm]')
    const initialEffects = (await fixture.command('stats')).vmEffects
    let writes = 0
    page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/v1/vm-operations')) writes++ })
    for (const [index, action] of ['stop', 'start', 'suspend', 'resume', 'reboot'].entries()) {
      if (action === 'start') {
        // Lose a real native response after admission, then recover through GET.
        await page.route('**/api/v1/vm-operations', async route => {
          const response = await route.fetch(); assert.equal(response.status(), 202)
          await route.abort('connectionfailed')
        }, { times: 1 })
      }
      const secondary = action === 'suspend' || action === 'reboot'
      if (secondary) await vmCard.getByRole('link', { name: labels.details, exact: true }).click()
      await (secondary ? page.getByRole('dialog') : vmCard).getByRole('button', { name: labels[action], exact: true }).click()
      await page.getByRole('dialog', { name: labels[action], exact: true }).getByRole('button', { name: labels[action], exact: true }).click()
      if (action === 'start') {
        await expect(vmCard).toContainText(labels.unknown)
        await vmCard.getByRole('button', { name: labels.query, exact: true }).click()
        await expect(vmCard).not.toContainText(labels.unknown)
      }
      await expect.poll(async () => (await fixture.command('stats')).vmEffects).toBe(initialEffects + index + 1)
      await expect(page.getByRole('dialog', { name: labels[action], exact: true })).toHaveCount(0)
      if (secondary) { await page.keyboard.press('Escape'); await expect(page.getByRole('dialog')).toHaveCount(0) }
      const next = action === 'stop' ? 'start' : action === 'suspend' ? 'resume' : 'stop'
      await expect(vmCard.getByRole('button', { name: labels[next], exact: true })).toBeEnabled({ timeout: 12000 })
      assert.equal(writes, index + 1)
    }
    await page.setViewportSize({ width: 320, height: 568 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    await page.screenshot({ path: join(output, `${lang}-vm-operations.png`), fullPage: true })
    const { taskId } = await fixture.command('task')
    await page.goto(`${origin}/#/tasks`)
    const card = page.locator(`[data-task="${taskId}"]`)
    await expect(card).toBeVisible()
    await expect(card).toContainText('9007199254740993')
    await expect(card.locator('progress')).toHaveAttribute('value', '50')
    await page.setViewportSize({ width: 320, height: 568 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    await page.screenshot({ path: join(output, `${lang}-tasks.png`), fullPage: true })
    await card.getByRole('button', { name: cancelLabel, exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: cancelLabel, exact: true }).click()
    await expect(card).toContainText(cancelledLabel, { timeout: 10000 })
    await expect(card.getByRole('button', { name: cancelLabel, exact: true })).toHaveCount(0)
    await page.goto(`${origin}/#/vms`)
    await page.setViewportSize({ width: 390, height: 844 })
    const session = await (await context.request.get(`${origin}/api/v1/session`)).json()
    await fixture.command(`revoke ${session.id}`)
    await page.getByRole('button', { name: refreshLabel, exact: true }).click()
    await expect(page.locator('#pairing-token')).toBeVisible()
    await expect(page.locator('[data-vm]')).toHaveCount(0)
    await signIn()
    await page.goto(`${origin}/#/more`)
    await page.getByRole('button', { name: logoutLabel, exact: true }).click()
    await expect(page.locator('#pairing-token')).toBeVisible()
    assert(!(await context.cookies()).some(entry => entry.name === 'droidvm-session'))
    assert.equal(errors.length, 0)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    await context.close()
    results.push({ locale, passed: true, checks: ['pairing', 'httpOnly', 'dto', 'reload', 'vm-lifecycle-five-actions', 'vm-lost-response-read-recovery', 'tasks', 'uint64-progress', 'task-cancellation', 'revocation', 'logout', 'mobile-320'] })
    console.log(`PASS native browser session ${locale}`)
  }
  assert.equal(await fixture.close(), 0)
  writeFileSync(join(output, 'result.json'), JSON.stringify({ passed: results.length, static: values.static, results }, null, 2))
  console.log(`Native browser integration passed; ${output}`)
} finally {
  writeFileSync(join(output, 'browser-diagnostics.json'), JSON.stringify(diagnostics, null, 2).replace(/[0-9a-f]{64}/gi, '[redacted]'))
  if (diagnostics.length) console.log(`Browser diagnostics: ${output}`)
  await browser?.close()
  await vite?.close()
  await fixture.close()
  writeFileSync(join(output, 'service.log'), fixture.log())
}
