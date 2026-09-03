import type { ScreenSnapshot } from './snapshot.js'

export type GestureKind = 'tap' | 'swipe' | 'longPress' | 'drag' | 'release' | 'nav' | 'input' | 'key' | 'clipboard' | 'sleep' | 'screenshot' | 'snapshot' | 'file' | 'share' | 'ping' | 'logs'

export type InputMode = 'text' | 'keys'

export type NavCmd = 'home' | 'back' | 'recentApps'

export type KeyCmd = 'backspace' | 'enter' | 'selectAll' | 'clear' | 'copy' | 'cut' | 'paste'

export type GestureStatus = 'pending' | 'sending' | 'ok' | 'error' | 'timeout' | 'aborted'

export type WaitingFor = null | 'pong' | 'ack' | 'screenshot'

export interface Gesture {
  id: string
  kind: GestureKind
  status?: GestureStatus
  error?: string
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
  latencyMs?: number
  hiRes?: boolean
  scale?: number
  quality?: number
  name?: string
  mime?: string
  size?: number
  path?: string
  uri?: string
  pkg?: string
}

export interface PhoneResponse {
  id?: string
  type?: string
  data?: string
  size?: number
  error?: string
  ok?: boolean
}

export interface PingResult {
  ok: boolean
  latencyMs?: number
  error?: string
}

export interface PutFileResult {
  ok: boolean
  path?: string
  uri?: string
  name?: string
  size?: number
  mime?: string
  error?: string
}

export interface LogsDump {
  adapter: string[]
  messages: string[]
  core: string[]
  apkUpdate: string[]
}

export interface ScreenshotResult {
  ok: boolean
  jpeg?: Buffer
  mime?: string
  error?: string
  a11yJson?: string
  captureW?: number
  captureH?: number
  screenW?: number
  screenH?: number
  snapshot?: ScreenSnapshot
}

export interface TunnelMessageLite {
  direction: string
  plaintext: string
  seq: number
  timestamp: number
}

export interface TunnelListItem {
  id: string
  label: string
  channel: string
  running: boolean
}

export interface PhoneState {
  tunnelId: string
  queue: Gesture[]
  busy: boolean
  executing: boolean
  waitingFor: WaitingFor
  lastPing: { ok: boolean; latencyMs?: number; error?: string; at: number } | null
  lastError: string
  lastClip?: string
  screenshotAt: number | null
  a11yJson: string
  screenW: number | null
  screenH: number | null
  captureW: number | null
  captureH: number | null
  screenshotMime: string | null
  lastSnapshot: ScreenSnapshot | null
  lastPutFile: PutFileResult | null
  lastLogs: LogsDump | null
}

/** Body of `POST /queue/execute`. No `lastSnapshot` — that is `/state` only. */
export type ExecuteResult = Omit<PhoneState, 'lastSnapshot'> & {
  snapshot?: ScreenSnapshot
}

export class BusyError extends Error {
  constructor(message = 'session busy') {
    super(message)
    this.name = 'BusyError'
  }
}

export class HttpError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'HttpError'
    this.status = status
  }
}
