// Isolated HTTP/WS checks. Requires `npm ci` in webui; no TLS tools or certificates.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import http from 'node:http'
import net from 'node:net'
import { createRequire } from 'node:module'
import { resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'

const repo = fileURLToPath(new URL('../../../', import.meta.url))
const require = createRequire(join(repo, 'webui/package.json'))
const WebSocket = require('ws')
const { values } = parseArgs({ options: {
  exe: { type: 'string' },
  output: { type: 'string', default: join(repo, 'build/webui-native') },
} })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'http-runtime-'))
const artifact = name => join(output, name)
const reservation = net.createServer()
reservation.listen(0, '127.0.0.1')
await once(reservation, 'listening')
const port = reservation.address().port
await new Promise((done, fail) => reservation.close(error => error ? fail(error) : done()))
const origin = `http://127.0.0.1:${port}`
const sockets = new Set()
const children = []
const results = []
function timeout(promise, milliseconds = 7000) {
  let timer
  return Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(`timeout after ${milliseconds}ms`)), milliseconds)
  })]).finally(() => clearTimeout(timer))
}
async function start(seconds) {
  const child = spawn(resolve(values.exe), [String(port), String(seconds)],
    { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] })
  children.push(child)
  child.log = ''
  child.stdout.on('data', data => { child.log += data })
  child.stderr.on('data', data => { child.log += data })
  child.done = new Promise((done, fail) => { child.once('error', fail); child.once('exit', code => done(code)) })
  // Capture readiness before any output can be delivered.
  await timeout(new Promise((done, fail) => {
    child.once('error', fail)
    child.once('exit', code => fail(new Error(`probe exited before ready: ${code}\n${child.log}`)))
    child.stdout.on('data', () => { if (child.log.includes('native-probe-ready')) done() })
  }))
  return child
}
function request(options = {}) {
  return new Promise((done, fail) => {
    const req = http.get({ hostname: '127.0.0.1', port, path: '/_probe/read',
      agent: false, ...options }, response => {
      let body = ''
      response.on('data', chunk => { body += chunk })
      response.on('error', fail)
      response.on('end', () => done({ status: response.statusCode, headers: response.headers, body }))
    })
    req.setTimeout(12000, () => req.destroy(new Error('request timeout')))
    req.on('error', fail)
  })
}
async function connect(options = {}, path = '/_probe/echo') {
  const socket = new WebSocket(`ws://127.0.0.1:${port}${path}`, 'probe-echo',
    { origin, handshakeTimeout: 4000, ...options })
  sockets.add(socket)
  socket.on('error', () => {})
  socket.on('close', () => sockets.delete(socket))
  try { await timeout(once(socket, 'open')); return socket }
  catch (error) { socket.terminate(); throw error }
}
async function echo(data, options, fragments) {
  const socket = await connect()
  try {
    const reply = timeout(once(socket, 'message'))
    if (fragments) {
      socket.send(data.subarray(0, 3), { ...options, fin: false })
      socket.send(data.subarray(3), { ...options, fin: true })
    } else socket.send(data, options)
    const [received, binary] = await reply
    assert.deepEqual(received, Buffer.from(data))
    assert.equal(binary, options?.binary ?? typeof data !== 'string')
    const closed = timeout(once(socket, 'close'))
    socket.close(1000)
    assert.equal((await closed)[0], 1000)
  } finally { if (socket.readyState !== WebSocket.CLOSED) socket.terminate() }
}
async function check(name, action) {
  try { await action(); results.push({ name, passed: true }); console.log(`PASS ${name}`) }
  catch (error) { results.push({ name, passed: false, error: String(error) }); console.error(`FAIL ${name}: ${error}`) }
}

try {
  const child = await start(10)
  await check('HTTP JSON / no-store', async () => {
    const response = await request()
    assert.equal(response.status, 200)
    assert.deepEqual(JSON.parse(response.body), { source: 'native-probe', tls: false })
    assert.equal(response.headers['cache-control'], 'no-store')
    assert.match(response.headers['content-type'], /application\/json/)
  })
  await check('HTTP bad Host rejected', async () => assert.equal((await request({
    headers: { Host: 'wrong.invalid' },
  })).status, 403))
  await check('HTTP bad Origin rejected', async () => assert.equal((await request({ headers: { Origin: 'https://wrong.invalid' } })).status, 403))
  await check('HTTP matching Origin allowed', async () => assert.equal((await request({ headers: { Origin: origin } })).status, 200))
  await check('Unknown path is JSON 404', async () => {
    const response = await request({ path: '/missing' })
    assert.equal(response.status, 404)
    assert.equal(JSON.parse(response.body).error, 'not_found')
  })
  await check('WS UTF-8 text', () => echo('简体 / 繁體 / English', { binary: false }))
  await check('WS binary', () => echo(Buffer.from([0, 255, 1, 128]), { binary: true }))
  await check('WS empty message', () => echo('', { binary: false }))
  await check('WS fragmented text', () => echo(Buffer.from('中文 fragmented text'), { binary: false }, true))
  await check('WS fragmented binary', () => echo(Buffer.from([0, 1, 2, 255, 3, 4]), { binary: true }, true))
  await check('WS empty first fragment preserves binary type', async () => {
    const socket = await connect()
    try {
      const reply = timeout(once(socket, 'message'))
      socket.send(Buffer.alloc(0), { binary: true, fin: false })
      socket.send(Buffer.from([0, 255]), { fin: true })
      const [received, binary] = await reply
      assert.deepEqual(received, Buffer.from([0, 255]))
      assert.equal(binary, true)
    } finally { socket.terminate() }
  })
  await check('WS ordered burst on one connection', async () => {
    const socket = await connect()
    try {
      const received = []
      const replies = timeout(new Promise((done, fail) => {
        socket.on('error', fail)
        socket.on('message', data => { received.push(data.toString()); if (received.length === 12) done() })
      }))
      const messages = Array.from({ length: 12 }, (_, index) => `message-${index}`)
      messages.forEach(message => socket.send(message))
      await replies
      assert.deepEqual(received, messages)
    } finally { socket.terminate() }
  })
  await check('WS 8 KiB boundary', () => echo(Buffer.alloc(8192, 65), { binary: true }))
  await check('WS over 8 KiB closes 1009', async () => {
    const socket = await connect()
    try { const closed = timeout(once(socket, 'close')); socket.send(Buffer.alloc(8193)); assert.equal((await closed)[0], 1009) }
    finally { socket.terminate() }
  })
  // libwebsockets may reject before emitting an HTTP response; reset is valid too.
  const rejected = /Unexpected server response|socket hang up/
  await check('WS bad Origin rejected', () => assert.rejects(connect({ origin: 'https://wrong.invalid' }), rejected))
  await check('WS missing Origin rejected', () => assert.rejects(connect({ origin: undefined }), rejected))
  await check('WS bad Host rejected', () => assert.rejects(connect({ headers: { Host: 'wrong.invalid' } }), rejected))
  await check('WS wrong path rejected', () => assert.rejects(connect({}, '/missing'), rejected))
  await check('WS reconnect', async () => { await echo('before'); await echo('after') })
  await check('Native timer shutdown with live connection', async () => {
    const socket = await connect()
    const closed = timeout(once(socket, 'close'), 30000)
    assert.equal(await timeout(child.done, 30000), 0)
    await closed
  })
  await check('Restart binds same port and serves HTTP', async () => {
    const next = await start(2)
    assert.equal((await request()).status, 200)
    assert.equal(await timeout(next.done), 0)
  })
} catch (error) {
  results.push({ name: 'setup/runtime', passed: false, error: String(error) })
  console.error(error)
} finally {
  for (const socket of sockets) socket.terminate()
  for (const child of children) {
    if (child.exitCode === null) child.kill()
    await timeout(child.done.catch(() => {})).catch(() => {})
  }
  children.forEach((child, index) => writeFileSync(artifact(`server-${index}.log`), child.log))
  writeFileSync(artifact('result.json'), JSON.stringify({ timestamp: new Date().toISOString(), platform: process.platform,
    node: process.version, executable: resolve(values.exe), port,
    exitCodes: children.map(child => child.exitCode),
    passed: results.every(test => test.passed), tests: results }, null, 2) + '\n')
  console.log(`Evidence: ${output}`)
  process.exitCode = results.every(test => test.passed) ? 0 : 1
}
