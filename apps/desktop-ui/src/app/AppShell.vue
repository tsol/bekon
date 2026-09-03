<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import NavTabs from './NavTabs.vue'
import TunnelLogPanel from '../tunnels/components/TunnelLogPanel.vue'
import { useAppLogContext } from './selectedTunnel'

const { logTunnelId, logAdapterId, setLogAdapter } = useAppLogContext()

const LOGS_SIZE_KEY = 'wlya.logsPanelSize'
const LOGS_VISIBLE_KEY = 'wlya.logsPanelVisible'
const DEFAULT_SIZE = 360
const MIN_SIZE = 140
const MAX_SIZE = 720
const STACK_MQ = '(max-width: 900px)'

const shellBodyRef = ref<HTMLElement | null>(null)
const panelSize = ref(DEFAULT_SIZE)
const isResizing = ref(false)
const isStacked = ref(false)
const logsVisible = ref(true)

let stackMq: MediaQueryList | null = null

function clampSize(size: number, max: number): number {
  return Math.round(Math.max(MIN_SIZE, Math.min(max, size)))
}

function maxPanelSize(): number {
  const el = shellBodyRef.value
  if (!el) return MAX_SIZE
  const limit = isStacked.value ? el.clientHeight : el.clientWidth
  return Math.min(MAX_SIZE, Math.floor(limit * 0.75))
}

function loadSavedSize() {
  try {
    const raw = localStorage.getItem(LOGS_SIZE_KEY)
    if (!raw) return
    const n = Number(raw)
    if (Number.isFinite(n)) panelSize.value = clampSize(n, maxPanelSize())
  } catch { /* ignore */ }
}

function loadVisible() {
  try {
    const raw = localStorage.getItem(LOGS_VISIBLE_KEY)
    if (raw === '0' || raw === 'false') logsVisible.value = false
  } catch { /* ignore */ }
}

function saveSize() {
  try {
    localStorage.setItem(LOGS_SIZE_KEY, String(panelSize.value))
  } catch { /* ignore */ }
}

function saveVisible() {
  try {
    localStorage.setItem(LOGS_VISIBLE_KEY, logsVisible.value ? '1' : '0')
  } catch { /* ignore */ }
}

function updateStacked() {
  isStacked.value = stackMq?.matches ?? false
  panelSize.value = clampSize(panelSize.value, maxPanelSize())
}

const logsPanelStyle = computed(() => (
  isStacked.value
    ? { height: `${panelSize.value}px`, width: '100%' }
    : { width: `${panelSize.value}px`, height: '100%' }
))

function resizeFromPointer(clientX: number, clientY: number) {
  const el = shellBodyRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const next = isStacked.value
    ? rect.bottom - clientY
    : rect.right - clientX
  panelSize.value = clampSize(next, maxPanelSize())
}

function onPointerMove(e: PointerEvent) {
  if (!isResizing.value) return
  resizeFromPointer(e.clientX, e.clientY)
}

function stopResize() {
  if (!isResizing.value) return
  isResizing.value = false
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
  saveSize()
}

function startResize(e: PointerEvent) {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  e.preventDefault()
  isResizing.value = true
  ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  document.body.style.cursor = isStacked.value ? 'row-resize' : 'col-resize'
  document.body.style.userSelect = 'none'
  resizeFromPointer(e.clientX, e.clientY)
}

function resetSize() {
  panelSize.value = DEFAULT_SIZE
  saveSize()
}

watch(logsVisible, saveVisible)

onMounted(() => {
  stackMq = window.matchMedia(STACK_MQ)
  updateStacked()
  loadSavedSize()
  loadVisible()
  stackMq.addEventListener('change', updateStacked)
})

onUnmounted(() => {
  stackMq?.removeEventListener('change', updateStacked)
  stopResize()
})
</script>

<template>
  <div class="app-shell" :class="{ resizing: isResizing, stacked: isStacked }">
    <NavTabs v-model:logs-visible="logsVisible" />
    <div
      ref="shellBodyRef"
      class="shell-body"
      :class="{ stacked: isStacked }"
    >
      <main class="shell-main">
        <router-view />
      </main>

      <template v-if="logsVisible">
        <div
          class="resize-handle"
          role="separator"
          :aria-orientation="isStacked ? 'horizontal' : 'vertical'"
          :aria-valuenow="panelSize"
          aria-label="Resize log panel"
          title="Drag to resize · double-click to reset"
          @pointerdown="startResize"
          @pointermove="onPointerMove"
          @pointerup="stopResize"
          @pointercancel="stopResize"
          @dblclick="resetSize"
        />

        <aside class="shell-logs" :style="logsPanelStyle">
          <TunnelLogPanel
            :tunnel-id="logTunnelId"
            :adapter-id="logAdapterId"
            @update:adapter-id="setLogAdapter"
          />
        </aside>
      </template>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  min-height: 0;
}

.shell-body {
  flex: 1;
  display: flex;
  flex-direction: row;
  min-height: 0;
  min-width: 0;
}

.shell-main {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}

.resize-handle {
  flex: 0 0 6px;
  cursor: col-resize;
  background: #141424;
  border-left: 1px solid #333;
  border-right: 1px solid #333;
  touch-action: none;
  z-index: 2;
}

.resize-handle:hover,
.app-shell.resizing .resize-handle {
  background: #2a3a5a;
  border-color: #455a64;
}

.shell-logs {
  flex: 0 0 auto;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 8px;
  background: #0d0d1a;
}

.shell-body.stacked {
  flex-direction: column;
}

.shell-body.stacked .resize-handle {
  cursor: row-resize;
  flex: 0 0 14px;
  width: 100%;
  border-left: none;
  border-right: none;
  border-top: 1px solid #333;
  border-bottom: 1px solid #333;
  background:
    #141424
    linear-gradient(#666, #666) center / 48px 3px no-repeat;
}

.shell-body.stacked .shell-main {
  flex: 1 1 auto;
}

.shell-body.stacked .shell-logs {
  flex: 0 0 auto;
}

.app-shell.resizing {
  cursor: col-resize;
}

.app-shell.resizing.stacked {
  cursor: row-resize;
}
</style>
