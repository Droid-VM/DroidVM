import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { once } from 'node:events'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture, freePort, deadline } from './server-fixture.mjs'

const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe)
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'service-runtime-'))
const fixture = await startFixture(values.exe)
const control = value => fixture.command(JSON.stringify(value))
const ipc = (command, request = {}, identity) => control({ op: 'ipc', command, request, identity })
const results = []
let current = (await ipc('web_status')).settings
const configure = async settings => {
  const result = await ipc('web_configure', { expected: current, settings: { ...current, ...settings } })
  if (result.settings) current = result.settings
  return result
}
function request(path = '/api/v1/session', options = {}) {
  return new Promise((resolve, reject) => {
    const { body, ...settings } = options
    const req = http.request({ hostname: '127.0.0.1', port: current.port, path, agent: false, ...settings,
      headers: { Origin: `http://127.0.0.1:${current.port}`, ...(body ? { 'Content-Length': Buffer.byteLength(body) } : {}), ...settings.headers },
    }, response => {
      let text = ''
      response.on('data', chunk => { text += chunk })
      response.on('error', reject)
      response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, body: text, socket: response.socket }))
    })
    req.setTimeout(5000, () => req.destroy(new Error('HTTP timeout')))
    req.on('error', reject); req.end(body)
  })
}
async function check(name, run) {
  try { await run() } catch (cause) { throw new Error(`${name}: ${cause.message.replace(/[0-9a-f]{64}/gi, '[redacted]')}`) }
  results.push({ name, passed: true }); console.log(`PASS ${name}`)
}
async function login() {
  const grant = await ipc('web_pair')
  assert(grant.pairingToken)
  const result = await request('/api/v1/session', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(grant) })
  assert.equal(result.status, 201)
  return { cookie: result.headers['set-cookie'][0].split(';')[0], id: JSON.parse(result.body).id }
}
try {
  await check('persisted enabled settings start through scheduled work without a configure request', async () => {
    const booted = await startFixture(values.exe, undefined, { extraArgs: ['--enabled'] })
    try {
      await booted.command(JSON.stringify({ op: 'tick' }))
      let status
      for (let i = 0; i < 50; ++i) {
        status = await booted.command(JSON.stringify({ op: 'ipc', command: 'web_status', request: {} }))
        if (status.running) break
        await new Promise(resolve => setTimeout(resolve, 20))
      }
      assert.equal(status.running, true)
      assert.equal((await request(undefined, { port: booted.port, headers: { Origin: booted.origin } })).status, 401)
    } finally { assert.equal(await booted.close(), 0) }
  })
  await check('unavailable IPv6 keeps the IPv4 listener usable', async () => {
    const ipv4 = await startFixture(values.exe, undefined, { extraArgs: ['--enabled', '--ipv4'] })
    try {
      await ipv4.command(JSON.stringify({ op: 'tick' }))
      let status
      for (let i = 0; i < 50; ++i) {
        status = await ipv4.command(JSON.stringify({ op: 'ipc', command: 'web_status', request: {} }))
        if (status.running) break
        await new Promise(resolve => setTimeout(resolve, 20))
      }
      assert.deepEqual(status.addresses, [ipv4.origin])
      assert.equal((await request(undefined, { port: ipv4.port, headers: { Origin: ipv4.origin } })).status, 401)
    } finally { assert.equal(await ipv4.close(), 0) }
  })
  await check('default disabled LAN settings do not bind a port', async () => {
    assert.equal(current.enabled, false); assert.equal(current.scope, 'lan')
    assert.equal((await ipc('web_status')).running, false)
    await assert.rejects(request(), { code: 'ECONNREFUSED' })
    assert.equal((await ipc('web_pair')).errorCode, 'UNAVAILABLE')
  })
  await check('browser and anonymous identities cannot administer WebUI', async () => {
    for (const command of ['web_status', 'web_configure', 'web_reload', 'web_pair', 'web_revoke']) {
      assert.equal((await ipc(command, {}, 'browser')).errorCode, 'PERMISSION_DENIED')
      assert.equal((await ipc(command, {}, 'anonymous')).errorCode, 'UNAUTHENTICATED')
    }
  })
  await check('enable without LAN retains IPv4 and IPv6 local URLs and requires login', async () => {
    const result = await configure({ enabled: true })
    assert.equal(result.state, 'local_only'); assert.equal(result.error, null)
    assert(result.addresses.includes(fixture.origin) && result.addresses.includes(fixture.ipv6Origin))
    assert.equal((await request()).status, 401)
  })
  let logged
  await check('pairing status exposes only public session IDs and revocation takes effect', async () => {
    logged = await login()
    const status = await ipc('web_status')
    assert.deepEqual(status.sessions, [logged.id]); assert(!JSON.stringify(status).includes(logged.cookie.split('=')[1]))
    await ipc('web_revoke', { session_id: logged.id })
    assert.equal((await request(undefined, { headers: { Cookie: logged.cookie } })).status, 401)
    logged = await login()
  })
  await check('stale settings and invalid ports or origins cannot alter admission', async () => {
    assert.equal((await ipc('web_configure', { expected: { ...current, enabled: false }, settings: current })).errorCode, 'CONFLICT')
    for (const settings of [{ port: 80 }, { port: 65536 }, { scope: 'unknown' }, { publicOrigins: ['https://host'] }, { publicOrigins: ['http://host/path'] }]) {
      assert.equal((await configure(settings)).errorCode, 'INVALID_ARGUMENT')
    }
    assert.equal((await request(undefined, { headers: { Cookie: logged.cookie } })).status, 200)
  })
  await check('explicit external origin supports forwarded Host without accepting arbitrary origins', async () => {
    await configure({ publicOrigins: ['http://vm.example:9000'] })
    assert.equal((await request(undefined, { headers: { Host: 'vm.example:9000', Origin: 'http://vm.example:9000', Cookie: logged.cookie } })).status, 200)
    assert.equal((await request(undefined, { headers: { Host: 'vm.example:9000', Origin: fixture.origin, Cookie: logged.cookie } })).status, 403)
    await configure({ publicOrigins: [] })
  })
  await check('failed discovery in all mode keeps only loopback and reports degradation', async () => {
    await control({ op: 'scan', mode: 1 })
    const result = await configure({ scope: 'all' })
    assert.equal(result.state, 'local_only'); assert.equal(result.error, 'discovery_failed')
    assert.equal(result.settings.scope, 'all')
    await configure({ scope: 'lan' })
    await control({ op: 'scan', mode: 0 })
    assert.equal((await ipc('web_reload')).error, null)
    assert.equal((await request(undefined, { headers: { Cookie: logged.cookie } })).status, 200)
  })
  await check('a vanished LAN bind falls back to loopback and reports listen failure', async () => {
    await control({ op: 'scan', mode: 2 })
    const status = await ipc('web_reload')
    assert.equal(status.state, 'local_only'); assert.equal(status.error, 'listen_failed')
    assert(!status.addresses.some(value => value.includes('203.0.113.240')))
    await control({ op: 'scan', mode: 0 }); await ipc('web_reload')
  })
  await check('periodic worker discovery notices failure without any configure request', async () => {
    await control({ op: 'scan', mode: 1 })
    const end = Date.now() + 7000
    let status
    do {
      await control({ op: 'tick' })
      status = await ipc('web_status')
      if (status.error === 'discovery_failed') break
      await new Promise(resolve => setTimeout(resolve, 100))
    } while (Date.now() < end)
    assert.equal(status.error, 'discovery_failed'); assert.equal(status.state, 'local_only')
    await control({ op: 'scan', mode: 0 }); await ipc('web_reload')
  })
  await check('port replacement closes old keep-alive connections and retains valid sessions', async () => {
    const agent = new http.Agent({ keepAlive: true, maxSockets: 1 })
    try {
      await request(undefined, { agent, headers: { Cookie: logged.cookie } })
      const socket = Object.values(agent.freeSockets).flat()[0]
      assert(socket)
      const closed = once(socket, 'close')
      const oldPort = current.port
      await configure({ port: await freePort() })
      await deadline(closed)
      await assert.rejects(request(undefined, { port: oldPort }), { code: 'ECONNREFUSED' })
      assert.equal((await request(undefined, { headers: { Cookie: logged.cookie } })).status, 200)
    } finally { agent.destroy() }
  })
  await check('occupied port fails without disturbing its existing listener and can be corrected', async () => {
    const occupied = net.createServer(socket => socket.end())
    occupied.listen(0, '127.0.0.1'); await once(occupied, 'listening')
    try {
      const result = await configure({ port: occupied.address().port })
      assert.equal(result.state, 'error'); assert.equal(result.error, 'listen_failed'); assert.equal(result.running, false)
    } finally { await new Promise(resolve => occupied.close(resolve)) }
    assert.equal((await configure({ port: fixture.port })).running, true)
  })
  await check('uncertain persistence closes access and blocks retries until explicit reload', async () => {
    await control({ op: 'save', mode: 2 })
    const result = await configure({ enabled: false })
    assert.equal(result.errorCode, 'COMMIT_UNCERTAIN')
    assert.equal((await ipc('web_status')).running, false)
    assert.equal((await configure({ enabled: false })).errorCode, 'COMMIT_UNCERTAIN')
    await control({ op: 'save', mode: 0 })
    const loaded = await ipc('web_reload'); current = loaded.settings
    assert.equal(current.enabled, false); assert.equal(loaded.state, 'disabled')
    await configure({ enabled: true })
    assert.equal((await request(undefined, { headers: { Cookie: logged.cookie } })).status, 401)
  })
  await check('a failure before persistence reloads the old settings without replaying the mutation', async () => {
    const old = { ...current }
    await control({ op: 'save', mode: 1 })
    assert.equal((await configure({ enabled: false })).errorCode, 'COMMIT_UNCERTAIN')
    await control({ op: 'save', mode: 0 })
    const loaded = await ipc('web_reload'); current = loaded.settings
    assert.deepEqual(current, old); assert.equal(loaded.running, true)
  })
  await check('idempotent configuration does not persist again and maintenance is throttled', async () => {
    const before = await control({ op: 'stats' })
    await configure({})
    assert.equal((await control({ op: 'stats' })).saves, before.saves)
    const scans = (await control({ op: 'stats' })).scans
    for (let i = 0; i < 10; ++i) await control({ op: 'tick' })
    assert.equal((await control({ op: 'stats' })).scans, scans)
  })
  await check('stopping WebUI keeps shared workers alive and closes its ports', async () => {
    assert.equal((await control({ op: 'stop' })).workersAlive, true)
    await assert.rejects(request(), { code: 'ECONNREFUSED' })
    assert.equal((await ipc('web_status')).errorCode, 'UNAVAILABLE')
  })
} finally {
  const exit = await fixture.close()
  writeFileSync(join(output, 'service.log'), fixture.log())
  writeFileSync(join(output, 'result.json'), JSON.stringify({ results, exit }, null, 2))
  assert.equal(exit, 0)
}
console.log(`${results.length} WebUI service checks passed; ${output}`)
