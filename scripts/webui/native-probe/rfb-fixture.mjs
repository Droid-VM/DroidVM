// Local standard RFB peer for transport validation, not a virtual machine.
// It authenticates VNC clients, emits real raw framebuffer rectangles and
// records key/pointer messages. Optional clipboard exchanges use actual RFB
// messages; their contents are test data, not an emulated application desktop.
import assert from 'node:assert/strict'
import { createCipheriv, randomBytes, timingSafeEqual } from 'node:crypto'
import { once } from 'node:events'
import net from 'node:net'
import { constants as zlibConstants, deflateSync, inflateSync } from 'node:zlib'

export async function startRfbFixture({ clipboard = 'legacy', foreground = [230, 80, 40], background = [40, 120, 210] } = {}) {
  assert(['legacy', 'extended'].includes(clipboard))
  for (const color of [foreground, background]) assert(Array.isArray(color) && color.length === 3 && color.every(value => Number.isInteger(value) && value >= 0 && value <= 255))
  const password = 'web-test'
  const width = 640, height = 360
  const clients = new Set(), faults = [], keys = [], pointers = []
  const publishers = new Map(), clipboardReceived = [], clipboardSent = [], clipboardEvents = []
  const textFormat = 1, caps = 1 << 24, request = 1 << 25, peek = 1 << 26, notify = 1 << 27, provide = 1 << 28
  const maxClipboardWire = 256 * 1024
  const record = (list, value) => { list.push(value); if (list.length > 256) list.shift() }
  let accepted = 0, authenticated = 0, rectangles = 0, disconnected = 0
  function response(challenge) {
    const key = Buffer.alloc(8)
    for (let i = 0; i < 8; i++) {
      let value = password.charCodeAt(i) || 0, reversed = 0
      for (let bit = 0; bit < 8; bit++) { reversed = (reversed << 1) | (value & 1); value >>= 1 }
      key[i] = reversed
    }
    const cipher = createCipheriv('des-ede3', Buffer.concat([key, key, key]), null)
    cipher.setAutoPadding(false)
    return Buffer.concat([cipher.update(challenge), cipher.final()])
  }
  const server = net.createServer(socket => {
    ++accepted
    const connection = accepted
    clients.add(socket)
    socket.setNoDelay(true)
    let pending = Buffer.alloc(0), phase = 'version', format = { big: false, red: 0, green: 8, blue: 16 }
    let advertisedClipboard = false, clientClipboard = false, offeredText = null, requestedClipboard = false
    const challenge = randomBytes(16)
    socket.write('RFB 003.008\n')
    function serverClipboard(data, extended = false) {
      assert(data.length <= maxClipboardWire)
      const header = Buffer.alloc(8)
      header[0] = 3
      header.writeInt32BE(extended ? -data.length : data.length, 4)
      socket.write(Buffer.concat([header, data]))
    }
    function extendedClipboard(action, body = Buffer.alloc(0), formats = textFormat) {
      const flags = Buffer.alloc(4)
      flags.writeUInt32BE((action | formats) >>> 0)
      record(clipboardEvents, { connection, direction: 'server', action })
      serverClipboard(Buffer.concat([flags, body]), true)
    }
    function sendProvidedText(text) {
      const wireText = text.replace(/\r\n|\r|\n/g, '\r\n') + '\0'
      const bytes = Buffer.from(wireText, 'utf8'), size = Buffer.alloc(4)
      assert(bytes.length + 4 <= maxClipboardWire)
      size.writeUInt32BE(bytes.length)
      extendedClipboard(provide, deflateSync(Buffer.concat([size, bytes])))
      record(clipboardSent, { connection, format: 'utf8', text })
    }
    publishers.set(socket, text => {
      if (phase !== 'messages' || socket.destroyed) return false
      if (clipboard === 'extended') {
        assert(clientClipboard, 'Extended clipboard must be negotiated before publishing')
        offeredText = text
        extendedClipboard(notify)
      } else {
        assert([...text].every(character => character.codePointAt(0) <= 255), 'Legacy clipboard only carries Latin-1')
        serverClipboard(Buffer.from(text, 'latin1'))
        record(clipboardSent, { connection, format: 'latin1', text })
      }
      return true
    })
    function clientCutText(message) {
      const length = message.readInt32BE(4), data = message.subarray(8)
      if (length >= 0) {
        record(clipboardReceived, { connection, format: 'latin1', text: data.toString('latin1') })
        return
      }
      assert(clipboard === 'extended' && advertisedClipboard && data.length >= 4)
      const flags = data.readUInt32BE(0), action = flags & 0xff000000, formats = flags & 0xffff
      record(clipboardEvents, { connection, direction: 'client', action })
      if (action & caps) {
        assert(formats & textFormat)
        assert((action & (request | notify | provide)) === (request | notify | provide))
        assert(data.length >= 8)
        clientClipboard = true
      } else if (action === notify) {
        assert(clientClipboard && data.length === 4)
        if (formats & textFormat) { requestedClipboard = true; extendedClipboard(request) }
      } else if (action === request) {
        assert(clientClipboard && data.length === 4 && formats === textFormat)
        assert.notEqual(offeredText, null, 'Client may only request an offered clipboard')
        sendProvidedText(offeredText)
        offeredText = null
      } else if (action === provide) {
        assert(clientClipboard && requestedClipboard && formats === textFormat)
        // noVNC emits a complete Z_FULL_FLUSH block, without ending its zlib stream.
        const decoded = inflateSync(data.subarray(4), {
          finishFlush: zlibConstants.Z_SYNC_FLUSH, maxOutputLength: maxClipboardWire,
        })
        assert(decoded.length >= 5)
        const size = decoded.readUInt32BE(0)
        assert.equal(decoded.length, size + 4)
        assert.equal(decoded.at(-1), 0)
        const wireText = new TextDecoder('utf-8', { fatal: true }).decode(decoded.subarray(4, -1))
        assert(!wireText.includes('\0'))
        record(clipboardReceived, { connection, format: 'utf8', text: wireText.replaceAll('\r\n', '\n'), wireText })
        requestedClipboard = false
      } else if (action === peek) {
        assert(clientClipboard && data.length === 4)
        extendedClipboard(notify, Buffer.alloc(0), offeredText === null ? 0 : textFormat)
      } else throw new Error(`Unexpected extended clipboard action ${action}`)
    }
    function init() {
      const name = Buffer.from('DroidVM standard RFB transport fixture')
      const header = Buffer.alloc(24)
      header.writeUInt16BE(width, 0); header.writeUInt16BE(height, 2)
      header[4] = 32; header[5] = 24; header[7] = 1
      header.writeUInt16BE(255, 8); header.writeUInt16BE(255, 10); header.writeUInt16BE(255, 12)
      header[14] = 0; header[15] = 8; header[16] = 16
      header.writeUInt32BE(name.length, 20)
      socket.write(Buffer.concat([header, name]))
    }
    function frame(request) {
      const x = request.readUInt16BE(2), y = request.readUInt16BE(4)
      const w = Math.min(request.readUInt16BE(6), width - x), h = Math.min(request.readUInt16BE(8), height - y)
      assert(x < width && y < height && w > 0 && h > 0)
      const header = Buffer.alloc(16)
      header.writeUInt16BE(1, 2)
      header.writeUInt16BE(x, 4); header.writeUInt16BE(y, 6)
      header.writeUInt16BE(w, 8); header.writeUInt16BE(h, 10)
      const pixels = Buffer.alloc(w * h * 4)
      for (let row = 0; row < h; row++) for (let col = 0; col < w; col++) {
        const [r, g, b] = ((x + col) < 80 && (y + row) < 80) ? foreground : background
        const value = ((r << format.red) | (g << format.green) | (b << format.blue)) >>> 0
        const offset = (row * w + col) * 4
        if (format.big) pixels.writeUInt32BE(value, offset)
        else pixels.writeUInt32LE(value, offset)
      }
      // Large enough to require many bounded upstream/WS reads.
      ++rectangles
      socket.write(Buffer.concat([header, pixels]))
    }
    function consume() {
      for (;;) {
        if (phase === 'version') {
          if (pending.length < 12) return
          assert.equal(pending.subarray(0, 12).toString(), 'RFB 003.008\n')
          pending = pending.subarray(12); phase = 'security'; socket.write(Buffer.from([1, 2]))
        } else if (phase === 'security') {
          if (pending.length < 1) return
          assert.equal(pending[0], 2)
          pending = pending.subarray(1); phase = 'auth'; socket.write(challenge)
        } else if (phase === 'auth') {
          if (pending.length < 16) return
          assert(timingSafeEqual(pending.subarray(0, 16), response(challenge)), 'VNC authentication response')
          pending = pending.subarray(16); phase = 'init'; ++authenticated; socket.write(Buffer.alloc(4))
        } else if (phase === 'init') {
          if (pending.length < 1) return
          pending = pending.subarray(1); phase = 'messages'; init()
        } else {
          if (!pending.length) return
          const type = pending[0]
          let length
          if (type === 0) length = 20
          else if (type === 2) {
            if (pending.length < 4) return
            const count = pending.readUInt16BE(2); assert(count <= 256); length = 4 + count * 4
          } else if (type === 3) length = 10
          else if (type === 4) length = 8
          else if (type === 5) length = 6
          else if (type === 6) {
            if (pending.length < 8) return
            length = 8 + Math.abs(pending.readInt32BE(4)); assert(length <= maxClipboardWire + 8)
          } else throw new Error(`Unexpected RFB client message ${type}`)
          if (pending.length < length) return
          const message = pending.subarray(0, length); pending = pending.subarray(length)
          if (type === 0) {
            assert.equal(message[4], 32); assert.equal(message[7], 1)
            format = { big: !!message[6], red: message[14], green: message[15], blue: message[16] }
          } else if (type === 2 && clipboard === 'extended' && !advertisedClipboard) {
            const encodings = []
            for (let offset = 4; offset < message.length; offset += 4) encodings.push(message.readUInt32BE(offset))
            assert(encodings.includes(0xc0a1e5ce), 'noVNC must advertise extended clipboard encoding')
            advertisedClipboard = true
            const maximum = Buffer.alloc(4)
            maximum.writeUInt32BE(0) // No unsolicited Provide; Notify/Request handshake is required.
            extendedClipboard(caps | request | peek | notify | provide, maximum)
          } else if (type === 3) {
            // One initial full frame and explicit nonincremental refreshes suffice;
            // no timer continuously queues identical pictures behind a slow client.
            if (!message[1] || !rectangles) frame(message)
          } else if (type === 4) {
            keys.push({ down: !!message[1], key: message.readUInt32BE(4) }); if (keys.length > 256) keys.shift()
          } else if (type === 5) {
            pointers.push({ buttons: message[1], x: message.readUInt16BE(2), y: message.readUInt16BE(4) })
            if (pointers.length > 256) pointers.shift()
          } else if (type === 6) {
            clientCutText(message)
          }
        }
      }
    }
    socket.on('data', chunk => {
      try {
        assert(pending.length + chunk.length <= maxClipboardWire + 65536)
        pending = Buffer.concat([pending, chunk]); consume()
      } catch (error) { faults.push(error.message); socket.destroy() }
    })
    socket.on('error', () => {})
    socket.on('close', () => { clients.delete(socket); publishers.delete(socket); ++disconnected })
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  return {
    port: server.address().port, password, width, height,
    dropClients() { for (const socket of clients) socket.destroy() },
    sendClipboard(text) {
      assert.equal(typeof text, 'string')
      assert(!text.includes('\0') && Buffer.byteLength(text, 'utf8') <= 65536)
      let published = 0
      for (const publish of publishers.values()) if (publish(text)) ++published
      return published
    },
    stats: () => ({ accepted, authenticated, rectangles, disconnected, connected: clients.size, keys: [...keys], pointers: [...pointers],
      clipboardReceived: [...clipboardReceived], clipboardSent: [...clipboardSent], clipboardEvents: [...clipboardEvents], faults: [...faults] }),
    async close() { for (const socket of clients) socket.destroy(); await new Promise(resolve => server.close(resolve)) },
  }
}
