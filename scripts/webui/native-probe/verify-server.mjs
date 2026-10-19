import assert from 'node:assert/strict'
import http from 'node:http'
import net from 'node:net'
import { once } from 'node:events'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { deadline, startFixture } from './server-fixture.mjs'

const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'server-runtime-'))
const fixture = await startFixture(values.exe)
const results = [], sockets = new Set()
const request = (path = '/api/v1/session', options = {}) => new Promise((done, fail) => {
  const { body, ...settings } = options
  const headers = { Accept: 'application/json', Origin: fixture.origin, ...(body !== undefined ? { 'Content-Length': Buffer.byteLength(body) } : {}), ...settings.headers }
  const req = http.request({ hostname: '127.0.0.1', port: fixture.port, path, agent: false, ...settings, headers }, response => {
    let data = ''
    response.on('data', chunk => { data += chunk })
    response.on('error', fail)
    response.on('end', () => done({ status: response.statusCode, headers: response.headers, body: data }))
  })
  req.setTimeout(6000, () => req.destroy(new Error('HTTP deadline exceeded')))
  req.on('error', fail)
  req.end(body)
})
async function raw(text, timeout = 6000) {
  const socket = net.connect(fixture.port, '127.0.0.1'); sockets.add(socket)
  let data = ''
  socket.on('data', chunk => { data += chunk })
  socket.on('error', () => {})
  const ended = new Promise(done => socket.once('close', () => { sockets.delete(socket); done(data) }))
  await once(socket, 'connect'); socket.write(text)
  try { return await deadline(ended, timeout) } finally { socket.destroy() }
}
const rawRequest = (headers, body = '', method = 'GET', path = '/api/v1/session') =>
  `${method} ${path} HTTP/1.1\r\nHost: 127.0.0.1:${fixture.port}\r\nOrigin: ${fixture.origin}\r\nConnection: close\r\n${headers}\r\n${body}`
const denied = text => assert(!/^HTTP\/1\.[01] 2\d\d/m.test(text), 'Malformed request must not succeed')
async function check(name, run) {
  try { await run() }
  catch (cause) { throw new Error(`${name}: ${cause.message.replace(/[0-9a-f]{64}/gi, '[redacted]')}`) }
  results.push({ name, passed: true }); console.log(`PASS ${name}`)
}
async function login() {
  const grant = await fixture.command('pair')
  const result = await request('/api/v1/session', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(grant) })
  assert.equal(result.status, 201)
  return { token: grant.pairingToken, cookie: result.headers['set-cookie'][0].split(';')[0], session: JSON.parse(result.body), result }
}
let logged
try {
  await check('browser capabilities are authenticated and share the real task epoch', async () => {
    assert.equal((await request('/api/v1/capabilities')).status, 401)
    const session = await login()
    const response = await request('/api/v1/capabilities', { headers: { Cookie: session.cookie } })
    assert.equal(response.status, 200)
    assert.equal(response.headers['cache-control'], 'no-store')
    const capabilities = JSON.parse(response.body)
    assert.deepEqual(capabilities.server, session.session.server)
    assert.equal(capabilities.server.apiMajor, 1)
    assert.equal(capabilities.server.daemonEpoch, (await fixture.command('stats')).epoch)
    assert.equal(capabilities.server.versionCode, '9007199254740993')
    assert.deepEqual(capabilities.server.capabilities, ['session.v1', 'vm.list.v1', 'tasks.list.v1', 'tasks.cancel.v1', 'vm.lifecycle.v1', 'vm.logs.v1', 'events.invalidate.v1', 'vm.configuration.v1', 'disks.list.v1', 'networks.list.v1'])
    assert.equal(capabilities.context.sessionId, session.session.id)
    assert.equal(capabilities.csrfToken, undefined)
    await fixture.command(`revoke ${session.session.id}`)
    assert.equal((await request('/api/v1/capabilities', { headers: { Cookie: session.cookie } })).status, 401)
  })
  await check('existing tasks are observed and cancellation requires CSRF plus matching context', async () => {
    const session = await login()
    const { taskId } = await fixture.command('task')
    const headers = { Cookie: session.cookie, 'Content-Type': 'application/json' }
    const snapshot = await request('/api/v1/tasks', { headers })
    assert.equal(snapshot.status, 200)
    const data = JSON.parse(snapshot.body)
    assert.equal(data.cursor.epoch, session.session.server.daemonEpoch)
    assert(data.items.some(item => item.id === taskId))
    const body = JSON.stringify({ context: data.context, taskId })
    assert.equal((await request('/api/v1/tasks/cancel', { method: 'POST', headers, body })).status, 403)
    headers['X-DroidVM-CSRF'] = session.session.csrfToken
    const stale = JSON.stringify({ context: { ...data.context, daemonEpoch: 'old-process' }, taskId })
    assert.equal((await request('/api/v1/tasks/cancel', { method: 'POST', headers, body: stale })).status, 409)
    const response = await request('/api/v1/tasks/cancel', { method: 'POST', headers, body })
    assert.equal(response.status, 200)
    assert(['cancelling', 'cancelled'].includes(JSON.parse(response.body).task.state))
    assert.equal(JSON.parse(response.body).task.id, taskId)
    await fixture.command(`revoke ${session.session.id}`)
    assert.equal((await request('/api/v1/tasks', { headers })).status, 401)
  })
  await check('VM snapshots bind their response to the process and requesting browser session', async () => {
    const first = await login(), second = await login()
    for (const paired of [first, second]) {
      const response = await request('/api/v1/vms', { headers: { Cookie: paired.cookie } })
      assert.equal(response.status, 200)
      assert.deepEqual(JSON.parse(response.body).context, {
        daemonEpoch: paired.session.server.daemonEpoch, sessionId: paired.session.id,
      })
    }
    assert.notEqual(first.session.id, second.session.id)
  })
  await check('a new service process uses a distinct epoch and rejects credentials from the previous process', async () => {
    const old = await login(), next = await startFixture(values.exe)
    try {
      const target = { port: next.port, headers: { Origin: next.origin, Cookie: old.cookie } }
      assert.equal((await request('/api/v1/capabilities', target)).status, 401)
      assert.notEqual((await next.command('stats')).epoch, old.session.server.daemonEpoch)
    } finally { await next.close() }
  })
  await check('IPv4 and IPv6 listeners share a port and authenticate through the same owner', async () => {
    const dual = await startFixture(values.exe, undefined, { dualStack: true })
    try {
      const target = { hostname: '::1', port: dual.port, headers: { Origin: dual.ipv6Origin } }
      assert.equal((await request('/api/v1/session', target)).status, 401)
      const paired = await request('/api/v1/session', {
        ...target, method: 'POST', headers: { ...target.headers, 'Content-Type': 'application/json' },
        body: JSON.stringify(await dual.command('pair')),
      })
      assert.equal(paired.status, 201)
      const cookie = paired.headers['set-cookie'][0].split(';')[0]
      assert.equal((await request('/api/v1/vms', { ...target, headers: { ...target.headers, Cookie: cookie } })).status, 200)
      assert.equal((await request('/api/v1/session', { port: dual.port, headers: { Origin: dual.origin, Cookie: cookie } })).status, 200)
      assert.equal((await dual.command('stop')).workersAlive, true)
      assert.equal((await dual.command('start')).ready, true)
      assert.equal((await request('/api/v1/session', { ...target, headers: { ...target.headers, Cookie: cookie } })).status, 401)
    } finally { assert.equal(await dual.close(), 0) }
  })
  await check('unauthenticated session and VM requests return JSON 401 without invoking the service', async () => {
    const reads = (await fixture.command('stats')).reads
    for (const path of ['/api/v1/session', '/api/v1/vms']) {
      const response = await request(path)
      assert.equal(response.status, 401); assert.equal(JSON.parse(response.body).error.code, 'unauthenticated')
      assert.equal(response.headers['cache-control'], 'no-store')
    }
    assert.equal((await fixture.command('stats')).reads, reads)
  })
  await check('pairing creates a real HTTP session with an HttpOnly cookie and separate CSRF', async () => {
    logged = await login()
    assert.match(logged.result.headers['set-cookie'][0], /^droidvm-session=[0-9a-f]{64}; Path=\/; HttpOnly; SameSite=Strict$/)
    assert.equal(Object.keys(logged.session).sort().join(','), 'csrfToken,id,permissions,server')
    assert(!logged.result.body.includes(logged.cookie.split('=')[1]))
  })
  await check('consumed pairing cannot be exchanged again', async () => {
    const result = await request('/api/v1/session', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pairingToken: logged.token }) })
    assert.equal(result.status, 401)
  })
  await check('native API reads the authenticated DTO without leaking configuration', async () => {
    const result = await request('/api/v1/vms', { headers: { Cookie: logged.cookie } })
    assert.equal(result.status, 200)
    const item = JSON.parse(result.body).items[0]
    assert.equal(item.id, 'native-http-vm'); assert.equal(item.name, '原生 HTTP 虚拟机')
    assert.equal(item.cpuCount, 4); assert.equal(item.memoryMiB, 2048)
    assert(!result.body.includes('password'))
  })
  await check('HTTP keep-alive supports successive authenticated reads', async () => {
    const agent = new http.Agent({ keepAlive: true, maxSockets: 1 })
    try {
      for (let i = 0; i < 3; ++i) assert.equal((await request('/api/v1/session', { agent, headers: { Cookie: logged.cookie } })).status, 200)
    } finally { agent.destroy() }
  })
  await check('VM lifecycle uses real task admission, scoped lookup and runtime observation', async () => {
    const browser = await login()
    const headers = { Cookie: browser.cookie, 'Content-Type': 'application/json', 'X-DroidVM-CSRF': browser.session.csrfToken }
    const read = async () => JSON.parse((await request('/api/v1/vms', { headers })).body)
    const context = { daemonEpoch: browser.session.server.daemonEpoch, sessionId: browser.session.id }
    const initialEffects = (await fixture.command('stats')).vmEffects
    let original
    for (const [action, state] of [['stop', 'STOPPED'], ['start', 'RUNNING'], ['suspend', 'SUSPENDED'], ['resume', 'RUNNING'], ['reboot', 'RUNNING']]) {
      const snapshot = await read(), lifecycle = snapshot.items[0].lifecycle
      assert(lifecycle.actions.includes(action))
      const input = { context, requestId: `probe-${action}`, vmId: 'native-http-vm', action,
        expected: { instanceId: lifecycle.instanceId, revision: lifecycle.revision, configRevision: lifecycle.configRevision } }
      if (!original) original = input
      const response = await request('/api/v1/vm-operations', { method: 'POST', headers, body: JSON.stringify(input) })
      assert.equal(response.status, 202)
      const admitted = JSON.parse(response.body).task
      let task
      for (let attempt = 0; attempt < 100; attempt++) {
        task = JSON.parse((await request(`/api/v1/vm-operations/${input.requestId}`, { headers })).body).task
        if (task && ['succeeded', 'failed', 'cancelled'].includes(task.state)) break
        await new Promise(resolve => setTimeout(resolve, 20))
      }
      assert.equal(task.id, admitted.id); assert.equal(task.state, 'succeeded'); assert.equal(task.phase, 'admitted')
      assert.equal((await read()).items[0].state, state)
      const duplicate = await request('/api/v1/vm-operations', { method: 'POST', headers, body: JSON.stringify(input) })
      assert.equal(duplicate.status, 202); assert.equal(JSON.parse(duplicate.body).task.id, admitted.id)
    }
    assert.equal((await fixture.command('stats')).vmEffects, initialEffects + 5)
    const conflicting = { ...original, action: 'start' }
    assert.equal((await request('/api/v1/vm-operations', { method: 'POST', headers, body: JSON.stringify(conflicting) })).status, 409)
    const stale = { ...original, requestId: 'probe-stale' }
    assert.equal((await request('/api/v1/vm-operations', { method: 'POST', headers, body: JSON.stringify(stale) })).status, 202)
    let failure
    for (let attempt = 0; attempt < 100; attempt++) {
      failure = JSON.parse((await request('/api/v1/vm-operations/probe-stale', { headers })).body).task
      if (failure?.state === 'failed') break
      await new Promise(resolve => setTimeout(resolve, 20))
    }
    assert.equal(failure.errorCode, 'CONFLICT')
    assert.equal((await fixture.command('stats')).vmEffects, initialEffects + 5)
    const other = await login()
    const lookup = await request('/api/v1/vm-operations/probe-stop', { headers: { Cookie: other.cookie } })
    assert.equal(lookup.status, 200); assert.equal(JSON.parse(lookup.body).task, null)
  })
  await check('HEAD errors do not include a body and empty POST does not hang', async () => {
    const agent = new http.Agent({ keepAlive: true, maxSockets: 1 })
    try {
      const head = await request('/api/v1/session', { method: 'HEAD', agent, headers: { Cookie: logged.cookie } })
      assert.equal(head.status, 405); assert.equal(head.body, '')
      assert.equal((await request('/api/v1/session', { agent, headers: { Cookie: logged.cookie } })).status, 200)
    } finally { agent.destroy() }
    assert.equal((await request('/api/v1/session', { method: 'POST', body: '', headers: { 'Content-Type': 'application/json' } })).status, 400)
  })
  for (const [name, headers] of [
    ['wrong Host', { Host: 'other.invalid' }], ['wrong Origin', { Origin: 'http://other.invalid' }],
    ['crossed allowed Host and Origin', { Host: '127.0.0.1:5178' }], ['opaque Origin', { Origin: 'null' }],
  ]) await check(name + ' is denied', async () => {
    assert.equal((await request('/api/v1/session', { headers: { Cookie: logged.cookie, ...headers } })).status, 403)
  })
  await check('missing Origin cannot log out a session', async () => {
    const result = await raw(rawRequest(`Cookie: ${logged.cookie}\r\nX-DroidVM-CSRF: ${logged.session.csrfToken}\r\n`, '', 'DELETE').replace(`Origin: ${fixture.origin}\r\n`, ''))
    assert.match(result, /^HTTP\/1\.[01] 403/)
  })
  for (const [name, headers, method] of [
    ['Host', `Host: 127.0.0.1:${fixture.port}\r\n`, 'GET'],
    ['Origin', `Origin: ${fixture.origin}\r\n`, 'GET'],
    ['Cookie', `Cookie: ${logged.cookie}\r\nCookie: unrelated=value\r\n`, 'GET'],
    ['Content-Length', 'Content-Length: 0\r\nContent-Length: 0\r\n', 'POST'],
    ['custom CSRF', `Cookie: ${logged.cookie}\r\nX-DroidVM-CSRF: ${logged.session.csrfToken}\r\nx-droidvm-csrf: wrong\r\n`, 'DELETE'],
  ]) await check(`duplicate ${name} is not normalized into an accepted request`, async () => denied(await raw(rawRequest(headers, '', method))))
  await check('chunked, oversized and truncated request bodies cannot reach the API', async () => {
    denied(await raw(rawRequest('Transfer-Encoding: chunked\r\nContent-Type: application/json\r\n', '0\r\n\r\n', 'POST')))
    const large = await raw(rawRequest('Content-Length: 4097\r\nContent-Type: application/json\r\n', '', 'POST'))
    assert.match(large, /^HTTP\/1\.[01] 413/)
    denied(await raw(rawRequest('Content-Length: 123\r\nContent-Type: application/json\r\n', '{', 'POST')))
  })
  await check('URL arguments, unconfigured API routes and WebSocket upgrades fail closed', async () => {
    assert.equal((await request('/api/v1/session?pairingToken=not-a-credential')).status, 400)
    assert.equal((await request('/api/v1/missing')).status, 404)
    const upgrade = await raw(rawRequest('Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n', '', 'GET', '/api/v1/session').replace('Connection: close', 'Connection: Upgrade'))
    assert(!upgrade.includes('101 Switching'))
  })
  await check('fragmented JSON body is collected before dispatch', async () => {
    const grant = await fixture.command('pair')
    const body = JSON.stringify(grant)
    const socket = net.connect(fixture.port, '127.0.0.1'); sockets.add(socket)
    let result = ''; socket.on('data', chunk => { result += chunk }); socket.on('error', () => {})
    const ended = new Promise(done => socket.once('close', done))
    await once(socket, 'connect')
    socket.write(rawRequest(`Content-Type: application/json\r\nContent-Length: ${body.length}\r\n`, body.slice(0, 10), 'POST'))
    await new Promise(done => setTimeout(done, 50)); socket.write(body.slice(10))
    await deadline(ended); sockets.delete(socket)
    assert.match(result, /^HTTP\/1\.[01] 201/)
  })
  await check('slow domain work does not block the network loop or a second worker', async () => {
    await fixture.command('delay 1000')
    const slow = request('/api/v1/vms', { headers: { Cookie: logged.cookie } })
    await new Promise(done => setTimeout(done, 100))
    const started = performance.now()
    assert.equal((await request('/api/v1/session', { headers: { Cookie: logged.cookie } })).status, 200)
    assert(performance.now() - started < 700)
    assert.equal((await slow).status, 200)
    await fixture.command('delay 0')
  })
  await check('large responses are sent in order across writable callbacks', async () => {
    await fixture.command('large 524288')
    const response = await request('/api/v1/vms', { headers: { Cookie: logged.cookie } })
    assert.equal(response.status, 200)
    assert.equal(JSON.parse(response.body).items[0].name.length, 524288)
    await fixture.command('large 0')
  })
  await check('response retention limits exert backpressure across concurrent real connections', async () => {
    await fixture.command('delay 400')
    const responses = await Promise.all(Array.from({ length: 12 }, () => request('/api/v1/vms', { headers: { Cookie: logged.cookie } })))
    assert(responses.some(response => response.status === 200))
    assert(responses.some(response => response.status === 429))
    assert(responses.every(response => response.status === 200 || response.status === 429))
    await fixture.command('delay 0')
    assert.equal((await request('/api/v1/vms', { headers: { Cookie: logged.cookie } })).status, 200)
  })
  await check('disconnecting a running read cannot deliver its response to a new connection', async () => {
    await fixture.command('delay 800')
    const old = http.get({ hostname: '127.0.0.1', port: fixture.port, path: '/api/v1/vms', agent: false, headers: { Cookie: logged.cookie } })
    old.on('error', () => {})
    await new Promise(done => setTimeout(done, 100)); old.destroy()
    const next = await request('/api/v1/session', { headers: { Cookie: logged.cookie } })
    assert.equal(next.status, 200); assert.equal(JSON.parse(next.body).id, logged.session.id)
    await new Promise(done => setTimeout(done, 850)); await fixture.command('delay 0')
    assert.equal((await request('/api/v1/vms', { headers: { Cookie: logged.cookie } })).status, 200)
  })
  await check('expired server sessions fail through the actual HTTP boundary', async () => {
    await fixture.command('expire')
    assert.equal((await request('/api/v1/session', { headers: { Cookie: logged.cookie } })).status, 401)
    logged = await login()
  })
  await check('revocation invalidates an already issued cookie', async () => {
    await fixture.command(`revoke ${logged.session.id}`)
    assert.equal((await request('/api/v1/vms', { headers: { Cookie: logged.cookie } })).status, 401)
    logged = await login()
  })
  await check('logout requires CSRF and deletes the exact cookie', async () => {
    const options = { method: 'DELETE', headers: { Cookie: logged.cookie } }
    assert.equal((await request('/api/v1/session', options)).status, 403)
    const response = await request('/api/v1/session', { ...options, headers: { ...options.headers, 'X-DroidVM-CSRF': logged.session.csrfToken } })
    assert.equal(response.status, 204); assert.equal(response.body, '')
    assert(response.headers['set-cookie'][0].endsWith('Max-Age=0'))
    assert.equal((await request('/api/v1/session', { headers: { Cookie: logged.cookie } })).status, 401)
  })
  await check('kernel peer policy cannot be bypassed by a forwarding header', async () => {
    await fixture.command('deny')
    denied(await raw(rawRequest('X-Forwarded-For: 127.0.0.1\r\n')))
    await fixture.command('allow')
  })
  await check('stopping and restarting the listener preserves workers and invalidates sessions', async () => {
    logged = await login()
    assert.equal((await fixture.command('stop')).workersAlive, true)
    assert.equal((await fixture.command('start')).ready, true)
    assert.equal((await request('/api/v1/session', { headers: { Cookie: logged.cookie } })).status, 401)
    logged = await login()
  })
  await check('a second listener reports an occupied port without disturbing the original', async () => {
    await assert.rejects(startFixture(values.exe, fixture.port))
    assert.equal((await request('/api/v1/session', { headers: { Cookie: logged.cookie } })).status, 200)
  })
  assert.equal(await fixture.close(), 0)
  writeFileSync(join(output, 'result.json'), JSON.stringify({ passed: results.length, results, exitCode: 0 }, null, 2))
  console.log(`${results.length} native HTTP checks passed; ${output}`)
} catch (cause) {
  writeFileSync(join(output, 'result.json'), JSON.stringify({ passed: results.length, results, failed: true, reason: cause.message }, null, 2))
  throw cause
} finally {
  for (const socket of sockets) socket.destroy()
  await fixture.close()
  writeFileSync(join(output, 'service.log'), fixture.log())
}
