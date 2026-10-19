import assert from 'node:assert/strict'
import http from 'node:http'
import { readFile, readdir, mkdir, mkdtemp, writeFile } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { parseArgs } from 'node:util'
import { gunzipSync } from 'node:zlib'
import { startFixture } from './server-fixture.mjs'

const { values } = parseArgs({ options: { exe: { type: 'string' }, dist: { type: 'string', default: 'webui/dist' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
await mkdir(values.output, { recursive: true })
const output = await mkdtemp(join(resolve(values.output), 'static-runtime-'))
const fixture = await startFixture(values.exe)
const results = []
const request = (path, options = {}) => new Promise((done, fail) => {
  const req = http.request(fixture.origin + path, { agent: false, ...options }, response => {
    const chunks = []
    response.on('data', chunk => chunks.push(chunk))
    response.on('error', fail)
    response.on('end', () => done({ status: response.statusCode, headers: response.headers, body: Buffer.concat(chunks) }))
  })
  req.setTimeout(6000, () => req.destroy(new Error('Static HTTP timeout')))
  req.on('error', fail); req.end()
})
async function check(name, run) {
  await run(); results.push({ name, passed: true }); console.log(`PASS ${name}`)
}
try {
  const paths = ['index.html', ...(await readdir(join(values.dist, 'assets'))).map(name => 'assets/' + name)]
  for (const path of paths) await check(`Exact production bytes, gzip, HEAD and ETag: ${path}`, async () => {
    const expected = await readFile(join(values.dist, path))
    const url = path === 'index.html' ? '/' : '/' + path
    const identity = await request(url)
    assert.equal(identity.status, 200)
    assert.deepEqual(identity.body, expected)
    assert.equal(Number(identity.headers['content-length']), expected.length)
    assert.equal(identity.headers['x-content-type-options'], 'nosniff')
    assert.equal(identity.headers['set-cookie'], undefined)
    assert.equal(identity.headers['cache-control'], path === 'index.html' ? 'no-cache' : 'public, max-age=31536000, immutable')
    const gzip = await request(url, { headers: { 'Accept-Encoding': 'gzip' } })
    assert.equal(gzip.status, 200)
    assert.equal(gzip.headers['content-encoding'], 'gzip')
    assert.deepEqual(gunzipSync(gzip.body), expected)
    assert.equal(Number(gzip.headers['content-length']), gzip.body.length)
    assert.notEqual(gzip.headers.etag, identity.headers.etag)
    const head = await request(url, { method: 'HEAD', headers: { 'Accept-Encoding': 'gzip' } })
    assert.equal(head.status, 200); assert.equal(head.body.length, 0)
    assert.equal(head.headers['content-length'], gzip.headers['content-length'])
    const cached = await request(url, { headers: { 'Accept-Encoding': 'gzip', 'If-None-Match': 'W/' + gzip.headers.etag } })
    assert.equal(cached.status, 304); assert.equal(cached.body.length, 0)
    assert.equal(cached.headers['content-length'], undefined)
    const plain = await request(url, { headers: { 'Accept-Encoding': 'gzip;q=0' } })
    assert.deepEqual(plain.body, expected); assert.equal(plain.headers['content-encoding'], undefined)
    assert.equal((await request(url, { headers: { 'Accept-Encoding': '*;q=0' } })).status, 406)
  })
  await check('Index alias; no SPA fallback for missing files or APIs', async () => {
    assert.equal((await request('/index.html')).status, 200)
    for (const path of ['/api/v1/missing', '/assets/missing.js', '/dev.html', '/.env', '/vms', '/assets/index.js.map']) {
      const missing = await request(path)
      assert.equal(missing.status, 404)
      assert(missing.headers['content-type'].startsWith('application/json'))
    }
    assert.equal((await request('/api/v1/vms')).status, 401)
    assert.equal((await request('/?pairingToken=not-a-token')).status, 400)
  })
  await check('Static access obeys Host, Origin, method and source policy', async () => {
    assert.equal((await request('/', { headers: { Host: 'evil.invalid' } })).status, 403)
    assert.equal((await request('/', { headers: { Origin: 'http://evil.invalid' } })).status, 403)
    const post = await request('/', { method: 'POST', headers: { Origin: fixture.origin } })
    assert.equal(post.status, 405); assert.equal(post.headers.allow, 'GET, HEAD')
    await fixture.command('deny')
    await assert.rejects(request('/'))
    await fixture.command('allow')
  })
  assert.equal(await fixture.close(), 0)
  await writeFile(join(output, 'result.json'), JSON.stringify({ passed: results.length, results }, null, 2))
  console.log(`Static production HTTP checks passed; ${output}`)
} finally {
  await fixture.close()
  await writeFile(join(output, 'service.log'), fixture.log())
}
