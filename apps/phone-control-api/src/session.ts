import { GestureService } from './gestures.js'
import { newRequestId } from './requestId.js'
import { WlyaTunnelTransport } from './transport.js'
import type { WlyaTunnelClient } from './tunnel-client.js'
import {
  BusyError,
  type Gesture,
  type GestureKind,
  type InputMode,
  type KeyCmd,
  type NavCmd,
  HttpError,
  type ExecuteResult,
  type PhoneState,
  type WaitingFor,
} from './types.js'
import type { ScreenSnapshot } from './snapshot.js'

type Listener = (state: PhoneState) => void

export class PhoneSession {
  readonly tunnelId: string
  readonly gestures: GestureService

  private busy = false
  private executing = false
  private waitingFor: WaitingFor = null
  private lastPing: PhoneState['lastPing'] = null
  private lastError = ''
  private lastClip: string | undefined
  private screenshotAt: number | null = null
  private a11yJson = ''
  private screenW: number | null = null
  private screenH: number | null = null
  private captureW: number | null = null
  private captureH: number | null = null
  jpeg: Buffer | null = null
  screenshotMime: string | null = null
  lastSnapshot: ScreenSnapshot | null = null
  lastPutFile: PhoneState['lastPutFile'] = null
  lastLogs: PhoneState['lastLogs'] = null
  private readonly listeners = new Set<Listener>()

  constructor(client: WlyaTunnelClient, tunnelId: string) {
    this.tunnelId = tunnelId
    const transport = new WlyaTunnelTransport(client, tunnelId)
    this.gestures = new GestureService(transport)
    this.gestures.setOnChange(() => this.emit())
  }

  snapshot(): PhoneState {
    return {
      tunnelId: this.tunnelId,
      queue: this.gestures.queue.map(g => ({ ...g })),
      busy: this.busy,
      executing: this.executing,
      waitingFor: this.waitingFor,
      lastPing: this.lastPing,
      lastError: this.lastError,
      lastClip: this.lastClip,
      screenshotAt: this.screenshotAt,
      a11yJson: this.a11yJson,
      screenW: this.screenW,
      screenH: this.screenH,
      captureW: this.captureW,
      captureH: this.captureH,
      screenshotMime: this.screenshotMime,
      lastSnapshot: this.lastSnapshot,
      lastPutFile: this.lastPutFile,
      lastLogs: this.lastLogs,
    }
  }

  subscribe(fn: Listener): () => void {
    this.listeners.add(fn)
    fn(this.snapshot())
    return () => { this.listeners.delete(fn) }
  }

  private emit() {
    const snap = this.snapshot()
    for (const fn of this.listeners) fn(snap)
  }

  private setWait(w: WaitingFor) {
    this.waitingFor = w
    this.emit()
  }

  private async runExclusive<T>(fn: () => Promise<T>): Promise<T> {
    if (this.busy) throw new BusyError()
    this.busy = true
    this.emit()
    try {
      return await fn()
    } finally {
      this.busy = false
      this.executing = false
      this.waitingFor = null
      this.emit()
    }
  }

  applyScreenshot(shot: {
    ok: boolean
    jpeg?: Buffer
    mime?: string
    a11yJson?: string
    error?: string
    captureW?: number
    captureH?: number
    screenW?: number
    screenH?: number
    snapshot?: ScreenSnapshot
  }) {
    if (shot.ok && shot.jpeg) {
      this.jpeg = shot.jpeg
      this.screenshotMime = shot.mime ?? 'image/jpeg'
      this.screenshotAt = Date.now()
      this.a11yJson = shot.a11yJson ?? ''
      this.captureW = shot.captureW ?? this.captureW
      this.captureH = shot.captureH ?? this.captureH
      this.screenW = shot.screenW ?? this.screenW
      this.screenH = shot.screenH ?? this.screenH
      if (shot.snapshot) this.lastSnapshot = shot.snapshot
      this.emit()
    } else if (shot.error) {
      this.lastError = shot.error
    }
  }

  enqueue(body: GestureInput): Gesture {
    const g = gestureFromInput(body)
    if (body.kind === 'file') {
      g.size = this.gestures.attachFile(g.id, body.name ?? 'file.bin', body.data ?? '', body.mime)
      g.name = body.name ?? 'file.bin'
      g.mime = body.mime
    }
    if (body.kind === 'share') {
      g.pkg = body.pkg ?? body.package
      g.path = body.path
      g.uri = body.uri
      g.mime = body.mime
      if (!g.path && !g.uri && this.lastPutFile?.ok) {
        g.path = this.lastPutFile.path
        g.uri = this.lastPutFile.uri
        g.mime = body.mime ?? this.lastPutFile.mime
      }
    }
    return this.gestures.enqueue(g)
  }

  enqueueMany(items: GestureInput[]): Gesture[] {
    return items.map(item => this.enqueue(item))
  }

  remove(id: string): void {
    this.gestures.remove(id)
  }

  clear(): void {
    this.gestures.clear()
  }

  async doExecute(): Promise<ExecuteResult> {
    let ran: Gesture[] = []
    await this.runExclusive(async () => {
      this.lastError = ''
      this.executing = true
      const pending = this.gestures.queue.filter(g => g.status === 'pending' || !g.status)
      ran = pending
      this.setWait(
        pending.some(g => g.kind === 'screenshot' || g.kind === 'snapshot')
          ? 'screenshot'
          : pending.some(g => g.kind === 'ping')
            ? 'pong'
            : 'ack',
      )
      const res = await this.gestures.execute()
      if (res.clip !== undefined) this.lastClip = res.clip
      if (res.screenshot) this.applyScreenshot(res.screenshot)
      if (res.lastPing) this.lastPing = { ...res.lastPing, at: Date.now() }
      if (res.lastPutFile) this.lastPutFile = res.lastPutFile
      if (res.lastLogs) this.lastLogs = res.lastLogs
      if (!res.ok) this.lastError = res.errors.join('; ')
    })
    return this.executeResult(ran)
  }

  abort(): { ok: true; aborted: boolean } {
    if (!this.busy) return { ok: true, aborted: false }
    const aborted = this.gestures.abort()
    return { ok: true, aborted }
  }

  /**
   * Agent-facing execute body: omit cached `lastSnapshot`. Include `snapshot`
   * only if this batch's last real command (ignoring sleep/ping) was a successful snapshot.
   */
  private executeResult(ran: Gesture[]): ExecuteResult {
    const { lastSnapshot: _cached, ...rest } = this.snapshot()
    const out: ExecuteResult = rest
    if (this.lastSnapshot && batchEndedWithOkSnapshot(ran)) {
      out.snapshot = this.lastSnapshot
    }
    return out
  }

  async captureAndSnapshot(opts?: { hiRes?: boolean; scale?: number; quality?: number }): Promise<{ snapshot: ScreenSnapshot }> {
    this.clear()
    this.enqueue({
      kind: 'snapshot',
      hiRes: opts?.hiRes,
      scale: opts?.scale,
      quality: opts?.quality,
    })
    await this.doExecute()
    if (this.lastError) throw new HttpError(502, this.lastError)
    if (!this.lastSnapshot) throw new HttpError(400, 'no snapshot')
    return { snapshot: this.lastSnapshot }
  }
}

function batchEndedWithOkSnapshot(ran: Gesture[]): boolean {
  let last: Gesture | undefined
  for (const g of ran) {
    if (g.kind === 'sleep' || g.kind === 'ping') continue
    last = g
  }
  return last?.kind === 'snapshot' && last.status === 'ok'
}

export type GestureInput = {
  kind: GestureKind
  x?: number
  y?: number
  x1?: number
  y1?: number
  x2?: number
  y2?: number
  nav?: NavCmd
  text?: string
  inputMode?: InputMode
  key?: KeyCmd
  n?: number
  ms?: number
  hiRes?: boolean
  scale?: number
  quality?: number
  name?: string
  mime?: string
  data?: string
  path?: string
  uri?: string
  pkg?: string
  package?: string
}

export function gestureFromInput(body: GestureInput): Gesture {
  return {
    id: newRequestId(),
    kind: body.kind,
    status: 'pending',
    x: body.x,
    y: body.y,
    x1: body.x1,
    y1: body.y1,
    x2: body.x2,
    y2: body.y2,
    nav: body.nav,
    text: body.text,
    inputMode: body.inputMode,
    key: body.key,
    n: body.n,
    ms: body.ms,
    hiRes: body.hiRes,
    scale: body.scale,
    quality: body.quality,
    name: body.name,
    mime: body.mime,
    path: body.path,
    uri: body.uri,
    pkg: body.pkg ?? body.package,
  }
}

export class SessionRegistry {
  private readonly sessions = new Map<string, PhoneSession>()

  constructor(private readonly client: WlyaTunnelClient) {}

  get(tunnelId: string): PhoneSession {
    let s = this.sessions.get(tunnelId)
    if (!s) {
      s = new PhoneSession(this.client, tunnelId)
      this.sessions.set(tunnelId, s)
    }
    return s
  }
}
