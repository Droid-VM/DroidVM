// Real HTTP/session/tasks and UI; disk and guest work use synthetic fixtures.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { parse } = require('lossless-json')
const labels = JSON.parse(readFileSync(new URL('../../../webui/src/shared/i18n/locales/zh-Hans.json', import.meta.url), 'utf8'))
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-linux-vm-'))
const fixture = await startFixture(values.exe)
let browser, page
try {
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 320, height: 568 } })
  page = await browserContext.newPage()
  const errors = [], writes = [], reads = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/more`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.getByRole('link', { name: labels.linuxVmTitle, exact: true })).toBeVisible()
  const sessionResponse = await page.request.get(`${fixture.origin}/api/v1/session`)
  assert.equal(sessionResponse.status(), 200)
  const session = await sessionResponse.json()
  assert(session.server.capabilities.includes('disks.clone-directory.v1'))
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const post = async (path, data, status = 202) => {
    const response = await page.request.post(`${fixture.origin}/api/v1/${path}`, {
      headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken }, data: { context, ...data },
    })
    assert.equal(response.status(), status)
    return response.json()
  }
  const waitTask = async (path, id) => {
    let task
    await expect(async () => {
      task = (await (await page.request.get(`${fixture.origin}/api/v1/${path}/${id}`)).json()).task
      assert(task && ['succeeded', 'failed', 'cancelled'].includes(task.state))
    }).toPass({ timeout: 10000 })
    return task
  }
  const disk = async (id, operation) => {
    await post('disk-operations', { requestId: id, revision: 'fixture-disk-revision', vmRevision: 'fixture-reference-revision', operation })
    const task = await waitTask('disk-operations', id)
    assert.equal(task.state, 'succeeded')
    return task.diskId
  }
  // The fixture rejects every configuration commit while its one synthetic VM runs.
  const inventory = await (await page.request.get(`${fixture.origin}/api/v1/vms`)).json()
  const observed = inventory.items[0].lifecycle
  await post('vm-operations', { requestId: 'linux-fixture-stop', vmId: 'native-http-vm', action: 'stop',
    expected: { instanceId: observed.instanceId, revision: observed.revision, configRevision: observed.configRevision } })
  assert.equal((await waitTask('vm-operations', 'linux-fixture-stop')).state, 'succeeded')
  for (let i = 0; i < 2; i++) {
    const id = `linux-initial-recovery-${i}`
    await post('disk-recovery', { requestId: id })
    assert.equal((await waitTask('disk-recovery', id)).state, 'succeeded')
  }
  const base = await disk('linux-base', { action: 'create', folder: '/fixture', name: 'base.raw', format: 'raw', size: '8589934592' })
  const source = await disk('linux-source', { action: 'create', folder: '/fixture', name: 'source.qcow2', format: 'qcow2', size: '8589934592', backingId: base })
  const rawSource = await disk('linux-raw', { action: 'create', folder: '/fixture', name: 'source.raw', format: 'raw', size: '8589934592' })
  await fixture.command('agent-grow-success')
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (request.method() === 'GET') { reads.push(path); return }
    if (request.method() !== 'POST') return
    if (!['disk-operations', 'agent-operations', 'vm-configuration/commit', 'vm-configuration/defaults'].some(suffix => path.endsWith(suffix))) return
    const body = request.postDataJSON()
    // Never retain passwords in probe history or disk artifacts.
    if (body.operation?.password) delete body.operation.password
    writes.push({ path, body })
  })
  const ui = key => page.getByTestId(`linux-vm-${key}`)
  const button = key => page.getByRole('button', { name: labels[key], exact: true })
  const noOverflow = async () => assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  const step = async name => expect(ui('step')).toHaveAttribute('data-step', name)
  const settled = async () => {
    await expect(async () => {
      if (await ui('reconcile').isEnabled()) await ui('reconcile').click()
      await expect(ui('next')).toBeEnabled()
    }).toPass({ timeout: 10000 })
  }
  const next = async expected => { await settled(); await ui('next').click(); await step(expected) }
  let vmName
  const choose = async (id, name) => {
    await expect(ui('source').locator(`option[value="${id}"]`)).toHaveCount(1)
    await ui('source').selectOption(id)
    await ui('name').fill(name)
    vmName = name
    await ui('choose').click()
    await step('clone')
  }
  const diskAction = async (action, { filename, size } = {}) => {
    await ui('prepare').click()
    const dialog = page.getByRole('dialog')
    await expect(dialog).toBeVisible()
    if (filename) {
      await ui('parent-folder').fill('/fixture')
      await expect(ui('directory-name')).toHaveValue(vmName)
      await expect(ui('directory-name')).toHaveAttribute('readonly', '')
      await ui('copy-file').fill(filename)
      await expect(ui('target-path')).toContainText(`/fixture/${vmName}/${filename}`)
    }
    if (size) {
      await dialog.getByLabel(labels.diskSizeUnit).selectOption('GiB')
      await dialog.getByLabel(labels.diskNewSize, { exact: true }).fill(size)
    }
    await dialog.getByRole('button', { name: labels.diskReview, exact: true }).click()
    if (filename) await expect(ui('target-review')).toContainText(`/fixture/${vmName}/${filename}`)
    await dialog.getByLabel(labels.diskAcknowledge, { exact: true }).check()
    await dialog.getByRole('button', { name: labels.diskExecute, exact: true }).click()
    await settled()
    assert.equal(writes.filter(row => row.path.endsWith('/disk-operations')).at(-1).body.operation.action, action)
    if (filename) {
      const operation = writes.filter(row => row.path.endsWith('/disk-operations')).at(-1).body.operation
      assert.equal(operation.createFolder, true)
      assert.equal(operation.folder, `/fixture/${vmName}`)
      assert.equal(operation.name, filename)
    }
    await noOverflow()
  }
  const agentAction = async password => {
    await ui('prepare').click()
    const dialog = page.getByRole('dialog')
    if (password) {
      await page.getByTestId('agent-password').fill('Wizard123!')
      await page.getByTestId('agent-password-confirm').fill('Wizard123!')
    }
    await dialog.getByRole('checkbox').last().check()
    await page.getByTestId('agent-submit').click()
  }
  const skip = async expected => {
    await ui('skip').click()
    await page.getByRole('dialog').getByRole('button', { name: labels.linuxVmSkip, exact: true }).click()
    await step(expected)
  }
  await page.getByRole('link', { name: labels.linuxVmTitle, exact: true }).click()
  await choose(source, 'Linux 向导验证')
  await page.route('**/api/v1/disk-operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await diskAction('clone', { filename: 'linux-copy.qcow2' })
  await step('clone') // Lookup never advances a stage automatically.
  assert.equal(writes.filter(row => row.path.endsWith('/disk-operations')).length, 1)
  await next('convert')
  await next('flatten') // qcow2 needs no extra conversion.
  await diskAction('flatten')
  await next('resize')
  await diskAction('resize', { size: '16' })
  await next('password')
  await page.route('**/api/v1/agent-operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await agentAction(true)
  await settled()
  await step('password')
  assert.equal(await page.locator('input[type=password]').count(), 0)
  assert.equal(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }).includes('Wizard123!')), false)
  await next('grow')
  await agentAction(false)
  await next('configure')
  await ui('prepare').click()
  await expect(page.getByLabel(labels.name, { exact: true })).toHaveValue('Linux 向导验证')
  await button('configEditJson').click()
  const raw = page.getByRole('textbox', { name: labels.configAdvanced, exact: true })
  const draft = parse(await raw.inputValue())
  assert.equal(draft.backend, 'crosvm')
  assert.equal(draft.boot.protocol, 'uefi')
  assert.equal(draft.auto_up, false)
  assert.deepEqual(draft.disks, [{ bus: 'virtio', path: '/fixture/Linux 向导验证/linux-copy.qcow2', readonly: false }])
  await button('configApplyDraft').click()
  await noOverflow()
  await page.screenshot({ path: join(output, 'linux-configure-320.png'), fullPage: true })
  await page.route('**/api/v1/vm-configuration/commit', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await button('configSave').click()
  await page.getByRole('dialog').getByRole('button', { name: labels.configSave, exact: true }).click()
  await expect(ui('created')).toBeVisible()
  await expect(ui('records')).toContainText(labels.linuxVmRecordSucceeded)
  assert.equal(writes.filter(row => row.path.endsWith('/vm-configuration/commit')).length, 1)
  assert.equal(writes.filter(row => row.path.endsWith('/vm-configuration/defaults')).length, 1)
  const saved = await post('vm-configuration/read', { snapshotId: null, offset: 0 }, 200)
  const savedText = Buffer.concat(saved.items.filter(item => item.type === 'chunk').map(item => Buffer.from(item.data, 'base64'))).toString()
  await post('vm-configuration/release', { snapshotId: saved.snapshotId }, 200)
  const vm = parse(savedText).vms.find(row => row.id === draft.id)
  assert(vm && vm.disks[0].path === '/fixture/Linux 向导验证/linux-copy.qcow2')
  assert(savedText.includes('18446744073709551615'))
  await page.screenshot({ path: join(output, 'linux-created-320.png'), fullPage: true })

  // A new raw-image flow validates conversion and stops on uncertain maintenance.
  await ui('restart').click()
  await choose(rawSource, 'Linux 不确定验证')
  await diskAction('clone', { filename: 'raw-copy.img' })
  await next('convert')
  await diskAction('convert')
  await next('flatten')
  await next('resize') // The converted standalone copy needs no flatten.
  await skip('password')
  await skip('grow')
  await fixture.command('agent-grow-uncertain')
  await agentAction(false)
  await expect(async () => {
    if (await ui('reconcile').isEnabled()) await ui('reconcile').click()
    await expect(ui('records')).toContainText('COMMIT_UNCERTAIN')
  }).toPass({ timeout: 10000 })
  await expect(ui('next')).toBeDisabled()
  await expect(ui('skip')).toBeDisabled()
  assert.equal(writes.filter(row => row.path.endsWith('/vm-configuration/commit')).length, 1)
  const operationWrites = writes.filter(row => row.body.operation)
  assert(operationWrites.every(row => row.body.operation.action === 'clone' || ![source, rawSource, base].includes(row.body.operation.diskId)))
  const passwords = writes.filter(row => row.body.operation?.action === 'chpasswd')
  assert.equal(passwords.length, 1)
  assert.equal(passwords[0].body.operation.normalUsers, false)
  assert(reads.some(path => path.startsWith('/api/v1/disk-operations/')))
  assert(reads.some(path => path.startsWith('/api/v1/agent-operations/')))
  await noOverflow()
  await page.screenshot({ path: join(output, 'linux-uncertain-320.png'), fullPage: true })
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['clone-directory-capability-and-vm-named-target', 'qcow2-clone-and-flatten', 'raw-copy-conversion', 'copy-only-writes', 'explicit-stage-confirmation',
      'clone-password-and-vm-lost-receipts-no-resubmit', 'password-not-retained', 'resize-and-durable-maintenance',
      'host-defaults-and-classified-editor', 'single-vm-id-and-save', 'uncertain-maintenance-blocks-create', 'no-horizontal-overflow'],
    execution: 'Synthetic disks/guest receipts/persistence; no Android image operations or VM starts.' }, null, 2))
  console.log(`PASS Linux VM wizard HTTP and phone UI: ${output}`)
} catch (error) {
  if (page && !page.isClosed()) {
    await page.screenshot({ path: join(output, 'failure.png'), fullPage: true }).catch(() => {})
    writeFileSync(join(output, 'failure.txt'), await page.locator('body').innerText().catch(() => 'Page unavailable'))
  }
  throw error
} finally {
  await browser?.close()
  await fixture.close()
}
