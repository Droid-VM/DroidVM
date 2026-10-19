// Real HTTP/WS and browser boundary, with an in-memory rescue echo target.
import assert from 'node:assert/strict'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

export async function verifyRescue({ page, expect, post, get, waitTask, diskId, output, errors, fixture }) {
  assert((await get('session')).server.capabilities.includes('agent.rescue.v1'))
  await page.reload()
  const writes = [], sockets = []
  page.on('request', request => {
    if (request.method() === 'POST' && /\/agent-(operations|rescue-operations)$/.test(new URL(request.url()).pathname))
      writes.push({ path: new URL(request.url()).pathname, request: request.postDataJSON() })
  })
  page.on('websocket', socket => sockets.push(socket.url()))
  const card = page.locator('article').filter({ has: page.getByRole('heading', { name: 'agent.qcow2', exact: true }) })
  await card.getByRole('button', { name: '磁盘详情', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '修改系统密码', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('新密码', { exact: true }).fill('Rescue123!')
  await dialog.getByLabel('确认新密码', { exact: true }).fill('Rescue123!')
  const keep = dialog.getByLabel('操作结束后保留同一临时虚拟机，进入救援终端', { exact: true })
  await expect(keep).not.toBeChecked()
  await keep.check()
  await expect(dialog).toContainText('绝对 15 分钟')
  await dialog.getByRole('checkbox').last().check()
  await page.screenshot({ path: join(output, 'rescue-confirm-320.png') })
  await page.getByTestId('agent-submit').click()
  await expect(async () => {
    const query = page.locator('.agent-status').getByRole('button', { name: '查询当前状态', exact: true })
    if (await query.isVisible()) await query.click()
    await expect(page.locator('.agent-status')).toContainText('已交接至救援环境')
  }).toPass({ timeout: 10000 })
  assert.equal(writes.length, 1)
  assert.equal(writes[0].request.rescue, true)
  const task = await waitTask('agent-operations', writes[0].request.requestId)
  const id = task.agentResult.rescueSessionId
  assert.match(id, /^[a-f0-9]{64}$/)
  assert.equal(task.agentResult.diskId, diskId)
  assert.equal(task.agentResult.outcome, 'rescue_ready')
  assert.equal(task.agentResult.durabilityConfirmed, false)
  assert.deepEqual(task.agentResult.cleanup, { stopped: false, removed: false, diskSynced: false })
  assert.equal(task.agentResult.recoveryRequired, false)
  assert(!JSON.stringify(task).includes('Rescue123!'))
  const list = await get('agent-rescue-sessions')
  assert.equal(list.items.length, 1)
  assert.equal(list.items[0].id, id)
  const metadata = await get(`agent-rescue-terminals/${id}`)
  assert.deepEqual(metadata.expected, list.items[0].expected)
  assert.deepEqual(metadata.streams, ['uart'])
  assert.equal(await page.evaluate(async () => (await fetch('/api/v1/agent-rescue-sessions', { credentials: 'omit' })).status), 401)
  await post('agent-rescue-operations', { requestId: 'rescue-no-csrf', sessionId: id }, 403, { 'X-DroidVM-CSRF': '' })
  // A different authenticated administrator cannot list or address this target.
  const other = await page.context().browser().newContext({ locale: 'zh-CN' })
  try {
    const tab = await other.newPage()
    await tab.goto(`${fixture.origin}/#/resources/disk`)
    await tab.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
    await tab.getByRole('button', { name: '登录', exact: true }).click()
    await expect(tab.locator('.app-shell')).toBeVisible()
    assert.deepEqual((await (await tab.request.get(`${fixture.origin}/api/v1/agent-rescue-sessions`)).json()).items, [])
    assert.equal((await tab.request.get(`${fixture.origin}/api/v1/agent-rescue-terminals/${id}`)).status(), 404)
  } finally { await other.close() }
  await page.locator('.agent-status').getByRole('link', { name: '打开救援终端', exact: true }).click()
  const connect = page.getByTestId('terminal-connect')
  await expect(connect).toBeEnabled()
  await connect.click()
  await expect(page.getByTestId('terminal-status')).toHaveAttribute('data-state', 'connected')
  await expect(page.getByTestId('terminal-output')).toContainText('DroidVM rescue shell')
  const input = page.locator('.xterm-helper-textarea')
  await input.focus()
  await input.evaluate(element => {
    const data = new DataTransfer(); data.setData('text/plain', 'echo-probe')
    element.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }))
  })
  await expect(page.getByTestId('terminal-output')).toContainText('echo-probe')
  assert(sockets.some(url => url.endsWith('/api/v1/agent-rescue-terminal')))
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'rescue-terminal-320.png') })
  // Reload recovers this login's session without replaying the Agent action.
  await page.reload()
  await expect(page.locator('.rescue-panel')).toContainText(id)
  await expect(page.getByTestId('terminal-connect')).toBeEnabled()
  assert.equal(writes.filter(row => row.path.endsWith('/agent-operations')).length, 1)
  await page.locator('.rescue-panel').getByRole('button', { name: '关闭救援虚拟机', exact: true }).click()
  await dialog.getByRole('checkbox').check()
  await page.route('**/api/v1/agent-rescue-operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await dialog.getByRole('button', { name: '关闭救援虚拟机', exact: true }).click()
  await expect(async () => {
    const query = page.locator('.close-result').getByRole('button', { name: '查询当前状态', exact: true })
    if (await query.isVisible()) await query.click()
    await expect(page.locator('.close-result')).toContainText('已完成')
  }).toPass({ timeout: 10000 })
  const closed = writes.filter(row => row.path.endsWith('/agent-rescue-operations'))
  assert.equal(closed.length, 1)
  const result = await waitTask('agent-rescue-operations', closed[0].request.requestId)
  assert.deepEqual(result.agentRescueCleanup, { sessionId: id, cleanup: { stopped: true, removed: true, diskSynced: true } })
  assert.deepEqual((await get('agent-rescue-sessions')).items, [])
  await expect(page.locator('.close-result')).toContainText('不能证明来宾已卸载')
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'rescue-close-320.png'), fullPage: true })
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['explicit-opt-in', 'separate-guest-and-custody-result', 'csrf', 'owner-isolation',
      'same-observation', 'rescue-websocket-echo', 'reload-recovers-session', 'close-lost-response-query-only',
      'host-cleanup-not-guest-durability', 'no-horizontal-overflow'],
    execution: 'Synthetic rescue target only; no real guest, disk writes or native handoff.' }, null, 2))
  console.log(`PASS rescue HTTP/WS and phone UI: ${output}`)
}
