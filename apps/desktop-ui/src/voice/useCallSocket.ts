import { onUnmounted, ref } from 'vue'
import { signedCallUrl } from './hmac.ts'
import { decodeFrame, encodeFrame } from './pcm.ts'

export type CallStatus = 'idle' | 'connecting' | 'joined' | 'error'

export type CallJson = {
  type?: string
  id?: string
  ok?: boolean
  error?: string
  capture?: boolean
  playback?: boolean
  source?: string
  speaker?: boolean
  call?: string
  root?: boolean
  mode?: string
  active?: string
  backends?: string[]
  number?: string
  dialResult?: string
  action?: string
  bridge?: boolean
  meters?: Record<string, number | boolean>
  tapDiag?: string
  flow?: Record<string, string>
  localTxMute?: string
  localMuteResult?: string
  uplinkGainDb?: number
  uplinkTilt?: boolean
  frameMs?: number
  bufMult?: number
  injectMult?: number
  latencyPreset?: string
  wsRttMs?: number
  playUnderruns?: number
  t?: number
  tEcho?: number
  state?: PhoneVoiceState
}

export type PhoneVoiceState = {
  call?: string
  capture?: boolean
  playback?: boolean
  source?: string
  speaker?: boolean
  root?: boolean
  mode?: string
  active?: string
  backends?: string[]
  meters?: Record<string, number | boolean>
  number?: string
  dialResult?: string
  bridge?: boolean
  /** Why the phone's GSM tap is or is not carrying audio. */
  tapDiag?: string
  /** Per-leg `frames/liveFrames`; `n/0` means the leg ran but carried only zeros. */
  flow?: Record<string, string>
  localTxMute?: string
  localMuteResult?: string
  /** Extra dB applied to WS audio before it is injected into GSM uplink. */
  uplinkGainDb?: number
  uplinkTilt?: boolean
  frameMs?: number
  bufMult?: number
  injectMult?: number
  latencyPreset?: string
  wsRttMs?: number
  playUnderruns?: number
}

function newClientId(): string {
  const rand = crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `web-${rand}`
}

export function useCallSocket() {
  const status = ref<CallStatus>('idle')
  const error = ref('')
  const bytesIn = ref(0)
  const bytesOut = ref(0)
  let ws: WebSocket | null = null
  let onPcm: ((pcm: Int16Array) => void) | null = null
  let onJson: ((msg: CallJson) => void) | null = null

  function setHandler(fn: ((pcm: Int16Array) => void) | null) {
    onPcm = fn
  }

  function setJsonHandler(fn: ((msg: CallJson) => void) | null) {
    onJson = fn
  }

  async function connect(opts: { url: string; seed: string; room: string; client?: string }) {
    disconnect()
    error.value = ''
    bytesIn.value = 0
    bytesOut.value = 0
    status.value = 'connecting'
    const client = opts.client || loadClientId()
    try {
      const href = await signedCallUrl(opts.url, opts.seed, client)
      await new Promise<void>((resolve, reject) => {
        const socket = new WebSocket(href)
        ws = socket
        socket.binaryType = 'arraybuffer'
        socket.onopen = () => {
          const join = JSON.stringify({ type: 'join', room: opts.room })
          socket.send(join)
          bytesOut.value += new TextEncoder().encode(join).length
          status.value = 'joined'
          resolve()
        }
        socket.onmessage = (ev) => {
          if (typeof ev.data === 'string') {
            bytesIn.value += new TextEncoder().encode(ev.data).length
            try {
              const msg = JSON.parse(ev.data) as CallJson
              if (msg && typeof msg.type === 'string') onJson?.(msg)
            } catch { /* ignore */ }
            return
          }
          const buf = ev.data as ArrayBuffer
          bytesIn.value += buf.byteLength
          const pcm = decodeFrame(buf)
          if (pcm) onPcm?.(pcm)
        }
        socket.onerror = () => {
          error.value = 'socket error'
          status.value = 'error'
          reject(new Error('socket error'))
        }
        socket.onclose = () => {
          if (ws === socket) {
            ws = null
            if (status.value !== 'error') status.value = 'idle'
          }
        }
      })
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e)
      status.value = 'error'
      throw e
    }
  }

  function sendPcm(pcm: Int16Array) {
    if (!ws || ws.readyState !== WebSocket.OPEN || pcm.length === 0) return
    const frame = encodeFrame(pcm)
    ws.send(frame)
    bytesOut.value += frame.byteLength
  }

  function sendJson(msg: CallJson): boolean {
    if (!ws || ws.readyState !== WebSocket.OPEN) return false
    const text = JSON.stringify(msg)
    ws.send(text)
    bytesOut.value += new TextEncoder().encode(text).length
    return true
  }

  function disconnect() {
    const socket = ws
    ws = null
    if (socket) {
      socket.onopen = null
      socket.onmessage = null
      socket.onerror = null
      socket.onclose = null
      try { socket.close() } catch { /* ignore */ }
    }
    if (status.value !== 'error') status.value = 'idle'
  }

  onUnmounted(disconnect)

  return { status, error, bytesIn, bytesOut, connect, disconnect, sendPcm, sendJson, setHandler, setJsonHandler }
}

function loadClientId(): string {
  const key = 'wlya.voice.client'
  try {
    const existing = localStorage.getItem(key)
    if (existing) return existing
    const id = newClientId()
    localStorage.setItem(key, id)
    return id
  } catch {
    return newClientId()
  }
}
