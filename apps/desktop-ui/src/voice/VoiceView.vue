<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { FRAME_SAMPLES, SAMPLE_RATE, createResampler, floatToS16, resample, rms, s16ToFloat } from './pcm.ts'
import { useCallSocket, type CallJson, type PhoneVoiceState } from './useCallSocket.ts'
import {
  CAPTURE_PROCESSOR,
  PLAYBACK_PROCESSOR,
  captureWorkletUrl,
  playbackWorkletUrl,
} from './worklets.ts'

const URL_KEY = 'wlya.voice.url'
const ROOM_KEY = 'wlya.voice.room'
const SEED_KEY = 'wlya.voice.seed'
// Example relay (self-host wlya-server): wss://your-relay.example/v1/call
const DEFAULT_URL = 'wss://relay.example/v1/call'

const callUrl = ref(load(URL_KEY, DEFAULT_URL))
const room = ref(load(ROOM_KEY, 'voice'))
const seed = ref(load(SEED_KEY, ''))
const muteMic = ref(false)
const muteSpk = ref(false)
const micLevel = ref(0)
const spkLevel = ref(0)

const phoneCall = ref('idle')
const phoneMode = ref('phone')
const phoneNumber = ref('')
const phoneDialResult = ref('')
const phoneBridge = ref(false)
const phoneRoot = ref(false)
const phoneTapDiag = ref('')
const phoneFlow = ref<Record<string, string>>({})
const phoneLocalTx = ref('normal')
const phoneLocalMuteResult = ref('')
const phoneUplinkGain = ref(12)
const phoneUplinkTilt = ref(true)
const wsRttMs = ref(-1)
const phoneFrameMs = ref(10)
const phoneBufMult = ref(4)
const phoneInjectMult = ref(8)
const phoneLatencyPreset = ref('balanced')
const phonePlayUnderruns = ref(0)
const phoneError = ref('')
const lastAckId = ref('')
const dialNumber = ref('')
const pending = ref<Record<string, string>>({})
const CTRL_TIMEOUT_MS = 12_000
const pendingTimers = new Map<string, number>()
let applyingRemote = false

const { status, error, bytesIn, bytesOut, connect, disconnect, sendPcm, sendJson, setHandler, setJsonHandler } = useCallSocket()
const bytesInLabel = computed(() => formatBytes(bytesIn.value))
const bytesOutLabel = computed(() => formatBytes(bytesOut.value))
const connecting = computed(() => status.value === 'connecting')
const connected = computed(() => status.value === 'joined')
const ctrlBusy = computed(() => Object.keys(pending.value).length > 0)
const localMuteReady = computed(() =>
  connected.value && phoneMode.value === 'phone' && phoneCall.value === 'offhook' && phoneBridge.value,
)
function isBusy(key: string): boolean {
  return key in pending.value
}
const audioLive = computed(() => {
  if (phoneMode.value === 'walkie') return true
  const call = (phoneCall.value || '').toLowerCase()
  return call === 'offhook' || phoneBridge.value
})
const modeLabel = computed(() => {
  if (!connected.value) return 'OFF'
  return phoneMode.value === 'walkie' ? 'WALKIE TALKIE' : 'PHONE'
})
const callLabel = computed(() => {
  const c = (phoneCall.value || 'idle').toUpperCase()
  if (c === 'RINGING' && phoneNumber.value) return `${c}  ${phoneNumber.value}`
  return c
})

let audioCtx: AudioContext | null = null
let mediaStream: MediaStream | null = null
let source: MediaStreamAudioSourceNode | null = null
let captureNode: AudioWorkletNode | null = null
let playNode: AudioWorkletNode | null = null
let captureAcc = new Float32Array(0)
let playResample: ((chunk: Float32Array) => Float32Array) | null = null
const workletUrls: string[] = []

setHandler((pcm) => {
  if (!audioLive.value) return
  if (muteSpk.value || !playNode || !audioCtx) return
  spkLevel.value = Math.min(1, rms(pcm) / 8000)
  const f32 = s16ToFloat(pcm)
  const up = playResample ? playResample(f32) : resample(f32, SAMPLE_RATE, audioCtx.sampleRate)
  if (up.length === 0) return
  playNode.port.postMessage(up)
})

setJsonHandler((msg) => onPhoneJson(msg))

watch(muteSpk, (v) => {
  playNode?.port.postMessage({ type: 'mute', value: v })
})

watch(status, (value) => {
  if (value !== 'idle' && value !== 'error') return
  // The phone state belongs to the WebSocket session. Do not leave a stale
  // OFFHOOK/Hang up UI behind after an unexpected socket failure.
  phoneCall.value = 'idle'
  phoneBridge.value = false
  phoneNumber.value = ''
  phoneLocalTx.value = 'normal'
  clearPending()
})

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function load(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) || fallback
  } catch {
    return fallback
  }
}

function persist() {
  try {
    localStorage.setItem(URL_KEY, callUrl.value.trim())
    localStorage.setItem(ROOM_KEY, room.value.trim())
    localStorage.setItem(SEED_KEY, seed.value)
  } catch { /* ignore */ }
}

function applyState(state: PhoneVoiceState) {
  applyingRemote = true
  if (typeof state.call === 'string') phoneCall.value = state.call
  if (typeof state.mode === 'string' && state.mode) {
    phoneMode.value = state.mode === 'line' ? 'phone' : state.mode
  } else if (typeof state.active === 'string' && state.active) {
    phoneMode.value = state.active === 'line' ? 'phone' : state.active
  }
  if (typeof state.number === 'string') phoneNumber.value = state.number
  if (typeof state.dialResult === 'string') phoneDialResult.value = state.dialResult
  if (typeof state.bridge === 'boolean') phoneBridge.value = state.bridge
  if (typeof state.root === 'boolean') phoneRoot.value = state.root
  if (typeof state.tapDiag === 'string') phoneTapDiag.value = state.tapDiag
  if (state.flow && typeof state.flow === 'object') phoneFlow.value = state.flow
  if (typeof state.localTxMute === 'string') phoneLocalTx.value = state.localTxMute
  if (typeof state.localMuteResult === 'string') phoneLocalMuteResult.value = state.localMuteResult
  if (typeof state.uplinkGainDb === 'number') phoneUplinkGain.value = state.uplinkGainDb
  if (typeof state.uplinkTilt === 'boolean') phoneUplinkTilt.value = state.uplinkTilt
  if (typeof state.frameMs === 'number') phoneFrameMs.value = state.frameMs
  if (typeof state.bufMult === 'number') phoneBufMult.value = state.bufMult
  if (typeof state.injectMult === 'number') phoneInjectMult.value = state.injectMult
  if (typeof state.latencyPreset === 'string' && state.latencyPreset) phoneLatencyPreset.value = state.latencyPreset
  if (typeof state.wsRttMs === 'number' && state.wsRttMs >= 0) wsRttMs.value = state.wsRttMs
  if (typeof state.playUnderruns === 'number') phonePlayUnderruns.value = state.playUnderruns
  applyingRemote = false
}

function onPhoneJson(msg: CallJson) {
  if (msg.type === 'phone-state') {
    applyState(msg)
    return
  }
  if (msg.type === 'ack' || msg.type === 'ctrl-ack') {
    const id = msg.id || ''
    lastAckId.value = id
    if (typeof msg.t === 'number' && msg.t > 0) {
      wsRttMs.value = Math.max(0, Date.now() - msg.t)
    }
    settlePending(id)
    if (msg.ok === false) phoneError.value = msg.error || 'ctrl failed'
    else {
      phoneError.value = ''
      if (msg.state) applyState(msg.state)
    }
  }
}

function newCtrlId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function clearPending() {
  for (const timer of pendingTimers.values()) window.clearTimeout(timer)
  pendingTimers.clear()
  pending.value = {}
}

function settlePending(ackId: string) {
  if (!ackId) return
  const next = { ...pending.value }
  for (const [key, id] of Object.entries(next)) {
    if (id !== ackId) continue
    delete next[key]
    const timer = pendingTimers.get(key)
    if (timer != null) {
      window.clearTimeout(timer)
      pendingTimers.delete(key)
    }
  }
  pending.value = next
}

function beginWait(key: string): string | null {
  if (ctrlBusy.value) return null
  const id = newCtrlId()
  pending.value = { ...pending.value, [key]: id }
  const timer = window.setTimeout(() => {
    pendingTimers.delete(key)
    if (pending.value[key] !== id) return
    const next = { ...pending.value }
    delete next[key]
    pending.value = next
    phoneError.value = 'no ack'
  }, CTRL_TIMEOUT_MS)
  pendingTimers.set(key, timer)
  return id
}

function failWait(key: string, id: string, err: string) {
  const timer = pendingTimers.get(key)
  if (timer != null) {
    window.clearTimeout(timer)
    pendingTimers.delete(key)
  }
  if (pending.value[key] !== id) return
  const next = { ...pending.value }
  delete next[key]
  pending.value = next
  phoneError.value = err
}

function sendCtrl(key: string, extra: Record<string, unknown> = {}) {
  if (applyingRemote || !connected.value || ctrlBusy.value) return
  const id = beginWait(key)
  if (!id) return
  const sent = sendJson({
    type: 'ctrl',
    id,
    mode: phoneMode.value === 'walkie' ? 'walkie' : 'phone',
    ...extra,
  })
  if (!sent) failWait(key, id, 'not sent')
}

function sendPing() {
  if (status.value !== 'joined') return
  const id = newCtrlId()
  sendJson({ type: 'ping', id, t: Date.now() })
}

function setGatewayLatency(preset: string) {
  sendCtrl(`latency-${preset}`, { action: 'latency-preset', number: preset })
}

function setGatewayBufMult(mult: number) {
  sendCtrl(`buf-${mult}`, { action: 'buf-mult', number: String(mult) })
}

let pingTimer: number | null = null

function startPingLoop() {
  stopPingLoop()
  sendPing()
  pingTimer = window.setInterval(sendPing, 2000)
}

function stopPingLoop() {
  if (pingTimer != null) {
    window.clearInterval(pingTimer)
    pingTimer = null
  }
}

function setMode(mode: 'phone' | 'walkie') {
  sendCtrl(`mode-${mode}`, { mode })
}

function localMute(action: string) {
  sendCtrl(action, { action })
}

function setUplinkGain(db: number) {
  sendCtrl(`uplink-gain-${db}`, { action: 'uplink-gain', number: String(db) })
}

function setUplinkTilt(on: boolean) {
  sendCtrl('uplink-tilt', { action: 'uplink-tilt', number: on ? '1' : '0' })
}

async function ensureAudio(): Promise<AudioContext> {
  if (audioCtx && audioCtx.state !== 'closed') {
    if (audioCtx.state === 'suspended') await audioCtx.resume()
    return audioCtx
  }
  audioCtx = new AudioContext()
  return audioCtx
}

function stopAudio() {
  if (captureNode) {
    captureNode.port.onmessage = null
    captureNode.disconnect()
  }
  playNode?.disconnect()
  source?.disconnect()
  captureNode = null
  playNode = null
  source = null
  mediaStream?.getTracks().forEach(t => t.stop())
  mediaStream = null
  captureAcc = new Float32Array(0)
  playResample = null
  micLevel.value = 0
  spkLevel.value = 0
  while (workletUrls.length) URL.revokeObjectURL(workletUrls.pop() as string)
}

async function startCapture() {
  const ctx = await ensureAudio()
  const capUrl = captureWorkletUrl()
  const playUrl = playbackWorkletUrl()
  workletUrls.push(capUrl, playUrl)
  await ctx.audioWorklet.addModule(capUrl)
  await ctx.audioWorklet.addModule(playUrl)

  mediaStream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 },
    video: false,
  })
  source = ctx.createMediaStreamSource(mediaStream)
  captureNode = new AudioWorkletNode(ctx, CAPTURE_PROCESSOR)
  captureNode.port.onmessage = (ev) => {
    const input = ev.data as Float32Array
    const down = resample(input, ctx.sampleRate, SAMPLE_RATE)
    if (down.length === 0) return
    const merged = new Float32Array(captureAcc.length + down.length)
    merged.set(captureAcc)
    merged.set(down, captureAcc.length)
    let offset = 0
    while (merged.length - offset >= FRAME_SAMPLES) {
      const slice = merged.subarray(offset, offset + FRAME_SAMPLES)
      offset += FRAME_SAMPLES
      micLevel.value = Math.min(1, rms(slice))
      if (!muteMic.value && audioLive.value) sendPcm(floatToS16(slice))
    }
    captureAcc = merged.slice(offset)
  }
  source.connect(captureNode)
  const silent = ctx.createGain()
  silent.gain.value = 0
  captureNode.connect(silent)
  silent.connect(ctx.destination)

  playNode = new AudioWorkletNode(ctx, PLAYBACK_PROCESSOR)
  playNode.port.postMessage({ type: 'mute', value: muteSpk.value })
  playNode.connect(ctx.destination)
  playResample = createResampler(SAMPLE_RATE, ctx.sampleRate)
}

async function onConnect() {
  persist()
  if (!callUrl.value.trim() || !seed.value || !room.value.trim()) {
    error.value = 'url, secret and room required'
    return
  }
  phoneError.value = ''
  phoneMode.value = 'phone'
  try {
    await connect({
      url: callUrl.value.trim(),
      seed: seed.value,
      room: room.value.trim(),
    })
    startPingLoop()
    await startCapture()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
    disconnect()
    stopAudio()
  }
}

function onDisconnect() {
  stopPingLoop()
  disconnect()
  stopAudio()
}

function pickup() {
  sendCtrl('pickup', { action: 'pickup' })
}

function cancelCall() {
  sendCtrl('cancel', { action: 'cancel' })
}

function dial() {
  sendCtrl('dial', { action: 'dial', number: dialNumber.value })
}

onUnmounted(() => {
  stopPingLoop()
  clearPending()
  stopAudio()
  void audioCtx?.close()
  audioCtx = null
})
</script>

<template>
  <div class="voice">
    <h1>Voice</h1>
    <p class="hint">Desktop ↔ phone. Default <strong>PHONE</strong>: audio only while the GSM call is offhook. <strong>WALKIE TALKIE</strong> is speakerphone, always on.</p>

    <label>WebSocket URL</label>
    <input v-model="callUrl" type="text" :disabled="connected || connecting" spellcheck="false" />

    <label>Room</label>
    <input v-model="room" type="text" :disabled="connected || connecting" spellcheck="false" />

    <label>Secret</label>
    <input v-model="seed" :disabled="connected || connecting" spellcheck="false" />

    <div class="row">
      <button v-if="status !== 'joined'" class="go" :disabled="connecting" :class="{ busy: connecting }" @click="onConnect">{{ connecting ? 'Connecting…' : 'Connect' }}</button>
      <button v-else class="stop" @click="onDisconnect">Disconnect</button>
      <span class="status" :class="status">{{ status }}</span>
      <span class="xfer">in {{ bytesInLabel }} · out {{ bytesOutLabel }}</span>
      <span v-if="wsRttMs >= 0" class="xfer"> · rtt {{ wsRttMs }} ms</span>
    </div>
    <p v-if="error" class="err">{{ error }}</p>

    <p class="mode">{{ modeLabel }}</p>
    <p class="call">{{ callLabel }}<span v-if="phoneBridge"> · TAP</span></p>
    <p v-if="phoneRoot" class="hint tight">root</p>

    <div class="row">
      <button type="button" :disabled="!connected || ctrlBusy" :class="{ go: phoneMode === 'phone', busy: isBusy('mode-phone') }" @click="setMode('phone')">{{ isBusy('mode-phone') ? '…' : 'Phone' }}</button>
      <button type="button" :disabled="!connected || ctrlBusy" :class="{ go: phoneMode === 'walkie', busy: isBusy('mode-walkie') }" @click="setMode('walkie')">{{ isBusy('mode-walkie') ? '…' : 'Walkie talkie' }}</button>
    </div>

    <div v-if="phoneCall === 'ringing'" class="ring">
      <p>Incoming {{ phoneNumber || '(no number)' }}</p>
      <div class="row">
        <button class="go" :disabled="!connected || ctrlBusy" :class="{ busy: isBusy('pickup') }" @click="pickup">{{ isBusy('pickup') ? '…' : 'Pick up' }}</button>
        <button class="stop" :disabled="!connected || ctrlBusy" :class="{ busy: isBusy('cancel') }" @click="cancelCall">{{ isBusy('cancel') ? '…' : 'Cancel' }}</button>
      </div>
    </div>
    <div v-else-if="phoneCall === 'offhook'" class="row">
      <button class="stop" :disabled="!connected || ctrlBusy" :class="{ busy: isBusy('cancel') }" @click="cancelCall">{{ isBusy('cancel') ? '…' : 'Hang up' }}</button>
    </div>

    <label>Dial</label>
    <input v-model="dialNumber" type="tel" :disabled="!connected || ctrlBusy" placeholder="+7…" />
    <div class="row">
      <button type="button" :disabled="!connected || ctrlBusy || !dialNumber.trim()" :class="{ busy: isBusy('dial') }" @click="dial">{{ isBusy('dial') ? '…' : 'Dial' }}</button>
    </div>
    <p v-if="phoneDialResult" class="ack">{{ phoneDialResult }}</p>
    <p v-if="phoneError" class="err">{{ phoneError }}</p>
    <p v-else-if="lastAckId" class="ack">ack {{ lastAckId.slice(0, 8) }}</p>

    <details class="dbg">
      <summary>Debug</summary>
      <div class="meters">
        <div class="meter">
          <span>laptop mic</span>
          <div class="bar"><i :style="{ width: `${Math.round(micLevel * 100)}%` }" /></div>
          <label><input v-model="muteMic" type="checkbox" /> mute</label>
        </div>
        <div class="meter">
          <span>laptop spk</span>
          <div class="bar"><i :style="{ width: `${Math.round(spkLevel * 100)}%` }" /></div>
          <label><input v-model="muteSpk" type="checkbox" /> mute</label>
        </div>
      </div>
      <p class="diag">phone legs (frames/audio)</p>
      <ul class="diag legs">
        <li v-for="(v, leg) in phoneFlow" :key="leg">{{ leg }} {{ v }}</li>
        <li v-if="!Object.keys(phoneFlow).length">no state yet</li>
      </ul>
      <p v-if="phoneTapDiag" class="diag">tap: {{ phoneTapDiag }}</p>

      <p class="diag section-title">Gateway latency (remote)</p>
      <p class="diag">
        frame {{ phoneFrameMs }} ms · buf×{{ phoneBufMult }} · inject×{{ phoneInjectMult }}
        · preset {{ phoneLatencyPreset }}
        <span v-if="phonePlayUnderruns > 0"> · underruns {{ phonePlayUnderruns }}</span>
      </p>
      <div class="row compact">
        <button
          v-for="p in ['low', 'balanced', 'stable']"
          :key="p"
          :disabled="!connected || ctrlBusy"
          :class="{ go: phoneLatencyPreset === p, busy: isBusy(`latency-${p}`) }"
          @click="setGatewayLatency(p)"
        >{{ isBusy(`latency-${p}`) ? '…' : p }}</button>
      </div>
      <div class="row compact">
        <button
          v-for="m in [2, 4, 8]"
          :key="m"
          :disabled="!connected || ctrlBusy"
          :class="{ go: phoneBufMult === m, busy: isBusy(`buf-${m}`) }"
          @click="setGatewayBufMult(m)"
        >buf×{{ m }}</button>
      </div>

      <p class="diag section-title">GSM uplink gain (desktop → radio)</p>
      <div class="row compact">
        <button
          v-for="db in [0, 6, 12, 18, 24]"
          :key="db"
          :disabled="!connected || ctrlBusy"
          :class="{ go: phoneUplinkGain === db, busy: isBusy(`uplink-gain-${db}`) }"
          @click="setUplinkGain(db)"
        >{{ isBusy(`uplink-gain-${db}`) ? '…' : `+${db} dB` }}</button>
        <button
          :disabled="!connected || ctrlBusy"
          :class="{ go: phoneUplinkTilt, busy: isBusy('uplink-tilt') }"
          @click="setUplinkTilt(!phoneUplinkTilt)"
        >{{ isBusy('uplink-tilt') ? '…' : 'tilt' }}</button>
      </div>
      <p class="diag">GSM is narrowband (300–3400 Hz), so it can never match WALKIE. Gain and tilt only fight the quiet, muffled inject.</p>

      <p class="diag section-title">Motorola physical mic (PHONE default: MUX=ZERO)</p>
      <div class="row compact">
        <button
          :disabled="!localMuteReady || ctrlBusy"
          :class="{ go: phoneLocalTx === 'normal', busy: isBusy('local-tx-normal') }"
          @click="localMute('local-tx-normal')"
        >{{ isBusy('local-tx-normal') ? '…' : 'Normal' }}</button>
        <button
          :disabled="!localMuteReady || ctrlBusy"
          :class="{ go: phoneLocalTx === 'adc0', busy: isBusy('local-tx-adc0') }"
          @click="localMute('local-tx-adc0')"
        >{{ isBusy('local-tx-adc0') ? '…' : 'ADC=0' }}</button>
        <button
          :disabled="!localMuteReady || ctrlBusy"
          :class="{ go: phoneLocalTx === 'dec0', busy: isBusy('local-tx-dec0') }"
          @click="localMute('local-tx-dec0')"
        >{{ isBusy('local-tx-dec0') ? '…' : 'DEC=0' }}</button>
        <button
          :disabled="!localMuteReady || ctrlBusy"
          :class="{ go: phoneLocalTx === 'mux-zero', busy: isBusy('local-tx-mux-zero') }"
          @click="localMute('local-tx-mux-zero')"
        >{{ isBusy('local-tx-mux-zero') ? '…' : 'MUX=ZERO' }}</button>
      </div>

      <button
        class="restore"
        :disabled="!connected || ctrlBusy"
        :class="{ busy: isBusy('local-restore') }"
        @click="localMute('local-restore')"
      >{{ isBusy('local-restore') ? '…' : 'Restore physical mic' }}</button>
      <p v-if="phoneLocalMuteResult" class="diag">local: {{ phoneLocalMuteResult }}</p>
    </details>
  </div>
</template>

<style scoped>
.voice {
  padding: 20px 24px;
  max-width: 520px;
}
h1 { font-size: 20px; color: #eee; margin-bottom: 8px; }
.hint { font-size: 13px; color: #888; margin-bottom: 18px; }
.hint.tight { margin: 0 0 8px; }
label { display: block; font-size: 12px; color: #888; margin: 10px 0 4px; }
input[type='text'], input[type='password'], input[type='tel'], input:not([type]) {
  width: 100%;
  padding: 8px 10px;
  background: #16162a;
  border: 1px solid #333;
  border-radius: 6px;
  color: #ddd;
  font-size: 13px;
}
.row { display: flex; align-items: center; gap: 12px; margin-top: 16px; flex-wrap: wrap; }
button {
  padding: 8px 16px;
  border-radius: 6px;
  border: 1px solid #444;
  font-size: 13px;
  cursor: pointer;
  color: #fff;
  background: #333;
}
.go { background: #2e7d32; border-color: #2e7d32; }
.stop { background: #c62828; border-color: #c62828; }
button:disabled { opacity: 0.55; cursor: wait; }
button.busy { opacity: 0.7; }
.status { font-size: 12px; color: #888; text-transform: uppercase; }
.status.joined { color: #66bb6a; }
.status.error { color: #ef5350; }
.xfer { font-size: 12px; color: #888; font-variant-numeric: tabular-nums; margin-left: auto; }
.err { color: #ef5350; font-size: 13px; margin-top: 8px; }
.ack { font-size: 11px; color: #666; margin-top: 6px; }
.mode {
  font-size: 28px;
  font-weight: 700;
  color: #eee;
  margin: 24px 0 4px;
  letter-spacing: 0.04em;
}
.call { font-size: 18px; color: #bbb; margin: 0 0 8px; }
.ring { margin-top: 16px; padding: 12px; border: 1px solid #444; border-radius: 8px; }
.ring p { margin: 0; color: #eee; }
.dbg { margin-top: 28px; color: #888; }
.dbg summary { cursor: pointer; }
.meters { margin-top: 12px; display: flex; flex-direction: column; gap: 10px; }
.diag { margin-top: 10px; font-size: 11px; color: #777; font-family: monospace; word-break: break-all; }
.diag.legs { margin-top: 2px; padding-left: 14px; }
.diag.section-title { margin-top: 18px; color: #aaa; }
.row.compact { margin-top: 6px; gap: 6px; }
.row.compact button { padding: 6px 9px; font-size: 11px; }
.restore { margin-top: 14px; }
.meter { display: flex; align-items: center; gap: 10px; font-size: 12px; color: #aaa; }
.meter .bar {
  flex: 1;
  height: 8px;
  background: #222;
  border-radius: 4px;
  overflow: hidden;
}
.meter .bar i {
  display: block;
  height: 100%;
  background: #64b5f6;
}
.meter label { margin: 0; display: flex; align-items: center; gap: 4px; }
</style>
