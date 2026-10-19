// Native fixture driver. Pairing credentials travel only over inherited pipes;
// stdout/control values must never be copied into a persistent service log.
import { spawn } from 'node:child_process'
import { createInterface } from 'node:readline'
import { once } from 'node:events'
import net from 'node:net'
import { resolve } from 'node:path'

export function deadline(promise, ms = 7000) {
  let timer
  return Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('Native fixture operation timed out')), ms)
  })]).finally(() => clearTimeout(timer))
}
export async function freePort() {
  const socket = net.createServer()
  socket.listen(0, '127.0.0.1')
  await once(socket, 'listening')
  const port = socket.address().port
  await new Promise((done, fail) => socket.close(error => error ? fail(error) : done()))
  return port
}
export async function startFixture(exe, port, { dualStack = false, extraArgs = [] } = {}) {
  port ??= await freePort()
  const child = spawn(resolve(exe), [String(port), ...(dualStack ? ['--dual-stack'] : []), ...extraArgs], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] })
  const messages = [], waiting = []
  let ended = false, log = ''
  const lines = createInterface({ input: child.stdout })
  lines.on('line', line => {
    let value
    try { value = JSON.parse(line) } catch { child.kill(); return }
    const receiver = waiting.shift()
    if (receiver) receiver.resolve(value)
    else messages.push(value)
  })
  child.stderr.on('data', chunk => { log += chunk.toString() })
  const done = new Promise(resolveDone => {
    const finish = code => {
      ended = true
      for (const receiver of waiting.splice(0)) receiver.reject(new Error('Native fixture exited'))
      resolveDone(code)
    }
    child.once('error', () => finish(-1))
    child.once('exit', finish)
  })
  const next = () => messages.length ? Promise.resolve(messages.shift()) : ended
    ? Promise.reject(new Error('Native fixture exited'))
    : new Promise((resolve, reject) => waiting.push({ resolve, reject }))
  const fixture = {
    port, origin: `http://127.0.0.1:${port}`, ipv6Origin: `http://[::1]:${port}`, done,
    log: () => log.replace(/[0-9a-f]{64}/gi, '[redacted]'),
    async command(command) { const result = next(); child.stdin.write(command + '\n'); return deadline(result) },
    async close() {
      if (!ended) child.stdin.end('quit\n')
      try { return await deadline(done) } catch { child.kill(); return deadline(done) }
    },
  }
  try {
    const ready = await deadline(next())
    if (!ready.ready) throw new Error('Native fixture did not become ready')
    return fixture
  } catch (cause) { await fixture.close(); throw cause }
}
