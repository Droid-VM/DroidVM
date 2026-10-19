// Two independent TCP/RFB peers through the real native HTTP/WS bridge.
// Synthetic peers do not establish actual VM multimonitor or physical-phone support.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { parseArgs } from 'node:util'
import { startFixture } from './server-fixture.mjs'
import { startRfbFixture } from './rfb-fixture.mjs'

const require = createRequire(new URL('../../../webui/package.json', import.meta.url))
const { chromium, expect } = require('@playwright/test')
const { values } = parseArgs({ options: { exe: { type: 'string' }, output: { type: 'string', default: 'build/webui-native' } } })
assert(values.exe, '--exe is required')
mkdirSync(values.output, { recursive: true })
const output = mkdtempSync(join(resolve(values.output), 'native-multiscreen-'))
let first, second, fixture, browser
try {
  first = await startRfbFixture()
  second = await startRfbFixture({ foreground: [70, 210, 100], background: [120, 40, 180] })
  assert.notEqual(first.port, second.port)
  fixture = await startFixture(values.exe)
  await fixture.command(`display-endpoint ${first.port}`)
  await fixture.command(`display-secondary-endpoint ${second.port}`)
  browser = await chromium.launch({ channel: 'msedge', headless: true })
  const browserContext = await browser.newContext({ locale: 'zh-CN', viewport: { width: 1440, height: 1000 } })
  const page = await browserContext.newPage(), errors = []
  page.setDefaultTimeout(10000)
  page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${fixture.origin}/#/vms`)
  await page.locator('#pairing-token').fill((await fixture.command('pair')).pairingToken)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.login-screen')).toHaveCount(0)
  const get = async path => {
    const response = await page.request.get(`${fixture.origin}/api/v1/${path}`)
    assert.equal(response.status(), 200, await response.text())
    return response.json()
  }
  const session = await get('session')
  const context = { daemonEpoch: session.server.daemonEpoch, sessionId: session.id }
  const vm = (await get('vms')).items.find(item => item.id === 'native-http-vm')
  assert(vm)
  const started = await page.request.post(`${fixture.origin}/api/v1/vm-operations`, {
    headers: { Origin: fixture.origin, 'X-DroidVM-CSRF': session.csrfToken },
    data: { context, requestId: 'multiscreen-start', vmId: vm.id, action: 'start', expected: {
      instanceId: vm.lifecycle.instanceId, revision: vm.lifecycle.revision, configRevision: vm.lifecycle.configRevision,
    } },
  })
  assert.equal(started.status(), 202, await started.text())
  await expect(async () => assert.equal((await get(`vm-displays/${vm.id}`)).screens.filter(screen => screen.available).length, 2)).toPass({ timeout: 10000 })
  await page.goto(`${fixture.origin}/#/vms/${vm.id}/console`)
  const pane = name => page.getByTestId(`graphic-pane-${name}`)
  const primary = pane('primary'), secondary = pane('secondary')
  const ui = (scope, key) => scope.getByTestId(`graphic-${key}`)
  const downKeys = peer => peer.stats().keys.filter(key => key.down).map(key => key.key)
  const connect = async (scope, peer, initial, color) => {
    const authenticated = peer.stats().authenticated
    await ui(scope, initial ? 'connect' : 'reconnect').click()
    await expect.poll(() => peer.stats().authenticated).toBe(authenticated + 1)
    await expect(ui(scope, 'canvas')).toBeVisible()
    await expect.poll(() => ui(scope, 'canvas').evaluate(element => [...element.getContext('2d').getImageData(10, 10, 1, 1).data])).toEqual([...color, 255])
  }
  const assertLayout = async width => {
    await page.setViewportSize({ width, height: width === 320 ? 568 : 1000 })
    await expect(ui(primary, 'canvas')).toBeVisible()
    await expect(ui(secondary, 'canvas')).toBeVisible()
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    const boxes = []
    for (const [name, scope] of [['primary', primary], ['secondary', secondary]]) {
      const canvas = ui(scope, 'canvas')
      await canvas.scrollIntoViewIfNeeded()
      try {
        await expect(canvas).toBeInViewport({ ratio: 0.95 })
      } catch (failure) {
        const diagnostic = await canvas.evaluate(async element => {
          const rect = value => ({ x: value.x, y: value.y, width: value.width, height: value.height, top: value.top, right: value.right, bottom: value.bottom, left: value.left })
          const describe = node => {
            const style = getComputedStyle(node)
            return { tag: node.tagName, id: node.id, testid: node.getAttribute('data-testid'), class: node.className,
              rect: rect(node.getBoundingClientRect()), clientWidth: node.clientWidth, clientHeight: node.clientHeight,
              scrollWidth: node.scrollWidth, scrollHeight: node.scrollHeight, scrollTop: node.scrollTop, scrollLeft: node.scrollLeft,
              overflowX: style.overflowX, overflowY: style.overflowY, position: style.position,
              top: style.top, bottom: style.bottom, height: style.height, minHeight: style.minHeight, maxHeight: style.maxHeight }
          }
          const ancestors = []
          for (let node = element; node; node = node.parentElement) ancestors.push(describe(node))
          const intersection = await new Promise(resolve => {
            const observer = new IntersectionObserver(entries => {
              const entry = entries[0]
              resolve({ ratio: entry.intersectionRatio, rect: rect(entry.intersectionRect), root: entry.rootBounds && rect(entry.rootBounds) })
              observer.disconnect()
            })
            observer.observe(element)
          })
          const bounds = element.getBoundingClientRect()
          const hitSamples = [0.05, 0.5, 0.95].map(fraction => {
            const x = bounds.left + bounds.width / 2, y = bounds.top + bounds.height * fraction
            return { x, y, stack: document.elementsFromPoint(x, y).slice(0, 5).map(node => ({ tag: node.tagName, testid: node.getAttribute('data-testid'), class: node.className })) }
          })
          return { viewport: { width: innerWidth, height: innerHeight, scrollX, scrollY,
            visual: visualViewport && { width: visualViewport.width, height: visualViewport.height, offsetTop: visualViewport.offsetTop, offsetLeft: visualViewport.offsetLeft } },
          intersection, ancestors, hitSamples,
          fixedOrSticky: [...document.querySelectorAll('*')].filter(node => ['fixed', 'sticky'].includes(getComputedStyle(node).position)).map(describe).slice(0, 24) }
        })
        const prefix = `multiscreen-${width}-${name}-failure`
        writeFileSync(join(output, `${prefix}.json`), JSON.stringify(diagnostic, null, 2))
        await page.screenshot({ path: join(output, `${prefix}.png`) })
        console.error(`Multiscreen viewport diagnostic: ${join(output, prefix)}`)
        throw failure
      }
      const box = await canvas.boundingBox()
      assert(box && box.width > 0 && box.x >= 0 && box.x + box.width <= width + 1)
      boxes.push(box)
      if (width === 320) await page.screenshot({ path: join(output, `multiscreen-${width}-${name}.png`) })
    }
    if (width > 320) {
      assert(boxes[0].x + boxes[0].width <= boxes[1].x, 'Desktop canvases must be side by side')
      await expect(ui(primary, 'canvas')).toBeInViewport({ ratio: 0.95 })
      await expect(ui(secondary, 'canvas')).toBeInViewport({ ratio: 0.95 })
      await page.screenshot({ path: join(output, `multiscreen-${width}.png`) })
    }
  }
  await expect(primary).toBeVisible()
  await expect(secondary).toHaveCount(0)
  await connect(primary, first, true, [230, 80, 40])
  await page.getByTestId('graphic-add-screen').click()
  await expect(secondary).toBeVisible()
  await expect(ui(secondary, 'screen')).toHaveValue('simplefb')
  assert.equal(second.stats().accepted, 0, 'Adding a pane must not connect without a click')
  await expect(page.getByTestId('graphic-add-screen')).toHaveCount(0)
  assert.equal(await page.getByTestId(/^graphic-pane-/).count(), 2)
  for (const [scope, occupied] of [[primary, 'simplefb'], [secondary, 'gpu-0']]) {
    assert.equal(await ui(scope, 'screen').evaluate((element, id) => [...element.options].some(option => option.value === id && !option.disabled), occupied), false)
  }
  await connect(secondary, second, true, [70, 210, 100])
  // Each canvas has its own actual pixels, not two views onto the same RFB stream.
  assert.deepEqual(await ui(primary, 'canvas').evaluate(element => [...element.getContext('2d').getImageData(200, 200, 1, 1).data]), [40, 120, 210, 255])
  assert.deepEqual(await ui(secondary, 'canvas').evaluate(element => [...element.getContext('2d').getImageData(200, 200, 1, 1).data]), [120, 40, 180, 255])
  await ui(primary, 'canvas').click({ position: { x: 15, y: 15 } })
  await page.keyboard.press('a')
  await expect.poll(() => downKeys(first)).toContain(97)
  assert(!downKeys(second).includes(97))
  assert(first.stats().pointers.some(pointer => pointer.buttons === 1))
  assert(!second.stats().pointers.some(pointer => pointer.buttons === 1))
  // Hold both a key and mouse button, then focus a secondary control without
  // a physical keyup/mouseup. The previous viewer must explicitly release them.
  await page.keyboard.down('b')
  await page.mouse.down()
  await expect.poll(() => first.stats().keys.some(key => key.key === 98 && key.down)).toBe(true)
  await expect.poll(() => first.stats().pointers.at(-1)?.buttons).toBe(1)
  await ui(secondary, 'focus-screen').focus()
  await expect(secondary).toHaveAttribute('data-input-active', 'true')
  await expect(primary).toHaveAttribute('data-input-active', 'false')
  await expect.poll(() => first.stats().keys.some(key => key.key === 98 && !key.down)).toBe(true)
  await expect.poll(() => first.stats().pointers.at(-1)?.buttons).toBe(0)
  await page.keyboard.up('b')
  await page.mouse.up()
  assert(!downKeys(second).includes(98))
  const primaryKeyCount = first.stats().keys.length
  await ui(secondary, 'canvas').click({ position: { x: 15, y: 15 } })
  await page.keyboard.press('s')
  await expect.poll(() => downKeys(second)).toContain(115)
  assert.equal(first.stats().keys.length, primaryKeyCount)
  assert(second.stats().pointers.some(pointer => pointer.buttons === 1))
  await assertLayout(1440)
  await assertLayout(320)
  const secondaryAccepted = second.stats().accepted, secondaryKeys = downKeys(second)
  second.dropClients()
  await expect.poll(() => second.stats().connected).toBe(0)
  await expect(ui(secondary, 'reconnect')).toBeEnabled()
  await expect(ui(primary, 'canvas')).toBeVisible()
  await ui(primary, 'canvas').click({ position: { x: 15, y: 15 } })
  await page.keyboard.press('c')
  await expect.poll(() => downKeys(first)).toContain(99)
  assert.equal(first.stats().connected, 1, 'A failed secondary connection must not close primary')
  assert.equal(second.stats().accepted, secondaryAccepted, 'Failure must not reconnect automatically')
  await connect(secondary, second, false, [70, 210, 100])
  assert.deepEqual(downKeys(second), secondaryKeys, 'Reconnect must not replay earlier input')
  await ui(secondary, 'canvas').click({ position: { x: 15, y: 15 } })
  await page.keyboard.down('d')
  await page.mouse.down()
  await expect.poll(() => second.stats().keys.some(key => key.key === 100 && key.down)).toBe(true)
  await expect.poll(() => second.stats().pointers.at(-1)?.buttons).toBe(1)
  // Focus transfers release input before the removal tears down its stream.
  await page.getByTestId('graphic-remove-screen').focus()
  await expect.poll(() => second.stats().keys.some(key => key.key === 100 && !key.down)).toBe(true)
  await expect.poll(() => second.stats().pointers.at(-1)?.buttons).toBe(0)
  await page.keyboard.up('d')
  await page.mouse.up()
  await page.getByTestId('graphic-remove-screen').click()
  await expect(secondary).toHaveCount(0)
  await expect.poll(() => second.stats().connected).toBe(0)
  assert(second.stats().keys.some(key => key.key === 100 && !key.down))
  assert.equal(second.stats().pointers.at(-1)?.buttons, 0)
  assert.equal(first.stats().connected, 1)
  const removedAccepted = second.stats().accepted
  await page.getByTestId('graphic-add-screen').click()
  await expect(secondary).toBeVisible()
  assert.equal(second.stats().accepted, removedAccepted)
  await connect(secondary, second, true, [70, 210, 100])
  await fixture.command('terminal-change')
  await expect.poll(() => [first.stats().connected, second.stats().connected]).toEqual([0, 0])
  await expect(ui(primary, 'reconnect')).toBeEnabled()
  await expect(ui(secondary, 'reconnect')).toBeEnabled()
  await connect(primary, first, false, [230, 80, 40])
  await connect(secondary, second, false, [70, 210, 100])
  await fixture.command(`revoke ${session.id}`)
  await expect.poll(() => [first.stats().connected, second.stats().connected]).toEqual([0, 0])
  assert.deepEqual(errors, [])
  assert.deepEqual(first.stats().faults, [])
  assert.deepEqual(second.stats().faults, [])
  writeFileSync(join(output, 'results.json'), JSON.stringify({ passed: true,
    checks: ['two-independent-rfb-endpoints-and-distinct-real-pixels', 'add-prepares-only-two-pane-limit-no-duplicate-screen',
      'keyboard-pointer-route-only-to-target', 'focus-releases-previous-held-key-and-pointer',
      'single-peer-failure-isolated-explicit-reconnect-no-input-replay', 'remove-releases-and-closes-only-secondary',
      'runtime-change-and-session-revocation-close-both', '320px-and-desktop-visible-no-horizontal-overflow'],
    stats: await fixture.command('display-stats'), primary: first.stats(), secondary: second.stats(),
    execution: 'Two distinct local synthetic RFB peers over real HTTP/WS/TCP with Edge at 320 and 1440 CSS px. Real VM multimonitor support and physical phones require device validation.' }, null, 2))
  console.log(`PASS Multiscreen HTTP/WS/RFB and responsive UI: ${output}`)
} finally {
  await browser?.close()
  await fixture?.close()
  await first?.close()
  await second?.close()
}
