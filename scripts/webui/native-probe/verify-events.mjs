// One focused integration: real WS auth/journal + browser event-driven refresh.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { startFixture, deadline } from './server-fixture.mjs'

const repo = fileURLToPath(new URL('../../../', import.meta.url))
const require = createRequire(join(repo, 'webui/package.json'))
const { chromium, expect } = require('@playwright/test')
const WebSocket = require('ws')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: join(repo, 'build/webui-native') } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-events-'))
const fixture = await startFixture(values.exe)
const sockets = []
let vite, browser
try {
  const pairing = await fixture.command('pair')
  const response = await fetch(`${fixture.origin}/api/v1/session`, {
    method: 'POST', headers: { Origin: fixture.origin, 'Content-Type': 'application/json' }, body: JSON.stringify(pairing),
  })
  assert.equal(response.status, 201)
  const session = await response.json()
  const cookie = response.headers.get('set-cookie').split(';')[0]
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  function stream({ origin = fixture.origin, csrf = session.csrfToken, cursor = null, path = '/api/v1/events' } = {}) {
    const socket = new WebSocket(fixture.origin.replace('http:', 'ws:') + path, 'droidvm.events.v1', { headers: { Origin: origin, Cookie: cookie } })
    sockets.push(socket)
    const messages = [], waiting = []
    socket.on('message', data => {
      const value = JSON.parse(data.toString())
      const done = waiting.shift()
      if (done) done(value); else messages.push(value)
    })
    socket.on('open', () => socket.send(JSON.stringify({ type: 'subscribe', context, csrfToken: csrf, cursor })))
    socket.on('error', () => {})
    const closed = new Promise(resolve => socket.on('close', code => resolve(code)))
    return { socket, closed, next: () => deadline(messages.length ? Promise.resolve(messages.shift()) : new Promise(resolve => waiting.push(resolve))) }
  }
  const denied = stream({ origin: 'http://untrusted.invalid' })
  await deadline(denied.closed)
  const csrfDenied = stream({ csrf: '0'.repeat(64) })
  assert.equal(await deadline(csrfDenied.closed), 1008)
  const live = stream()
  assert.equal((await live.next()).type, 'ready')
  await fixture.command('vm-event')
  const event = await live.next()
  assert.equal(event.type, 'invalidate')
  assert.deepEqual(event.resources, ['vms'])
  assert.deepEqual(Object.keys(event).sort(), ['context', 'cursor', 'resources', 'type'])
  live.socket.close()
  await deadline(live.closed)
  const gap = stream({ cursor: { epoch: 'old-daemon', sequence: '0' } })
  assert.equal((await gap.next()).type, 'resync')
  await fixture.command(`revoke ${session.id}`)
  assert.equal(await deadline(gap.closed), 1008)

  process.env.DROIDVM_DEV_API_TARGET = fixture.origin
  const { createServer } = await import(pathToFileURL(join(repo, 'webui/node_modules/vite/dist/node/index.js')))
  vite = await createServer({ root: join(repo, 'webui'), configFile: join(repo, 'webui/vite.config.ts'), server: { host: '127.0.0.1', port: 5178, strictPort: true } })
  await vite.listen()
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 390, height: 844 } })
  const page = await browserContext.newPage()
  const errors = [], frames = []
  let activeSocket
  page.on('pageerror', error => errors.push(error.message))
  page.on('websocket', socket => {
    if (!socket.url().endsWith('/api/v1/events')) return
    activeSocket = socket
    socket.on('framereceived', frame => frames.push(JSON.parse(frame.payload.toString())))
  })
  await page.goto('http://127.0.0.1:5178/#/vms')
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect.poll(() => frames.some(frame => frame.type === 'ready')).toBe(true)
  await expect(page.getByText('原生 HTTP 虚拟机', { exact: true })).toBeVisible()
  // Settle the initial baseline, then require a refresh sooner than the 5s fallback.
  await page.waitForTimeout(800)
  const before = (await fixture.command('stats')).reads
  await fixture.command('vm-event')
  await expect.poll(async () => (await fixture.command('stats')).reads, { timeout: 3000 }).toBeGreaterThan(before)
  await page.goto('http://127.0.0.1:5178/#/tasks')
  await page.waitForTimeout(800)
  await fixture.command('task')
  await expect(page.locator('.task-card')).toHaveCount(1, { timeout: 3000 })
  // Native expiry closes the already-open stream; browser revalidates and clears.
  const closed = activeSocket.waitForEvent('close')
  await fixture.command('expire')
  await closed
  await expect(page.locator('#pairing-token')).toBeVisible()
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'result.json'), JSON.stringify({ passed: true, checks: ['origin-before-upgrade', 'csrf-first-frame', 'journal-vm-invalidation', 'white-list-frame', 'cursor-resync', 'revocation-closes-stream', 'browser-vm-refresh', 'browser-task-refresh', 'expiry-clears-session'] }, null, 2))
  console.log(`Native WS event integration passed; ${output}`)
} finally {
  for (const socket of sockets) if (socket.readyState !== WebSocket.CLOSED) socket.terminate()
  await browser?.close(); await vite?.close(); await fixture.close()
  writeFileSync(join(output, 'service.log'), fixture.log())
}
