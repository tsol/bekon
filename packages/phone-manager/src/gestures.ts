import {
  serializeBatch,
  screenshotPayload,
  sleepPayload,
  inputPayload,
  keyPayload,
  clipboardPayload,
  pingPayload,
  putFilePayload,
  logsPayload,
  dragPayload,
  releasePayload,
  sharePayload,
  clipTextFromAck,
  gesturePayload,
  navPayload,
  findPhoneItem,
  isCommandAck,
  decodeA11yField,
  jpegFromShotData,
  shotMime,
} from './protocol.js'
import { newRequestId } from './requestId.js'
import type { PhonePayload } from './protocol.js'
import type { TunnelTransport } from './transport.js'
import { ocrImage } from './ocr.js'
import { buildScreenSnapshot } from './snapshot.js'
import type { Gesture, GestureStatus, LogsDump, PingResult, PutFileResult, ScreenshotResult } from './types.js'
import { HttpError } from './types.js'

const DEFAULT_ACK_TIMEOUT_MS = 90_000
const FILE_ACK_TIMEOUT_MS = 600_000
const MAX_FILE_BYTES = 25 * 1024 * 1024

function optDim(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v) && v > 0) return Math.round(v)
  return undefined
}

export class GestureService {
  queue: Gesture[] = []
  private onChange: (() => void) | null = null
  private fileBodies = new Map<string, { name: string; mime?: string; data: string }>()
  private abortCtl: AbortController | null = null

  constructor(private readonly transport: TunnelTransport) {}

  setOnChange(fn: (() => void) | null): void {
    this.onChange = fn
  }

  private notify() {
    this.onChange?.()
  }

  private setStatus(g: Gesture, status: GestureStatus, error?: string) {
    g.status = status
    if (error !== undefined) g.error = error
    else if (status !== 'error') delete g.error
    this.notify()
  }

  attachFile(id: string, name: string, data: string, mime?: string): number {
    const raw = data.includes(',') ? data.slice(data.indexOf(',') + 1) : data
    const buf = Buffer.from(raw, 'base64')
    if (!raw || buf.length === 0) throw new HttpError(400, 'file data required')
    if (buf.length > MAX_FILE_BYTES) throw new HttpError(400, `file too large (max ${MAX_FILE_BYTES} bytes)`)
    this.fileBodies.set(id, { name: name || 'file.bin', mime, data: raw })
    return buf.length
  }

  enqueue(g: Gesture): Gesture {
    if (!g.id) g.id = newRequestId()
    if (!g.status) g.status = 'pending'
    this.queue.push(g)
    this.notify()
    return g
  }

  remove(id: string): void {
    this.fileBodies.delete(id)
    this.queue = this.queue.filter(g => g.id !== id)
    this.notify()
  }

  clear(): void {
    this.fileBodies.clear()
    this.queue = []
    this.notify()
  }

  abort(): boolean {
    if (!this.abortCtl) return false
    this.abortCtl.abort()
    return true
  }

  async execute(timeoutMs?: number): Promise<{
    ok: boolean
    errors: string[]
    clip?: string
    screenshot?: ScreenshotResult
    lastPing?: PingResult
    lastPutFile?: PutFileResult
    lastLogs?: LogsDump
  }> {
    const pending = this.queue.filter(g => g.status === 'pending' || !g.status)
    if (pending.length === 0) return { ok: true, errors: [] }
    const wait = timeoutMs
      ?? (pending.some(g => g.kind === 'file') ? FILE_ACK_TIMEOUT_MS : DEFAULT_ACK_TIMEOUT_MS)

    const res = await this.executeJson(pending, wait)
    return {
      ok: res.errors.length === 0,
      errors: res.errors,
      clip: res.clip,
      screenshot: res.screenshot,
      lastPing: res.lastPing,
      lastPutFile: res.lastPutFile,
      lastLogs: res.lastLogs,
    }
  }

  private async executeJson(batch: Gesture[], timeoutMs: number): Promise<{
    errors: string[]
    clip?: string
    screenshot?: ScreenshotResult
    lastPing?: PingResult
    lastPutFile?: PutFileResult
    lastLogs?: LogsDump
  }> {
    const errors: string[] = []
    for (const g of batch) this.setStatus(g, 'sending')
    const start = Date.now()
    const abortCtl = new AbortController()
    this.abortCtl = abortCtl
    try {
      await this.transport.sendPlaintext(serializeBatch(batch.map(g => this.payload(g))))
      if (abortCtl.signal.aborted) throw abortErr()
      const msg = await this.transport.waitForMessage((m) => {
        if (m.direction !== 'in') return false
        return batch.every(g => !!findPhoneItem(m.plaintext, g.id))
      }, timeoutMs, undefined, abortCtl.signal)

      if (!msg) {
        for (const g of batch) this.setStatus(g, 'timeout', 'timeout waiting for phone reply')
        return { errors: batch.map(g => `${g.kind}: timeout`) }
      }

      let clip: string | undefined
      let screenshot: ScreenshotResult | undefined
      let lastPing: PingResult | undefined
      let lastPutFile: PutFileResult | undefined
      let lastLogs: LogsDump | undefined
      let lastOcr: ScreenshotResult['snapshot']
      const latencyMs = Date.now() - start

      for (const g of batch) {
        const payload = findPhoneItem(msg.plaintext, g.id)
        if (!payload) {
          this.setStatus(g, 'error', 'missing reply slot')
          errors.push(`${g.kind}: missing reply`)
          continue
        }
        const fromAck = clipTextFromAck(payload)
        if (fromAck !== undefined) clip = fromAck

        if (g.kind === 'screenshot' || g.kind === 'snapshot') {
          const shot = shotFromPhone(payload)
          if (!shot.ok) {
            this.setStatus(g, 'error', shot.error)
            errors.push(`${g.kind}: ${shot.error ?? 'failed'}`)
            screenshot = shot
            continue
          }
          if (g.kind === 'snapshot' && shot.jpeg) {
            let ocrError: string | undefined
            let ocr: { text: string; bbox: [number, number, number, number] }[] = []
            try {
              ocr = await ocrImage(shot.jpeg)
            } catch (e) {
              ocrError = e instanceof Error ? e.message : String(e)
            }
            shot.snapshot = buildScreenSnapshot({
              a11yJson: shot.a11yJson ?? '',
              ocr,
              captureW: shot.captureW ?? 0,
              captureH: shot.captureH ?? 0,
              screenW: shot.screenW ?? 0,
              screenH: shot.screenH ?? 0,
              ocrError,
            })
          }
          this.setStatus(g, 'ok')
          if (shot.snapshot) lastOcr = shot.snapshot
          screenshot = shot
          continue
        }

        if (g.kind === 'logs') {
          if (typeof payload.error === 'string') {
            this.setStatus(g, 'error', payload.error)
            errors.push(`logs: ${payload.error}`)
          } else if (payload.ok === true) {
            this.setStatus(g, 'ok')
            lastLogs = {
              adapter: strList(payload.adapter),
              messages: strList(payload.messages),
              core: strList(payload.core),
              apkUpdate: strList(payload.apkUpdate),
            }
          } else {
            this.setStatus(g, 'error', 'missing logs reply')
            errors.push('logs: missing reply')
          }
          continue
        }

        if (g.kind === 'file') {
          this.fileBodies.delete(g.id)
          if (typeof payload.error === 'string') {
            this.setStatus(g, 'error', payload.error)
            errors.push(`file: ${payload.error}`)
            lastPutFile = { ok: false, error: payload.error }
          } else if (payload.ok === true && typeof payload.path === 'string') {
            this.setStatus(g, 'ok')
            const size = optDim(payload.size)
            lastPutFile = {
              ok: true,
              path: payload.path,
              uri: typeof payload.uri === 'string' ? payload.uri : undefined,
              name: typeof payload.name === 'string' ? payload.name : g.name,
              size,
              mime: typeof payload.mime === 'string' ? payload.mime : g.mime,
            }
            if (size) g.size = size
          } else {
            this.setStatus(g, 'error', 'missing path in putFile reply')
            errors.push('file: missing path')
            lastPutFile = { ok: false, error: 'missing path' }
          }
          continue
        }

        if (typeof payload.error === 'string') {
          this.setStatus(g, 'error', payload.error)
          errors.push(`${g.kind}: ${payload.error}`)
          if (g.kind === 'ping') lastPing = { ok: false, error: payload.error }
        } else if (g.kind === 'sleep' || isCommandAck(payload, g.id)) {
          this.setStatus(g, 'ok')
          if (g.kind === 'ping') {
            g.latencyMs = latencyMs
            lastPing = { ok: true, latencyMs }
          }
        } else {
          this.setStatus(g, 'error', 'missing ACK')
          errors.push(`${g.kind}: missing ACK`)
          if (g.kind === 'ping') lastPing = { ok: false, error: 'missing ACK' }
        }
      }

      if (screenshot?.ok && lastOcr) screenshot.snapshot = lastOcr
      return { errors, clip, screenshot, lastPing, lastPutFile, lastLogs }
    } catch (e) {
      if (isAbortErr(e)) {
        for (const g of batch) {
          if (g.status === 'sending') this.setStatus(g, 'aborted', 'aborted waiting for phone reply')
        }
        return { errors: batch.map(g => `${g.kind}: aborted`) }
      }
      const err = e instanceof Error ? e.message : String(e)
      for (const g of batch) {
        if (g.status === 'sending') this.setStatus(g, 'error', err)
      }
      return { errors: [err] }
    } finally {
      if (this.abortCtl === abortCtl) this.abortCtl = null
    }
  }

  private payload(gesture: Gesture) {
    if (gesture.kind === 'ping') return pingPayload(gesture.id)
    if (gesture.kind === 'screenshot') {
      return screenshotPayload(gesture.id, {
        hiRes: gesture.hiRes === true,
        scale: gesture.scale,
        quality: gesture.quality,
      })
    }
    if (gesture.kind === 'snapshot') {
      return screenshotPayload(gesture.id, {
        hiRes: gesture.hiRes !== false,
        scale: gesture.scale,
        quality: gesture.quality,
      })
    }
    if (gesture.kind === 'sleep') return sleepPayload(gesture.ms ?? 0, gesture.id)
    if (gesture.kind === 'nav') return navPayload(gesture.nav ?? 'home', gesture.id)
    if (gesture.kind === 'input') {
      return inputPayload(gesture.text ?? '', gesture.id, gesture.inputMode ?? 'text')
    }
    if (gesture.kind === 'key') return keyPayload(gesture.key ?? 'enter', gesture.n ?? 1, gesture.id)
    if (gesture.kind === 'clipboard') return clipboardPayload(gesture.id)
    if (gesture.kind === 'logs') return logsPayload(gesture.id, gesture.n)
    if (gesture.kind === 'file') {
      const f = this.fileBodies.get(gesture.id)
      if (!f) throw new Error('file data missing — re-enqueue the file')
      return putFilePayload(f.name, f.data, f.mime, gesture.id)
    }
    if (gesture.kind === 'share') {
      return sharePayload(gesture.path, gesture.mime, gesture.pkg, gesture.uri, gesture.id)
    }
    if (gesture.kind === 'drag') {
      return dragPayload(gesture.x ?? 0, gesture.y ?? 0, gesture.id)
    }
    if (gesture.kind === 'release') {
      return releasePayload(gesture.id)
    }
    const params: Record<string, number> = {}
    if (gesture.kind === 'tap' || gesture.kind === 'longPress') {
      params.x = gesture.x ?? 0
      params.y = gesture.y ?? 0
    } else if (gesture.kind === 'swipe') {
      params.x1 = gesture.x1 ?? 0
      params.y1 = gesture.y1 ?? 0
      params.x2 = gesture.x2 ?? 0
      params.y2 = gesture.y2 ?? 0
    }
    return gesturePayload(gesture.kind, params, gesture.id)
  }
}

function abortErr(): Error {
  const err = new Error('aborted')
  err.name = 'AbortError'
  return err
}

function isAbortErr(e: unknown): boolean {
  return e instanceof Error && e.name === 'AbortError'
}

function strList(v: unknown): string[] {
  if (!Array.isArray(v)) return []
  return v.filter((x): x is string => typeof x === 'string')
}

function shotFromPhone(payload: PhonePayload): ScreenshotResult {
  if (typeof payload.error === 'string') {
    return { ok: false, error: payload.error }
  }
  if (typeof payload.data !== 'string') {
    return { ok: false, error: 'unexpected screenshot reply' }
  }
  return {
    ok: true,
    jpeg: jpegFromShotData(payload.data),
    mime: shotMime(payload),
    a11yJson: decodeA11yField(payload.a11y),
    captureW: optDim(payload.captureW),
    captureH: optDim(payload.captureH),
    screenW: optDim(payload.screenW),
    screenH: optDim(payload.screenH),
  }
}
