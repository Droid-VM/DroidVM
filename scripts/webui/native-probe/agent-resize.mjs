// Focused extension of the existing HTTP fixture; no real guest/disk writes.
import assert from 'node:assert/strict'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'

export async function verifyResizeGrow({ page, expect, post, get, waitTask, diskId, output, errors }) {
  for (let index = 0; index < 2; index++) {
    const requestId = `resize-recovery-${index}`
    await post('disk-recovery', { requestId })
    assert.equal((await waitTask('disk-recovery', requestId)).state, 'succeeded')
  }
  assert.equal((await get('disk-recovery')).pendingRecords, 0)
  await page.reload()
  const writes = [], trace = []
  page.on('request', request => {
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/v1/')) return
    trace.push(`${request.method()} ${url.pathname}`)
    if (request.method() === 'POST' && /\/(disk|agent)-operations$/.test(url.pathname))
      writes.push({ path: url.pathname, ...request.postDataJSON() })
  })
  const card = page.locator('article').filter({ has: page.getByRole('heading', { name: 'agent.qcow2', exact: true }) })
  async function review(size) {
    await card.getByRole('button', { name: '磁盘详情', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: '调整容量', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('目标容量', { exact: true }).fill(size)
    await dialog.getByLabel('容量单位').selectOption('GiB')
    await dialog.getByLabel('扩容后扩展分区与文件系统', { exact: true }).check()
    await dialog.getByRole('button', { name: '核对操作与影响范围', exact: true }).click()
    await expect(dialog).toContainText('确认两个步骤')
    await dialog.getByRole('checkbox').last().check()
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
    return dialog
  }
  async function queryResize() {
    await page.locator('.disk-status').getByRole('button', { name: '查询当前状态', exact: true }).click()
  }
  // An accepted resize whose response is lost must never auto-submit autogrow,
  // even after a later GET proves that the resize succeeded.
  const lostDialog = await review('9')
  await page.route('**/api/v1/disk-operations', async route => {
    assert.equal((await route.fetch()).status(), 202)
    await route.abort()
  }, { times: 1 })
  await lostDialog.getByRole('button', { name: '执行操作', exact: true }).click()
  await expect(page.locator('.grow-status')).toContainText('第二步未自动提交')
  assert.equal((await waitTask('disk-operations', writes[0].requestId)).state, 'succeeded')
  await queryResize()
  await expect(page.locator('.disk-status')).toContainText('已完成')
  await expect(page.locator('.grow-status')).toContainText('第二步未自动提交')
  assert.equal(writes.length, 1)
  await page.screenshot({ path: join(output, 'resize-lost-response-320.png'), fullPage: true })

  const dialog = await review('10')
  await page.screenshot({ path: join(output, 'resize-grow-review-320.png') })
  const start = trace.length
  await dialog.getByRole('button', { name: '执行操作', exact: true }).click()
  await expect(async () => {
    await queryResize()
    await expect(page.locator('.grow-status')).toContainText('第二步已提交')
  }).toPass({ timeout: 10000 })
  assert.deepEqual(writes.map(row => row.operation.action), ['resize', 'resize', 'autogrow'])
  assert.equal(writes[2].operation.diskId, diskId)
  assert.equal(writes[2].revision, writes[1].revision)
  assert.equal(writes[2].vmRevision, writes[1].vmRevision)
  const second = trace.slice(start)
  const resizeAt = second.indexOf('POST /api/v1/disk-operations')
  const growAt = second.indexOf('POST /api/v1/agent-operations')
  assert(resizeAt >= 0 && growAt > resizeAt)
  assert(second.slice(resizeAt + 1, growAt).some(row => row.startsWith('GET ') && row.includes(diskId)), 'Fresh disk detail must precede autogrow')
  const result = await waitTask('agent-operations', writes[2].requestId)
  assert.equal(result.agentResult.outcome, 'uncertain')
  await expect(async () => {
    const query = page.locator('.agent-status').getByRole('button', { name: '查询当前状态', exact: true })
    if (await query.isVisible()) await query.click()
    await expect(page.locator('.agent-status')).toContainText('无法确认')
  }).toPass({ timeout: 10000 })
  assert.equal(writes.length, 3)
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: join(output, 'resize-grow-result-320.png'), fullPage: true })
  assert.deepEqual(errors, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true, width: 320,
    checks: ['explicit-two-step-review', 'lost-resize-response-stops-continuation', 'recovered-success-does-not-resume',
      'fresh-detail-before-autogrow', 'original-revisions', 'ordered-single-posts', 'separate-uncertain-result', 'no-horizontal-overflow'],
    execution: 'Real HTTP/session/task boundary with synthetic receipts; no guest or disk mutation.' }, null, 2))
  console.log(`PASS resize/autogrow HTTP and phone UI: ${output}`)
}
