// Actual HTTP/WS/TCP/RFB/noVNC integration using a synthetic framebuffer peer.
// This does not launch QEMU/crosvm or validate an Android display implementation.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { deadline, startFixture } from './server-fixture.mjs'
import { startRfbFixture } from './rfb-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test'), WebSocket = require('ws')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-graphics-'))
const rfb = await startRfbFixture(), fixture = await startFixture(values.exe), sockets = []
let browser
try {
  await fixture.command(`display-endpoint ${rfb.port}`)
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  const page = await browserContext.newPage(), errors = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/vms`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.login-screen')).toHaveCount(0)
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200, await response.text())
    return response.json()
  }
  const session = await get('session')
  assert(session.server.capabilities.includes('vm.display.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const vm = (await get('vms')).items.find(item => item.id === 'native-http-vm')
  const started = await page.request.post(`${fixture.origin}/api/v1/vm-operations`, {
    headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken },
    data: { context, requestId: 'graphics-start', vmId: vm.id, action: 'start', expected: {
      instanceId: vm.lifecycle.instanceId, revision: vm.lifecycle.revision, configRevision: vm.lifecycle.configRevision,
    } },
  })
  assert.equal(started.status(), 202, await started.text())
  await expect(async () => assert((await get(`vm-displays/${vm.id}`)).screens.some(screen => screen.available))).toPass({ timeout: 10000 })
  const metadata = await get(`vm-displays/${vm.id}`)
  assert.equal(metadata.screens.find(screen => screen.id === 'simplefb').available, false)
  assert(!/password|127\.0\.0\.1|"port"|"host"/.test(JSON.stringify(metadata)))
  assert.equal((await fetch(`${fixture.origin}/api/v1/vm-displays/${vm.id}`)).status, 401)
  const cookie = (await browserContext.cookies()).map(item => `${item.name}=${item.value}`).join('; ')
  const openFrame = overrides => ({ type: 'open', context, csrfToken: session.csrfToken, vmId: vm.id,
    screenId: 'gpu-0', expected: metadata.expected, ...overrides })
  function connect(headers = {}) {
    const socket = new WebSocket(`${fixture.origin.replace('http:', 'ws:')}/api/v1/display`, 'droidvm.display.v1', {
      headers: { Origin: fixture.origin, Cookie: cookie, ...headers },
    })
    sockets.push(socket)
    const state = { socket, closed: false, messages: [], status: null }
    state.opened = deadline(new Promise((resolve, reject) => {
      socket.once('open', resolve)
      socket.once('unexpected-response', (_request, response) => { state.status = response.statusCode; response.resume(); reject(new Error('Upgrade rejected')) })
      socket.once('error', reject)
    }))
    state.opened.catch(() => {})
    socket.on('error', () => {})
    socket.on('close', () => { state.closed = true })
    socket.on('message', (data, binary) => state.messages.push(binary ? data : JSON.parse(data.toString())))
    return state
  }
  for (const headers of [{ Cookie: '' }, { Origin: 'http://untrusted.invalid' }]) {
    const denied = connect(headers)
    await assert.rejects(denied.opened)
    assert.notEqual(denied.socket.readyState, WebSocket.OPEN)
  }
  for (const override of [{ csrfToken: 'invalid' }, { context: { ...context, daemonEpoch: 'stale' } },
    { expected: { ...metadata.expected, instanceId: 'stale' } }, { screenId: 'simplefb' }]) {
    const denied = connect(); await denied.opened
    denied.socket.send(JSON.stringify(openFrame(override)))
    await expect.poll(() => denied.closed).toBe(true)
    assert(!denied.messages.some(message => message.type === 'ready'))
  }
  assert.equal(rfb.stats().accepted, 0)
  await page.goto(`${fixture.origin}/#/vms/${vm.id}/console`)
  const ui = key => page.getByTestId(`graphic-${key}`)
  await expect(ui('connect')).toBeEnabled()
  await ui('connect').click()
  const canvas = ui('canvas')
  await expect(canvas).toBeVisible()
  await expect.poll(() => rfb.stats().authenticated).toBe(1)
  await expect.poll(() => canvas.evaluate(element => [...element.getContext('2d').getImageData(10, 10, 1, 1).data])).toEqual([230, 80, 40, 255])
  assert.deepEqual(await canvas.evaluate(element => [...element.getContext('2d').getImageData(200, 200, 1, 1).data]), [40, 120, 210, 255])
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await noOverflow()
  await page.screenshot({ path: join(output, 'graphics-320.png'), fullPage: true })
  await canvas.click({ position: { x: 15, y: 15 } })
  await page.keyboard.press('a')
  await page.keyboard.press('Tab')
  await page.keyboard.press('Escape')
  await expect(canvas).toBeVisible()
  await expect.poll(() => rfb.stats().keys.some(key => key.key === 97 && key.down)).toBe(true)
  await expect.poll(() => rfb.stats().keys.some(key => key.key === 0xff09 && key.down)).toBe(true)
  await expect.poll(() => rfb.stats().keys.some(key => key.key === 0xff1b && key.down)).toBe(true)
  await expect.poll(() => rfb.stats().pointers.some(pointer => pointer.buttons === 1)).toBe(true)
  await ui('keyboard').click()
  await ui('input').fill('中')
  await expect.poll(() => rfb.stats().keys.some(key => key.key === (0x01000000 | 0x4e2d) && key.down)).toBe(true)
  await ui('key-Enter').click()
  await expect.poll(() => rfb.stats().keys.some(key => key.key === 0xff0d && key.down)).toBe(true)
  await noOverflow()
  await page.screenshot({ path: join(output, 'graphics-keyboard-320.png'), fullPage: true })
  await canvas.click({ position: { x: 15, y: 15 } })
  await page.keyboard.down('b')
  await expect.poll(() => rfb.stats().keys.some(key => key.key === 98 && key.down)).toBe(true)
  // Moving focus away from the framebuffer releases a held physical key.
  await ui('disconnect').focus()
  await expect.poll(() => rfb.stats().keys.some(key => key.key === 98 && !key.down)).toBe(true)
  await ui('disconnect').click()
  await expect.poll(() => rfb.stats().connected).toBe(0)
  await expect(ui('reconnect')).toBeEnabled()
  await ui('reconnect').click()
  await expect(ui('canvas')).toBeVisible()
  await expect.poll(() => rfb.stats().authenticated).toBe(2)
  await fixture.command('terminal-change')
  await expect(ui('reconnect')).toBeEnabled()
  await expect.poll(() => rfb.stats().connected).toBe(0)
  const afterChange = rfb.stats().accepted
  await page.waitForTimeout(600)
  assert.equal(rfb.stats().accepted, afterChange, 'No automatic reconnect after runtime change')
  await ui('reconnect').click()
  await expect(ui('canvas')).toBeVisible()
  await expect.poll(() => rfb.stats().authenticated).toBe(3)
  await fixture.command(`revoke ${session.id}`)
  await expect.poll(() => rfb.stats().connected).toBe(0)
  assert.deepEqual(errors, [])
  assert.deepEqual(rfb.stats().faults, [])
  const stats = await fixture.command('display-stats')
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['metadata-hides-endpoint-and-password', 'cookie-origin-csrf-context-instance-required',
      'vnc-password-authentication', 'bounded-binary-transport-and-real-canvas-pixels', 'pointer-and-keyboard-roundtrip',
      'guest-tab-escape-do-not-close-dialog', 'held-key-released-on-focus-loss', 'unicode-soft-input', 'explicit-reconnect-only',
      'runtime-change-and-session-revocation-close-tcp', 'no-horizontal-overflow'],
    stats, rfb: rfb.stats(), execution: 'Local synthetic standard RFB peer, not an Android VM. Physical keyboard/IME/fullscreen and backend codecs require device validation.' }, null, 2))
  console.log(`PASS Graphics HTTP/WS/RFB and phone UI: ${output}`)
} finally {
  for (const socket of sockets) if (socket.readyState !== WebSocket.CLOSED) socket.terminate()
  await browser?.close()
  await fixture.close()
  await rfb.close()
}
