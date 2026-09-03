export type SnapshotSource = 'a11y' | 'ocr'

export type SnapshotItem = {
  ref: string
  source: SnapshotSource
  name: string
  x: number
  y: number
  /** Screen pixels `[left, top, right, bottom]`. */
  bounds?: [number, number, number, number]
}

export type ScreenSnapshot = {
  source: SnapshotSource[]
  captureW: number
  captureH: number
  screenW: number
  screenH: number
  items: SnapshotItem[]
  ocrCount: number
  ocrError?: string
}

type A11yNode = {
  bounds?: number[]
  text?: string
  desc?: string
  clickable?: boolean
}

function norm(s: string): string {
  return s.toLowerCase().replace(/\s+/g, ' ').trim()
}

function coveredByA11y(text: string, a11yNames: string[]): boolean {
  const t = norm(text)
  if (t.length < 2) return false
  return a11yNames.some(n => n === t)
}

/** Strip leading/trailing junk (®, =, ©) and drop symbol-only OCR. */
function cleanOcrText(raw: string): string | null {
  let t = raw.replace(/\s+/g, ' ').trim()
  t = t.replace(/^[^\p{L}\p{N}]+/u, '').replace(/[^\p{L}\p{N}]+$/u, '').trim()
  const letters = t.match(/\p{L}/gu) ?? []
  if (letters.length < 2) return null
  return t
}

function pointInRect(x: number, y: number, r: number[], pad = 6): boolean {
  const [l, t, right, b] = r
  return x >= l - pad && x <= right + pad && y >= t - pad && y <= b + pad
}

/** Status/nav chrome already in a11y — skip OCR in those bands. */
function chromeBands(rects: number[][], screenH: number): { top: number; bottom: number } {
  const capTop = Math.round(screenH * 0.14)
  const capBot = Math.round(screenH * 0.88)
  let top = Math.round(screenH * 0.055)
  let bottom = capBot
  for (const [l, t, r, b] of rects) {
    if (b <= t) continue
    if (t <= screenH * 0.02) top = Math.max(top, b)
    if (b >= screenH * 0.92) bottom = Math.min(bottom, t)
  }
  return { top: Math.min(top, capTop), bottom: Math.max(bottom, capBot) }
}

function toScreen(
  imgX: number, imgY: number,
  captureW: number, captureH: number,
  screenW: number, screenH: number,
): { x: number; y: number } {
  if (!captureW || !captureH) return { x: Math.round(imgX), y: Math.round(imgY) }
  return {
    x: Math.round(imgX * screenW / captureW),
    y: Math.round(imgY * screenH / captureH),
  }
}

export function parseA11yNodes(raw: string): A11yNode[] {
  if (!raw?.trim()) return []
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? v as A11yNode[] : []
  } catch {
    return []
  }
}

export function buildScreenSnapshot(opts: {
  a11yJson: string
  ocr: { text: string; bbox: [number, number, number, number] }[]
  captureW: number
  captureH: number
  screenW: number
  screenH: number
  ocrError?: string
  /** OCR list only — skip a11y merge. Chrome bands still skip status/nav strips. */
  ocrOnly?: boolean
}): ScreenSnapshot {
  const captureW = opts.captureW || opts.screenW || 1
  const captureH = opts.captureH || opts.screenH || 1
  const screenW = opts.screenW || captureW
  const screenH = opts.screenH || captureH
  const items: SnapshotItem[] = []
  const a11yNames: string[] = []
  const a11yRects: number[][] = []
  const sources = new Set<SnapshotSource>()
  let n = 1
  let ocrKept = 0

  const nextRef = () => `e${n++}`

  for (const node of opts.ocrOnly ? [] : parseA11yNodes(opts.a11yJson)) {
    const name = (node.text || node.desc || '').trim()
    const b = node.bounds
    if (!b || b.length < 4) continue
    const [l, t, r, bot] = b
    const w = r - l
    const h = bot - t
    if (w <= 0 || h <= 0) continue
    if (!name && !node.clickable) continue
    if (w >= screenW * 0.95 && h >= screenH * 0.95) continue
    const label = name || 'clickable'
    items.push({
      ref: nextRef(),
      source: 'a11y',
      name: label,
      x: Math.round((l + r) / 2),
      y: Math.round((t + bot) / 2),
      bounds: [l, t, r, bot],
    })
    sources.add('a11y')
    a11yRects.push([l, t, r, bot])
    if (name) a11yNames.push(norm(name))
  }

  const chrome = chromeBands(a11yRects, screenH)

  for (const block of opts.ocr) {
    const text = cleanOcrText(block.text)
    if (!text || coveredByA11y(text, a11yNames)) continue
    const [x1, y1, x2, y2] = block.bbox
    const c = toScreen((x1 + x2) / 2, (y1 + y2) / 2, captureW, captureH, screenW, screenH)
    if (c.y <= chrome.top || c.y >= chrome.bottom) continue
    if (a11yRects.some(r => pointInRect(c.x, c.y, r))) continue
    const tl = toScreen(x1, y1, captureW, captureH, screenW, screenH)
    const br = toScreen(x2, y2, captureW, captureH, screenW, screenH)
    items.push({
      ref: nextRef(),
      source: 'ocr',
      name: text,
      x: c.x,
      y: c.y,
      bounds: [tl.x, tl.y, br.x, br.y],
    })
    sources.add('ocr')
    ocrKept++
  }

  return {
    source: [...sources],
    captureW,
    captureH,
    screenW,
    screenH,
    items,
    ocrCount: ocrKept,
    ocrError: opts.ocrError,
  }
}
