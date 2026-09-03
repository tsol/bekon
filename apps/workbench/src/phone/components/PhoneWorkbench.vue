<script setup lang="ts">
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAppLogContext } from '../../app/selectedTunnel'
import { usePhoneSession } from '../composables/usePhoneSession'
import PingIndicator from './PingIndicator.vue'
import ScreenshotCanvas from './ScreenshotCanvas.vue'
import GestureQueuePanel from './GestureQueuePanel.vue'
import ReplyPanel from './ReplyPanel.vue'
import type { GestureInput, KeyCmd, NavCmd, SnapshotItem } from '../types'

const props = defineProps<{ tunnelId?: string }>()
const router = useRouter()
const { logTunnelId, setLogTunnel } = useAppLogContext()

const tunnelId = ref(props.tunnelId || logTunnelId.value || '')
const actShot = ref(true)
const actShotSleepMs = ref(500)
const captureMode = ref<'screenshot' | 'hires' | 'snapshot'>('screenshot')
const captureOpen = ref(false)
const captureRef = ref<HTMLElement | null>(null)
const draft = ref('')
const asKeys = ref(false)

const LS = 'bekon.capture.'
function loadNum(key: string, fallback: number) {
  const raw = localStorage.getItem(LS + key)
  if (raw == null || raw === '') return fallback
  const n = Number(raw)
  return Number.isFinite(n) ? n : fallback
}
function loadBool(key: string, fallback: boolean) {
  const v = localStorage.getItem(LS + key)
  if (v === 'true') return true
  if (v === 'false') return false
  return fallback
}
const previewScale = ref(loadNum('previewScale', 0.5))
const previewQuality = ref(loadNum('previewQuality', 45))
const hiresScale = ref(loadNum('hiresScale', 1))
const hiresQuality = ref(loadNum('hiresQuality', 70))
const snapshotHiRes = ref(loadBool('snapshotHiRes', true))

watch(previewScale, (v) => localStorage.setItem(LS + 'previewScale', String(v)))
watch(previewQuality, (v) => localStorage.setItem(LS + 'previewQuality', String(v)))
watch(hiresScale, (v) => localStorage.setItem(LS + 'hiresScale', String(v)))
watch(hiresQuality, (v) => localStorage.setItem(LS + 'hiresQuality', String(v)))
watch(snapshotHiRes, (v) => localStorage.setItem(LS + 'snapshotHiRes', String(v)))

function clampScale(v: number) {
  return Math.min(1, Math.max(0.1, v))
}
function clampQuality(v: number) {
  return Math.min(100, Math.max(1, Math.round(v)))
}
function shotFields(hiRes: boolean): Pick<GestureInput, 'hiRes' | 'scale' | 'quality'> {
  return hiRes
    ? { hiRes: true, scale: clampScale(hiresScale.value), quality: clampQuality(hiresQuality.value) }
    : { scale: clampScale(previewScale.value), quality: clampQuality(previewQuality.value) }
}
function screenshotItem(hiRes: boolean): GestureInput {
  return { kind: 'screenshot', ...shotFields(hiRes) }
}
function snapshotItem(): GestureInput {
  const hi = snapshotHiRes.value
  return { kind: 'snapshot', hiRes: hi, ...shotFields(hi) }
}

const session = usePhoneSession(tunnelId)

watch(() => props.tunnelId, (id) => {
  if (id) tunnelId.value = id
}, { immediate: true })

watch(tunnelId, (id) => {
  if (id) router.replace(`/phone/${id}`)
  setLogTunnel(id || null)
}, { immediate: true })

watch(logTunnelId, (id) => {
  if (!id) {
    tunnelId.value = ''
    return
  }
  if (id !== tunnelId.value) tunnelId.value = id
})

watch(() => session.state.value.lastClip, (clip) => {
  if (clip !== undefined) draft.value = clip
})

const hasTunnel = computed(() => !!tunnelId.value)
const queue = computed(() => session.state.value.queue)
const executing = computed(() => session.state.value.executing || session.state.value.busy)
const shotLoading = computed(() => session.state.value.waitingFor === 'screenshot')
const lastError = computed(() => session.localError.value || session.state.value.lastError)
const a11yText = computed(() => session.state.value.a11yJson)
const screenshotSrc = computed(() => session.screenshotSrc())
const pinging = computed(() => session.state.value.waitingFor === 'pong')
const lastPing = computed(() => session.state.value.lastPing)
const screenSnapshot = computed(() => session.state.value.lastSnapshot)
const lastPutFile = computed(() => session.state.value.lastPutFile)
const lastLogs = computed(() => session.state.value.lastLogs)
const fileInput = ref<HTMLInputElement | null>(null)
const replyTab = ref<'snapshot' | 'a11y' | 'file' | 'logs'>('snapshot')

async function sendViaQueue(body: GestureInput) {
  return sendViaQueueMany([body])
}

async function sendViaQueueMany(items: GestureInput[]) {
  if (!items.length || executing.value) return
  if (actShot.value) {
    await session.clear()
    const batch: GestureInput[] = [...items]
    const last = items[items.length - 1]
    if (last.kind !== 'ping' && last.kind !== 'screenshot' && last.kind !== 'snapshot' && last.kind !== 'file' && last.kind !== 'share' && last.kind !== 'logs') {
      if (actShotSleepMs.value > 0) batch.push({ kind: 'sleep', ms: actShotSleepMs.value })
      batch.push(captureMode.value === 'hires' ? screenshotItem(true) : screenshotItem(false))
    }
    await session.enqueue(batch)
    await session.execute()
  } else {
    await session.enqueue(items)
  }
}

function onTap(x: number, y: number) {
  void sendViaQueue({ kind: 'tap', x, y })
}

function onSwipe(x1: number, y1: number, x2: number, y2: number) {
  void sendViaQueue({ kind: 'swipe', x1, y1, x2, y2 })
}

function onLongPress(x: number, y: number) {
  void sendViaQueue({ kind: 'longPress', x, y })
}

function onDragMove(from: { x: number; y: number }, to: { x: number; y: number }) {
  void sendViaQueueMany([
    { kind: 'drag', x: from.x, y: from.y },
    { kind: 'swipe', x1: from.x, y1: from.y, x2: to.x, y2: to.y },
    { kind: 'release' },
  ])
}

const captureLabel = computed(() => {
  if (captureMode.value === 'hires') return 'Screenshot Hi-res'
  if (captureMode.value === 'snapshot') return 'Snapshot'
  return 'Screenshot'
})

function runCapture() {
  if (captureMode.value === 'snapshot') {
    replyTab.value = 'snapshot'
    void sendViaQueue(snapshotItem())
    return
  }
  replyTab.value = 'a11y'
  void sendViaQueue(screenshotItem(captureMode.value === 'hires'))
}

function pickCapture(mode: 'screenshot' | 'hires' | 'snapshot') {
  captureMode.value = mode
  captureOpen.value = false
  runCapture()
}

function onCaptureDocClick(ev: MouseEvent) {
  const el = captureRef.value
  if (!el || !captureOpen.value) return
  if (!el.contains(ev.target as Node)) captureOpen.value = false
}

function onCaptureKey(ev: KeyboardEvent) {
  if (ev.key === 'Escape') captureOpen.value = false
}

onMounted(() => {
  document.addEventListener('click', onCaptureDocClick)
  document.addEventListener('keydown', onCaptureKey)
})
onUnmounted(() => {
  document.removeEventListener('click', onCaptureDocClick)
  document.removeEventListener('keydown', onCaptureKey)
})

function pickFile() {
  fileInput.value?.click()
}

async function onFileChosen(ev: Event) {
  const input = ev.target as HTMLInputElement
  const f = input.files?.[0]
  input.value = ''
  if (!f) return
  const max = 25 * 1024 * 1024
  if (f.size > max) {
    session.localError.value = 'file too large (max 25 MB)'
    return
  }
  replyTab.value = 'file'
  const buf = new Uint8Array(await f.arrayBuffer())
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < buf.length; i += chunk) {
    binary += String.fromCharCode(...buf.subarray(i, i + chunk))
  }
  await sendViaQueue({
    kind: 'file',
    name: f.name,
    mime: f.type || 'application/octet-stream',
    data: btoa(binary),
  })
}

const highlightItems = ref<SnapshotItem[]>([])

function onSnapshotTap(item: SnapshotItem) {
  void sendViaQueue({ kind: 'tap', x: item.x, y: item.y })
}

function queueNav(cmd: NavCmd) {
  void sendViaQueue({ kind: 'nav', nav: cmd })
}

function onKeyboardInput(text: string) {
  void sendViaQueue({ kind: 'input', text: text.slice(0, 4000), inputMode: asKeys.value ? 'keys' : 'text' })
}

function onKeyCmd(key: KeyCmd) {
  void sendViaQueue({ kind: 'key', key })
}

function onClipboard() {
  void sendViaQueue({ kind: 'clipboard' })
}

function fetchLogs() {
  replyTab.value = 'logs'
  void sendViaQueue({ kind: 'logs' })
}
</script>

<template>
  <div class="phone-workbench">
    <header v-if="hasTunnel" class="top">
      <div class="left">
        <div ref="captureRef" class="capture-split">
          <button
            class="shot shot-main"
            :class="{ snap: captureMode === 'snapshot' }"
            type="button"
            :disabled="shotLoading || executing"
            @click="runCapture"
          >
            {{ captureLabel }}
            <span class="spin" :class="{ on: shotLoading }" aria-hidden="true" />
          </button>
          <button
            class="shot shot-chevron"
            :class="{ snap: captureMode === 'snapshot', open: captureOpen }"
            type="button"
            title="Capture mode"
            aria-label="Capture mode"
            :aria-expanded="captureOpen"
            :disabled="shotLoading || executing"
            @click.stop="captureOpen = !captureOpen"
          >
            <svg class="chevron" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
              <path d="M2.5 4.5L6 8L9.5 4.5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </button>
          <div v-if="captureOpen" class="capture-menu" role="listbox">
            <button type="button" class="item" :class="{ selected: captureMode === 'screenshot' }" @click="pickCapture('screenshot')">Screenshot</button>
            <button type="button" class="item" :class="{ selected: captureMode === 'hires' }" @click="pickCapture('hires')">Screenshot Hi-res</button>
            <button type="button" class="item" :class="{ selected: captureMode === 'snapshot' }" @click="pickCapture('snapshot')">Snapshot</button>
            <div class="knobs" @click.stop>
              <label>Preview
                <input type="number" min="0.1" max="1" step="0.05" v-model.number="previewScale" @change="previewScale = clampScale(previewScale)">
                ×
                <input type="number" min="1" max="100" step="5" v-model.number="previewQuality" @change="previewQuality = clampQuality(previewQuality)">
                q
              </label>
              <label>Hi-res
                <input type="number" min="0.1" max="1" step="0.05" v-model.number="hiresScale" @change="hiresScale = clampScale(hiresScale)">
                ×
                <input type="number" min="1" max="100" step="5" v-model.number="hiresQuality" @change="hiresQuality = clampQuality(hiresQuality)">
                q
              </label>
              <label class="snap-mode">
                Snapshot
                <select
                  :value="snapshotHiRes ? 'hi' : 'preview'"
                  @change="snapshotHiRes = ($event.target as HTMLSelectElement).value === 'hi'"
                >
                  <option value="hi">hi-res</option>
                  <option value="preview">ordinary</option>
                </select>
              </label>
            </div>
          </div>
        </div>
        <input ref="fileInput" type="file" hidden @change="onFileChosen" />
        <button
          class="shot file"
          type="button"
          :disabled="shotLoading || executing"
          title="Send a file to the phone inbox (max 25 MB). Phone replies with the saved path. APK auto-installs if enabled on the phone."
          @click="pickFile"
        >
          File
        </button>
        <button
          class="shot logs"
          type="button"
          :disabled="shotLoading || executing"
          title="Fetch recent adapter, message, core, and APK-update logs. Phone Status must have Share logs on request enabled."
          @click="fetchLogs"
        >
          Logs
        </button>
        <PingIndicator
          :pinging="pinging || executing"
          :latency-ms="lastPing?.latencyMs ?? null"
          :ok="lastPing ? lastPing.ok : null"
          @ping="sendViaQueue({ kind: 'ping' })"
        />
      </div>
      <div class="navs">
        <button class="nav" type="button" title="Back" @click="queueNav('back')">
          <svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="currentColor" d="M15.5 5.5 9 12l6.5 6.5L14 20 6 12l8-8z" />
          </svg>
          <span class="nav-label">Back</span>
        </button>
        <button class="nav" type="button" title="Home" @click="queueNav('home')">
          <svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="currentColor" d="M12 4.5 4 11h2.2V20h4.4v-5.2h2.8V20h4.4v-9H20z" />
          </svg>
          <span class="nav-label">Home</span>
        </button>
        <button class="nav" type="button" title="Recents" @click="queueNav('recentApps')">
          <svg class="nav-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="none" stroke="currentColor" stroke-width="2" d="M7 7.5h10.5v10.5H7z" />
            <path fill="none" stroke="currentColor" stroke-width="2" d="M4.5 4.5H15v1.8" />
          </svg>
          <span class="nav-label">Recents</span>
        </button>
      </div>
    </header>

    <div v-if="!hasTunnel" class="pick-hint">Pick a tunnel from the Phone Control menu in the app bar.</div>

    <div v-else class="grid">
      <ScreenshotCanvas
        :image-url="screenshotSrc"
        :queue="queue"
        :screen-width="session.state.value.screenW"
        :screen-height="session.state.value.screenH"
        :highlights="highlightItems"
        @tap="onTap"
        @swipe="onSwipe"
        @long-press="onLongPress"
        @drag-move="onDragMove"
      />
      <div class="side">
      <GestureQueuePanel
        v-model:act-shot="actShot"
        v-model:sleep-ms="actShotSleepMs"
        v-model:draft="draft"
        v-model:as-keys="asKeys"
        :queue="queue"
        :executing="executing"
        :last-error="lastError"
        @execute="session.execute()"
        @clear="session.clear()"
        @remove="session.remove($event)"
        @input="onKeyboardInput"
        @key="onKeyCmd"
        @clipboard="onClipboard"
        @abort="session.abort()"
      />
      <ReplyPanel
        v-model:tab="replyTab"
        :text="a11yText"
        :snapshot="screenSnapshot"
        :file="lastPutFile"
        :logs="lastLogs"
        @tap-item="onSnapshotTap"
        @highlight="highlightItems = $event"
      />
      </div>
    </div>
  </div>
</template>

<style scoped>
.phone-workbench {
  height: 100%;
  min-height: 0;
  padding: 16px;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.top {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.left, .navs {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.shot {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: none;
  border-radius: 4px;
  padding: 6px 12px;
  background: #1565c0;
  color: #fff;
  cursor: pointer;
  font-size: 12px;
  white-space: nowrap;
}
.shot:disabled { opacity: .7; cursor: wait; }
.shot.snap { background: #00695c; }
.shot.file { background: #5d4037; }
.shot.logs { background: #37474f; }
.capture-split {
  position: relative;
  display: inline-flex;
}
.shot-main {
  border-radius: 4px 0 0 4px;
}
.shot-chevron {
  border-radius: 0 4px 4px 0;
  border-left: 1px solid rgba(255, 255, 255, 0.28);
  padding: 6px 8px;
}
.shot-chevron.open .chevron {
  transform: rotate(180deg);
}
.chevron {
  display: block;
  transition: transform 0.15s ease;
}
.capture-menu {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 30;
  min-width: 200px;
  padding: 4px;
  background: #16162a;
  border: 1px solid #444;
  border-radius: 6px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.45);
}
.capture-menu .item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: #ddd;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.capture-menu .item:hover { background: #2a2a4a; }
.capture-menu .item.selected { background: #24344f; color: #fff; }
.knobs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 6px;
  padding: 8px 10px 10px;
  border-top: 1px solid #333;
  color: #bbb;
  font-size: 12px;
}
.knobs label {
  display: flex;
  align-items: center;
  gap: 6px;
}
.knobs input,
.knobs select {
  width: 64px;
  padding: 3px 5px;
  background: #0f0f1a;
  border: 1px solid #444;
  border-radius: 4px;
  color: #eee;
  font-size: 12px;
}
.knobs .snap-mode select { width: 90px; }
.spin {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  display: none;
}
.spin.on {
  display: block;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.nav {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  padding: 6px 10px;
  background: #37474f;
  color: #ddd;
  cursor: pointer;
  font-size: 12px;
}
.nav-icon { display: none; width: 18px; height: 18px; }
.nav-label { display: inline; }
.pick-hint { color: #777; padding: 40px; text-align: center; flex: 1; }
.grid {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px;
  align-items: stretch;
  overflow: hidden;
}
.side {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  gap: 12px;
  overflow: auto;
}
@media (max-width: 900px) {
  .phone-workbench {
    height: auto;
    min-height: 100%;
    padding: 8px 0 12px;
    overflow: visible;
  }
  .top {
    flex-shrink: 0;
    padding: 0 8px;
  }
  .nav { padding: 6px 8px; }
  .nav-label { display: none; }
  .nav-icon { display: block; }
  .grid {
    flex: 0 0 auto;
    height: auto;
    display: flex;
    flex-direction: column;
    grid-template-columns: none;
    overflow: visible;
  }
  .grid :deep(.queue-panel) {
    min-width: 0;
    margin: 0 8px;
    max-height: none;
    overflow: visible;
  }
  .side {
    overflow: visible;
  }
  .side :deep(.reply-panel) {
    margin: 0 8px;
  }
}
@media (max-width: 700px) {
  .grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
