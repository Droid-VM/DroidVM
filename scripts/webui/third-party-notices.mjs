// Retain upstream notices alongside the APK's embedded WebUI/native resources.
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { parseArgs } from 'node:util'

const { values } = parseArgs({ options: { modules: { type: 'string' }, lws: { type: 'string' }, output: { type: 'string' } } })
if (!values.modules || !values.lws || !values.output) throw new Error('--modules, --lws and --output are required')
const notices = ['DroidVM WebUI — third-party notices\n\nIncludes runtime packages and their shared upstream notices.\n']
// The serial terminal embeds xterm.js and its local layout addon (both MIT).
for (const name of ['vue', '@vue/runtime-dom', '@vue/runtime-core', '@vue/reactivity', '@vue/shared', '@vue/devtools-api', 'vue-router', 'vue-i18n', '@intlify/core-base', '@intlify/message-compiler', '@intlify/shared', 'source-map-js', 'ajv', 'lossless-json', '@xterm/xterm', '@xterm/addon-fit', '@novnc/novnc', '@noble/hashes']) {
  const root = join(values.modules, name)
  const manifest = JSON.parse(await readFile(join(root, 'package.json'), 'utf8'))
  const licenses = (await readdir(root)).filter(name => /^licen[sc]e(?:\..*)?$/i.test(name)).sort()
  notices.push(`\n=== ${name} ${manifest.version} (${manifest.license}) ===\n`)
  if (!licenses.length) {
    if (name !== '@vue/devtools-api' || manifest.version !== '6.6.4') throw new Error(`Missing upstream license: ${name}`)
    notices.push(await readFile(new URL('./licenses/vue-devtools-api-6.6.4.txt', import.meta.url), 'utf8'))
  }
  for (const license of licenses) notices.push(await readFile(join(root, license), 'utf8'))
  if (name === '@novnc/novnc') {
    notices.push('Unmodified upstream source: https://github.com/novnc/noVNC/tree/v' + manifest.version + '\n')
    for (const entry of ['AUTHORS', 'docs/LICENSE.MPL-2.0', 'docs/LICENSE.BSD-2-Clause', 'docs/LICENSE.BSD-3-Clause', 'docs/LICENSE.OFL-1.1', 'vendor/pako/LICENSE']) {
      notices.push('\n=== noVNC ' + entry + ' ===\n', await readFile(join(root, entry), 'utf8'))
    }
    const des = await readFile(join(root, 'core/crypto/des.js'), 'utf8')
    if (!des.startsWith('/*') || !des.includes('*/')) throw new Error('Missing noVNC DES notices')
    notices.push('\n=== noVNC DES upstream attribution ===\n', des.slice(0, des.indexOf('*/') + 2))
  }
}
notices.push('\n=== libwebsockets (version and hash pinned in native-probe/dependencies.json) ===\n')
notices.push(await readFile(join(values.lws, 'LICENSE'), 'utf8'))
const sha1 = await readFile(join(values.lws, 'lib/misc/sha-1.c'), 'utf8')
if (!sha1.startsWith('/*') || !sha1.includes('*/')) throw new Error('Missing SHA-1 BSD notice')
notices.push('\n=== libwebsockets SHA-1: WIDE Project BSD-3-Clause ===\n', sha1.slice(0, sha1.indexOf('*/') + 2))
const output = resolve(values.output)
await mkdir(dirname(output), { recursive: true })
await writeFile(output, notices.join('\n'))
console.log('WebUI third-party notices generated')
