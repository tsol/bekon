export interface CanvasPoint {
  x: number
  y: number
}

export interface DevicePoint {
  x: number
  y: number
}

export interface ImageLayout {
  naturalWidth: number
  naturalHeight: number
  displayWidth: number
  displayHeight: number
  /** Real display pixels (a11y / tap API). Falls back to JPEG size if omitted. */
  screenWidth?: number
  screenHeight?: number
}

function drawnBox(layout: ImageLayout) {
  const { naturalWidth, naturalHeight, displayWidth, displayHeight } = layout
  const scale = Math.min(displayWidth / naturalWidth, displayHeight / naturalHeight)
  const drawnW = naturalWidth * scale
  const drawnH = naturalHeight * scale
  const offsetX = (displayWidth - drawnW) / 2
  const offsetY = (displayHeight - drawnH) / 2
  return { scale, drawnW, drawnH, offsetX, offsetY }
}

function screenSize(layout: ImageLayout) {
  const screenWidth = layout.screenWidth && layout.screenWidth > 0
    ? layout.screenWidth
    : layout.naturalWidth
  const screenHeight = layout.screenHeight && layout.screenHeight > 0
    ? layout.screenHeight
    : layout.naturalHeight
  return { screenWidth, screenHeight }
}

/** JPEG pixels → display pixels (phone API / a11y). */
export function screenshotToScreen(x: number, y: number, layout: ImageLayout): DevicePoint {
  const { naturalWidth, naturalHeight } = layout
  const { screenWidth, screenHeight } = screenSize(layout)
  if (!naturalWidth || !naturalHeight || (screenWidth === naturalWidth && screenHeight === naturalHeight)) {
    return { x: Math.round(x), y: Math.round(y) }
  }
  return {
    x: Math.round(x * screenWidth / naturalWidth),
    y: Math.round(y * screenHeight / naturalHeight),
  }
}

/** Display pixels → JPEG pixels. */
export function screenToScreenshot(x: number, y: number, layout: ImageLayout): DevicePoint {
  const { naturalWidth, naturalHeight } = layout
  const { screenWidth, screenHeight } = screenSize(layout)
  if (!naturalWidth || !naturalHeight || !screenWidth || !screenHeight
    || (screenWidth === naturalWidth && screenHeight === naturalHeight)) {
    return { x, y }
  }
  return {
    x: x * naturalWidth / screenWidth,
    y: y * naturalHeight / screenHeight,
  }
}

/** Map click on displayed canvas to device (screen) pixels. */
export function canvasToDevice(
  canvasX: number,
  canvasY: number,
  layout: ImageLayout,
): DevicePoint | null {
  const { naturalWidth, naturalHeight, displayWidth, displayHeight } = layout
  if (!naturalWidth || !naturalHeight || !displayWidth || !displayHeight) return null

  const { scale, drawnW, drawnH, offsetX, offsetY } = drawnBox(layout)

  const relX = canvasX - offsetX
  const relY = canvasY - offsetY

  const margin = 6
  if (relX < -margin || relY < -margin || relX > drawnW + margin || relY > drawnH + margin) {
    return null
  }

  const clampedX = Math.max(0, Math.min(drawnW, relX))
  const clampedY = Math.max(0, Math.min(drawnH, relY))

  return screenshotToScreen(clampedX / scale, clampedY / scale, layout)
}

/** Map device (screen) pixels back onto the displayed letterboxed image. */
export function deviceToCanvas(
  deviceX: number,
  deviceY: number,
  layout: ImageLayout,
): CanvasPoint | null {
  const { naturalWidth, naturalHeight, displayWidth, displayHeight } = layout
  if (!naturalWidth || !naturalHeight || !displayWidth || !displayHeight) return null

  const { scale, offsetX, offsetY } = drawnBox(layout)
  const shot = screenToScreenshot(deviceX, deviceY, layout)
  return {
    x: offsetX + shot.x * scale,
    y: offsetY + shot.y * scale,
  }
}
