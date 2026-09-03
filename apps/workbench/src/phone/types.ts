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
  lastPutFile: { ok: boolean; path?: string; uri?: string; name?: string; size?: number; mime?: string; error?: string } | null
  lastLogs: { adapter: string[]; messages: string[]; core: string[]; apkUpdate: string[] } | null
}

export interface TunnelListItem {
  id: string
  label: string
  running: boolean
}

export interface GestureInput {
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

export type SnapshotItem = {
  ref: string
  source: 'a11y' | 'ocr'
  name: string
  x: number
  y: number
  /** Screen pixels `[left, top, right, bottom]`. */
  bounds?: [number, number, number, number]
}

export type ScreenSnapshot = {
  source: Array<'a11y' | 'ocr'>
  captureW: number
  captureH: number
  screenW: number
  screenH: number
  items: SnapshotItem[]
  ocrCount?: number
  ocrError?: string
}

export function emptyPhoneState(tunnelId = ''): PhoneState {
  return {
    tunnelId,
    queue: [],
    busy: false,
    executing: false,
    waitingFor: null,
    lastPing: null,
    lastError: '',
    screenshotAt: null,
    a11yJson: '',
    screenW: null,
    screenH: null,
    captureW: null,
    captureH: null,
    screenshotMime: null,
    lastSnapshot: null,
    lastPutFile: null,
    lastLogs: null,
  }
}
