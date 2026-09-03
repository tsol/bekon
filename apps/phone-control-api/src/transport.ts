import type { WlyaTunnelClient } from './tunnel-client.js'
import type { TunnelMessageLite } from './types.js'

export interface TunnelTransport {
  sendPlaintext(text: string): Promise<void>
  waitForMessage(
    test: (msg: TunnelMessageLite) => boolean,
    timeoutMs: number,
    pollMs?: number,
    signal?: AbortSignal,
  ): Promise<TunnelMessageLite | null>
}

const MIN_FETCH_GAP_MS = 250

export class WlyaTunnelTransport implements TunnelTransport {
  private seen = new Set<string>()
  private lastFetchAt = 0
  private inFlightFetch: Promise<void> | null = null
  private buffer: TunnelMessageLite[] = []

  constructor(
    private readonly client: WlyaTunnelClient,
    private readonly tunnelId: string,
  ) {}

  async sendPlaintext(text: string): Promise<void> {
    await this.client.ensureRunning(this.tunnelId)
    await this.client.send(this.tunnelId, text)
  }

  async waitForMessage(
    test: (msg: TunnelMessageLite) => boolean,
    timeoutMs: number,
    pollMs = MIN_FETCH_GAP_MS,
    signal?: AbortSignal,
  ): Promise<TunnelMessageLite | null> {
    const deadline = Date.now() + timeoutMs
    while (Date.now() < deadline) {
      if (signal?.aborted) throw abortError()
      await this.refillBuffer(signal)
      const idx = this.buffer.findIndex(test)
      if (idx >= 0) {
        return this.buffer.splice(idx, 1)[0]
      }
      const remaining = deadline - Date.now()
      if (remaining <= 0) break
      await sleep(Math.min(pollMs, remaining), signal)
    }
    if (signal?.aborted) throw abortError()
    return null
  }

  private async refillBuffer(signal?: AbortSignal): Promise<void> {
    const now = Date.now()
    const wait = Math.max(0, MIN_FETCH_GAP_MS - (now - this.lastFetchAt))
    if (wait > 0) await sleep(wait, signal)

    if (this.inFlightFetch) {
      await this.inFlightFetch
      return
    }

    this.inFlightFetch = this.doFetch().finally(() => {
      this.inFlightFetch = null
    })
    await this.inFlightFetch
  }

  private async doFetch(): Promise<void> {
    this.lastFetchAt = Date.now()
    const all = await this.client.getMessages(this.tunnelId)
    const fresh = all.filter(m => {
      const key = `${m.direction}:${m.seq}:${m.timestamp}:${m.plaintext}`
      if (this.seen.has(key)) return false
      this.seen.add(key)
      return true
    })
    if (this.seen.size > 2000) {
      const extra = this.seen.size - 1500
      let i = 0
      for (const k of this.seen) {
        if (i++ >= extra) break
        this.seen.delete(k)
      }
    }
    if (fresh.length > 0) {
      this.buffer.push(
        ...fresh.map(m => ({
          direction: m.direction,
          plaintext: m.plaintext,
          seq: m.seq,
          timestamp: m.timestamp,
        })),
      )
    }
  }
}

function abortError(): Error {
  const err = new Error('aborted')
  err.name = 'AbortError'
  return err
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError())
      return
    }
    const t = setTimeout(resolve, ms)
    const onAbort = () => {
      clearTimeout(t)
      reject(abortError())
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}
