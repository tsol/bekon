import { gunzipSync } from 'node:zlib'
import { newRequestId } from './requestId.js'
import type { GestureKind, InputMode, KeyCmd, NavCmd } from './types.js'

export type PhonePayload = Record<string, unknown>

function asBatch(obj: PhonePayload): string {
  return JSON.stringify([obj])
}

export function screenshotPayload(
  id = newRequestId(),
  opts: { hiRes?: boolean; scale?: number; quality?: number } = {},
): PhonePayload {
  const p: PhonePayload = { cmd: 'screenshot', id }
  if (opts.hiRes) p.hiRes = true
  if (typeof opts.scale === 'number' && Number.isFinite(opts.scale)) {
    p.scale = Math.min(1, Math.max(0.1, opts.scale))
  }
  if (typeof opts.quality === 'number' && Number.isFinite(opts.quality)) {
    p.quality = Math.min(100, Math.max(1, Math.round(opts.quality)))
  }
  return p
}

export function tapPayload(x: number, y: number, id = newRequestId()): PhonePayload {
  return { cmd: 'tap', x, y, id }
}

export function swipePayload(
  x1: number, y1: number, x2: number, y2: number, id = newRequestId(),
): PhonePayload {
  return { cmd: 'swipe', x1, y1, x2, y2, id }
}

export function longPressPayload(x: number, y: number, id = newRequestId()): PhonePayload {
  return { cmd: 'longPress', x, y, id }
}

export function dragPayload(x: number, y: number, id = newRequestId()): PhonePayload {
  return { cmd: 'drag', x, y, id }
}

export function releasePayload(id = newRequestId()): PhonePayload {
  return { cmd: 'release', id }
}

export function navPayload(cmd: NavCmd, id = newRequestId()): PhonePayload {
  return { cmd, id }
}

export function sleepPayload(ms: number, id = newRequestId()): PhonePayload {
  return { cmd: 'sleep', ms: Math.max(0, Math.round(ms)), id }
}

export function inputPayload(text: string, id = newRequestId(), mode: InputMode = 'text'): PhonePayload {
  const p: PhonePayload = { cmd: 'input', text, id }
  if (mode === 'keys') p.mode = 'keys'
  return p
}

export function keyPayload(key: KeyCmd, n = 1, id = newRequestId()): PhonePayload {
  const p: PhonePayload = { cmd: 'key', key, id }
  if (key === 'backspace') p.n = Math.max(1, Math.round(n))
  return p
}

export function clipboardPayload(id = newRequestId()): PhonePayload {
  return { cmd: 'clipboard', id }
}

export function pingPayload(id = newRequestId()): PhonePayload {
  return { cmd: 'ping', id }
}

export function putFilePayload(
  name: string,
  data: string,
  mime: string | undefined,
  id = newRequestId(),
): PhonePayload {
  const p: PhonePayload = { cmd: 'putFile', id, name, data }
  if (mime) p.mime = mime
  return p
}

export function logsPayload(id = newRequestId(), n?: number): PhonePayload {
  const p: PhonePayload = { cmd: 'logs', id }
  if (typeof n === 'number' && Number.isFinite(n)) {
    p.n = Math.min(200, Math.max(10, Math.round(n)))
  }
  return p
}

export function sharePayload(
  path: string | undefined,
  mime: string | undefined,
  pkg: string | undefined,
  uri: string | undefined,
  id = newRequestId(),
): PhonePayload {
  const p: PhonePayload = { cmd: 'share', id }
  if (path) p.path = path
  if (mime) p.mime = mime
  if (pkg) p.package = pkg
  if (uri) p.uri = uri
  return p
}

export function clipTextFromAck(payload: PhonePayload | null): string | undefined {
  if (!payload || typeof payload.text !== 'string') return undefined
  if (payload.type === 'clipboard' || payload.cmd === 'clipboard' || payload.cmd === 'key') {
    return payload.text
  }
  return undefined
}

export function gesturePayload(
  kind: GestureKind,
  params: Record<string, number>,
  id = newRequestId(),
): PhonePayload {
  if (kind === 'tap') return tapPayload(params.x, params.y, id)
  if (kind === 'swipe') return swipePayload(params.x1, params.y1, params.x2, params.y2, id)
  if (kind === 'nav' || kind === 'input' || kind === 'key' || kind === 'clipboard' || kind === 'sleep' || kind === 'screenshot' || kind === 'snapshot' || kind === 'ping' || kind === 'file' || kind === 'share' || kind === 'drag' || kind === 'release' || kind === 'logs') {
    throw new Error(`use dedicated payload for ${kind}`)
  }
  return longPressPayload(params.x, params.y, id)
}

export function serializeScreenshot(id = newRequestId()): string {
  return asBatch(screenshotPayload(id))
}

export function serializeBatch(cmds: PhonePayload[]): string {
  return JSON.stringify(cmds)
}

export function isCommandAck(payload: PhonePayload, requestId: string): boolean {
  if (payload.id !== requestId) return false
  if (payload.type === 'screenshot') return false
  return payload.ok === true || typeof payload.error === 'string'
}

export function parsePhoneItems(plaintext: string): PhonePayload[] | null {
  try {
    const parsed: unknown = JSON.parse(plaintext)
    if (!Array.isArray(parsed)) return null
    const items: PhonePayload[] = []
    for (const el of parsed) {
      if (el && typeof el === 'object' && !Array.isArray(el)) {
        items.push(el as PhonePayload)
      }
    }
    return items
  } catch {
    return null
  }
}

export function findPhoneItem(plaintext: string, requestId: string): PhonePayload | null {
  const items = parsePhoneItems(plaintext)
  if (!items) return null
  return items.find(p => p.id === requestId) ?? null
}

export function decodeA11yField(b64: unknown): string | undefined {
  if (typeof b64 !== 'string' || !b64) return undefined
  try {
    const text = gunzipSync(Buffer.from(b64, 'base64')).toString('utf8')
    try {
      return JSON.stringify(JSON.parse(text), null, 2)
    } catch {
      return text
    }
  } catch {
    return undefined
  }
}

export function jpegFromShotData(data: string): Buffer {
  const raw = data.includes(',') ? data.slice(data.indexOf(',') + 1) : data
  return Buffer.from(raw, 'base64')
}

export function shotMime(payload: PhonePayload): string {
  return typeof payload.mime === 'string' && payload.mime.startsWith('image/')
    ? payload.mime
    : 'image/jpeg'
}
