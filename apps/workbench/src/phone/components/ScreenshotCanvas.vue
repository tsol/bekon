<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { canvasToDevice, deviceToCanvas, type ImageLayout } from '../lib/coords'
import type { Gesture, SnapshotItem } from '../types'

const props = defineProps<{
  imageUrl?: string | null
  queue: Gesture[]
  screenWidth?: number | null
  screenHeight?: number | null
  highlights?: SnapshotItem[]
}>()

const emit = defineEmits<{
  tap: [x: number, y: number]
  swipe: [x1: number, y1: number, x2: number, y2: number]
  longPress: [x: number, y: number]
  dragMove: [from: { x: number; y: number }, to: { x: number; y: number }]
  error: [msg: string]
}>()

const wrap = ref<HTMLElement | null>(null)
const frame = ref<HTMLElement | null>(null)
const overlay = ref<HTMLCanvasElement | null>(null)
const naturalW = ref(0)
const naturalH = ref(0)
const displayW = ref(320)
const displayH = ref(640)
const fillWidth = ref(false)
let resizeObs: ResizeObserver | null = null
let widthMq: MediaQueryList | null = null

let dragStart: { x: number; y: number } | null = null
let dragCurrent: { x: number; y: number } | null = null
let dragging = false
let holdTimer: ReturnType<typeof setTimeout> | null = null
let holding = false
let animFrame = 0

const TAP_SLOP = 10
const SCROLL_GUTTER = 18
const FALLBACK_ASPECT_W = 9
const FALLBACK_ASPECT_H = 19.5

const layout = computed((): ImageLayout => ({
  naturalWidth: naturalW.value,
  naturalHeight: naturalH.value,
  displayWidth: displayW.value,
  displayHeight: displayH.value,
  screenWidth: props.screenWidth ?? undefined,
  screenHeight: props.screenHeight ?? undefined,
}))

const frameStyle = computed(() => ({
  width: `${displayW.value}px`,
  height: `${displayH.value}px`,
}))

function liveLayout(): ImageLayout {
  const el = overlay.value ?? frame.value
  const rect = el?.getBoundingClientRect()
  return {
    naturalWidth: naturalW.value,
    naturalHeight: naturalH.value,
    displayWidth: rect && rect.width > 0 ? rect.width : displayW.value,
    displayHeight: rect && rect.height > 0 ? rect.height : displayH.value,
    screenWidth: props.screenWidth ?? undefined,
    screenHeight: props.screenHeight ?? undefined,
  }
}

function localPoint(ev: PointerEvent) {
  const el = overlay.value ?? frame.value
  if (!el) return null
  const rect = el.getBoundingClientRect()
  return { x: ev.clientX - rect.left, y: ev.clientY - rect.top }
}

function toDevice(p: { x: number; y: number }) {
  return canvasToDevice(p.x, p.y, liveLayout())
}

function statusColor(status?: string): { stroke: string; fill: string } {
  switch (status) {
    case 'sending':
      return { stroke: 'rgba(255,183,77,0.95)', fill: 'rgba(255,183,77,0.55)' }
    case 'ok':
      return { stroke: 'rgba(129,199,132,0.95)', fill: 'rgba(129,199,132,0.55)' }
    case 'error':
      return { stroke: 'rgba(239,83,80,0.95)', fill: 'rgba(239,83,80,0.55)' }
    case 'timeout':
      return { stroke: 'rgba(171,71,188,0.95)', fill: 'rgba(171,71,188,0.55)' }
    case 'aborted':
      return { stroke: 'rgba(239,83,80,0.95)', fill: 'rgba(239,83,80,0.4)' }
    default:
      return { stroke: 'rgba(100,181,246,0.9)', fill: 'rgba(100,181,246,0.55)' }
  }
}

function highlightColor(source: SnapshotItem['source']): string {
  return source === 'ocr' ? 'rgba(255,183,77,0.95)' : 'rgba(100,181,246,0.95)'
}

function drawHighlight(ctx: CanvasRenderingContext2D, it: SnapshotItem) {
  const b = it.bounds
  if (!b || b.length < 4) return
  const tl = deviceToCanvas(b[0], b[1], layout.value)
  const br = deviceToCanvas(b[2], b[3], layout.value)
  if (!tl || !br) return
  const x = Math.min(tl.x, br.x)
  const y = Math.min(tl.y, br.y)
  const rw = Math.abs(br.x - tl.x)
  const rh = Math.abs(br.y - tl.y)
  if (rw < 1 && rh < 1) return
  const stroke = highlightColor(it.source)
  ctx.save()
  ctx.strokeStyle = stroke
  ctx.fillStyle = stroke.replace('0.95', '0.12')
  ctx.lineWidth = 1.5
  ctx.fillRect(x, y, rw, rh)
  ctx.strokeRect(x, y, rw, rh)
  ctx.fillStyle = stroke
  ctx.font = '10px ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace'
  ctx.textBaseline = 'top'
  const label = it.ref
  const pad = 2
  const tw = ctx.measureText(label).width
  const lx = x
  const ly = y > 12 ? y - 12 : y
  ctx.fillRect(lx, ly, tw + pad * 2, 12)
  ctx.fillStyle = '#111'
  ctx.fillText(label, lx + pad, ly + 1)
  ctx.restore()
}

function drawOverlay(now = performance.now()) {
  const canvas = overlay.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return

  const w = displayW.value
  const h = displayH.value
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w
    canvas.height = h
  }
  ctx.clearRect(0, 0, w, h)

  for (const it of props.highlights ?? []) {
    drawHighlight(ctx, it)
  }

  const pulse = 0.5 + 0.5 * Math.sin(now / 180)

  for (const g of props.queue) {
    if (g.kind === 'nav' || g.kind === 'input' || g.kind === 'key' || g.kind === 'clipboard' || g.kind === 'sleep' || g.kind === 'screenshot' || g.kind === 'snapshot' || g.kind === 'file' || g.kind === 'share' || g.kind === 'ping' || g.kind === 'release' || g.kind === 'logs') continue
    const colors = statusColor(g.status)
    const sending = g.status === 'sending'
    const radius = sending ? 7 + pulse * 5 : g.kind === 'longPress' || g.kind === 'drag' ? 11 : 8
    ctx.strokeStyle = colors.stroke
    ctx.fillStyle = colors.fill
    ctx.lineWidth = sending ? 2.5 : 2

    if (g.kind === 'tap' || g.kind === 'longPress' || g.kind === 'drag') {
      const p = deviceToCanvas(g.x ?? 0, g.y ?? 0, layout.value)
      if (!p) continue
      ctx.beginPath()
      ctx.arc(p.x, p.y, radius, 0, Math.PI * 2)
      ctx.fill()
      ctx.stroke()
      if (g.kind === 'longPress' || g.kind === 'drag') {
        ctx.beginPath()
        ctx.arc(p.x, p.y, radius + 5, 0, Math.PI * 2)
        ctx.stroke()
      }
      if (sending) {
        ctx.beginPath()
        ctx.arc(p.x, p.y, radius + 8 + pulse * 4, 0, Math.PI * 2)
        ctx.strokeStyle = colors.stroke
        ctx.globalAlpha = 0.4
        ctx.stroke()
        ctx.globalAlpha = 1
      }
    } else if (g.kind === 'swipe') {
      const a = deviceToCanvas(g.x1 ?? 0, g.y1 ?? 0, layout.value)
      const b = deviceToCanvas(g.x2 ?? 0, g.y2 ?? 0, layout.value)
      if (!a || !b) continue
      ctx.beginPath()
      ctx.moveTo(a.x, a.y)
      ctx.lineTo(b.x, b.y)
      ctx.stroke()
      ctx.beginPath()
      ctx.arc(a.x, a.y, 5, 0, Math.PI * 2)
      ctx.fill()
      ctx.beginPath()
      ctx.arc(b.x, b.y, sending ? 6 + pulse * 3 : 6, 0, Math.PI * 2)
      ctx.fill()
      ctx.stroke()
    }
  }

  if (dragStart && dragCurrent && dragging) {
    ctx.strokeStyle = 'rgba(255,213,79,0.95)'
    ctx.fillStyle = 'rgba(255,213,79,0.7)'
    ctx.lineWidth = 2
    ctx.setLineDash([6, 4])
    ctx.beginPath()
    ctx.moveTo(dragStart.x, dragStart.y)
    ctx.lineTo(dragCurrent.x, dragCurrent.y)
    ctx.stroke()
    ctx.setLineDash([])
    ctx.beginPath()
    ctx.arc(dragStart.x, dragStart.y, 5, 0, Math.PI * 2)
    ctx.fill()
    ctx.beginPath()
    ctx.arc(dragCurrent.x, dragCurrent.y, 6, 0, Math.PI * 2)
    ctx.fill()
  } else if (dragStart && !dragging && !holding) {
    ctx.strokeStyle = 'rgba(100,181,246,0.7)'
    ctx.beginPath()
    ctx.arc(dragStart.x, dragStart.y, 10, 0, Math.PI * 2)
    ctx.stroke()
  }
}

function loop(now: number) {
  drawOverlay(now)
  animFrame = requestAnimationFrame(loop)
}

function finishPointer(end: { x: number; y: number } | null) {
  if (!dragStart && !holding) return
  const start = dragStart
  const wasDragging = dragging
  const wasHolding = holding
  const origin = start
  dragStart = null
  dragCurrent = null
  dragging = false
  holding = false

  if (wasHolding) {
    const from = origin ?? end
    const to = end ?? origin
    if (!from) {
      drawOverlay()
      return
    }
    const dest = to ?? from
    const moved =
      Math.abs(dest.x - from.x) > TAP_SLOP ||
      Math.abs(dest.y - from.y) > TAP_SLOP
    const d1 = toDevice(from)
    const d2 = toDevice(dest)
    if (moved && d1 && d2) emit('dragMove', d1, d2)
    else if (d1) emit('longPress', d1.x, d1.y)
    drawOverlay()
    return
  }

  if (!start) {
    drawOverlay()
    return
  }

  const endPoint = end ?? start
  const dx = Math.abs(endPoint.x - start.x)
  const dy = Math.abs(endPoint.y - start.y)

  if (wasDragging || dx > TAP_SLOP || dy > TAP_SLOP) {
    const d1 = toDevice(start)
    const d2 = toDevice(endPoint)
    if (d1 && d2) emit('swipe', d1.x, d1.y, d2.x, d2.y)
    drawOverlay()
    return
  }

  const d = toDevice(start)
  if (d) emit('tap', d.x, d.y)
  else emit('error', 'Tap outside screenshot area')
  drawOverlay()
}

function onPointerDown(ev: PointerEvent) {
  if (ev.pointerType === 'mouse' && ev.button !== 0) return
  ev.preventDefault()
  ;(ev.currentTarget as HTMLElement).setPointerCapture(ev.pointerId)
  const p = localPoint(ev)
  if (!p) return
  holding = false
  dragging = false
  dragStart = p
  dragCurrent = p
  if (holdTimer) clearTimeout(holdTimer)
  holdTimer = setTimeout(() => {
    if (dragging || !dragStart) return
    holding = true
    drawOverlay()
  }, 500)
  drawOverlay()
}

function onPointerMove(ev: PointerEvent) {
  if (!dragStart) return
  const p = localPoint(ev)
  if (!p) return
  dragCurrent = p
  if (holding) {
    dragging = true
    drawOverlay()
    return
  }
  const dx = Math.abs(p.x - dragStart.x)
  const dy = Math.abs(p.y - dragStart.y)
  if (dx > TAP_SLOP || dy > TAP_SLOP) {
    if (holdTimer) {
      clearTimeout(holdTimer)
      holdTimer = null
    }
    dragging = true
  }
  drawOverlay()
}

function onPointerUp(ev: PointerEvent) {
  if (holdTimer) clearTimeout(holdTimer)
  holdTimer = null
  finishPointer(localPoint(ev))
}

function onPointerCancel() {
  if (holdTimer) clearTimeout(holdTimer)
  holdTimer = null
  finishPointer(null)
}

function loadImageMeta(url: string): Promise<void> {
  return new Promise((resolve) => {
    const img = new Image()
    img.onload = () => {
      naturalW.value = img.naturalWidth
      naturalH.value = img.naturalHeight
      fitToWrap()
      resolve()
    }
    img.onerror = () => resolve()
    img.src = url
  })
}

function fitToWrap() {
  const el = wrap.value
  if (!el) return
  const gutter = fillWidth.value ? SCROLL_GUTTER : 0
  const availW = Math.max(1, el.clientWidth - gutter - 4)
  const availH = Math.max(1, el.clientHeight - 4)

  const nw = naturalW.value > 0 ? naturalW.value : FALLBACK_ASPECT_W
  const nh = naturalH.value > 0 ? naturalH.value : FALLBACK_ASPECT_H
  const scale = fillWidth.value
    ? availW / nw
    : Math.min(availW / nw, availH / nh)
  displayW.value = Math.max(1, Math.floor(nw * scale))
  displayH.value = Math.max(1, Math.floor(nh * scale))
  nextTick(() => drawOverlay())
}

function syncFillWidth() {
  fillWidth.value = widthMq?.matches ?? false
  fitToWrap()
}

onMounted(() => {
  widthMq = window.matchMedia('(max-width: 900px)')
  fillWidth.value = widthMq.matches
  widthMq.addEventListener('change', syncFillWidth)
  fitToWrap()
  resizeObs = new ResizeObserver(() => fitToWrap())
  if (wrap.value) resizeObs.observe(wrap.value)
  animFrame = requestAnimationFrame(loop)
})

watch(() => props.imageUrl, (url) => {
  if (!url) {
    naturalW.value = 0
    naturalH.value = 0
    fitToWrap()
    return
  }
  void loadImageMeta(url)
})

watch(() => props.queue, () => drawOverlay(), { deep: true })
watch(() => props.highlights, () => drawOverlay(), { deep: true })

onUnmounted(() => {
  widthMq?.removeEventListener('change', syncFillWidth)
  resizeObs?.disconnect()
  if (holdTimer) clearTimeout(holdTimer)
  cancelAnimationFrame(animFrame)
})
</script>

<template>
  <div ref="wrap" class="device-wrap" :class="{ 'fill-width': fillWidth }">
    <div
      ref="frame"
      class="frame"
      :style="frameStyle"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerCancel"
    >
      <img v-if="imageUrl" :src="imageUrl" alt="device" class="shot" />
      <div v-else class="placeholder">No screenshot — press Screenshot</div>
      <canvas ref="overlay" class="overlay" />
    </div>
    <div
      v-if="fillWidth"
      class="scroll-gutter"
      title="Scroll the page here"
      aria-hidden="true"
    />
  </div>
</template>

<style scoped>
.device-wrap {
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}
.frame {
  position: relative;
  flex: 0 0 auto;
  overflow: hidden;
  background: #000;
  border: 2px solid #333;
  border-radius: 12px;
  box-sizing: content-box;
  cursor: crosshair;
  touch-action: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.shot {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: fill;
  pointer-events: none;
}
.placeholder { color: #666; font-size: 12px; text-align: center; padding: 16px; }
.device-wrap.fill-width {
  height: auto;
  align-items: stretch;
  justify-content: flex-start;
}
.scroll-gutter {
  flex: 0 0 18px;
  width: 18px;
  align-self: stretch;
  touch-action: pan-y;
  cursor: ns-resize;
  background:
    #141422
    linear-gradient(#5a5a70, #5a5a70) center / 3px 42% no-repeat;
}
.overlay {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
</style>
