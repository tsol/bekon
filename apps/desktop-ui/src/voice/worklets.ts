export const CAPTURE_PROCESSOR = 'wlya-capture'
export const PLAYBACK_PROCESSOR = 'wlya-playback'

const CAPTURE_SRC = `
class CaptureProcessor extends AudioWorkletProcessor {
  process(inputs) {
    const ch = inputs[0] && inputs[0][0]
    if (ch && ch.length) this.port.postMessage(ch.slice())
    return true
  }
}
registerProcessor('${CAPTURE_PROCESSOR}', CaptureProcessor)
`

const PLAYBACK_SRC = `
class PlaybackProcessor extends AudioWorkletProcessor {
  constructor() {
    super()
    this.queue = []
    this.offset = 0
    this.muted = false
    this.port.onmessage = (e) => {
      const d = e.data
      if (d && d.type === 'mute') { this.muted = !!d.value; return }
      if (d && d.type === 'flush') { this.queue = []; this.offset = 0; return }
      if (d instanceof Float32Array) {
        this.queue.push(d)
        let samples = 0
        for (const q of this.queue) samples += q.length
        while (samples > 24000 && this.queue.length > 1) {
          samples -= this.queue[0].length
          this.queue.shift()
          this.offset = 0
        }
      }
    }
  }
  process(_inputs, outputs) {
    const out = outputs[0] && outputs[0][0]
    if (!out) return true
    if (this.muted) { out.fill(0); return true }
    let i = 0
    while (i < out.length) {
      if (!this.queue.length) { out.fill(0, i); break }
      const buf = this.queue[0]
      const n = Math.min(out.length - i, buf.length - this.offset)
      out.set(buf.subarray(this.offset, this.offset + n), i)
      i += n
      this.offset += n
      if (this.offset >= buf.length) { this.queue.shift(); this.offset = 0 }
    }
    return true
  }
}
registerProcessor('${PLAYBACK_PROCESSOR}', PlaybackProcessor)
`

function blobUrl(src: string): string {
  return URL.createObjectURL(new Blob([src], { type: 'text/javascript' }))
}

export function captureWorkletUrl(): string {
  return blobUrl(CAPTURE_SRC)
}

export function playbackWorkletUrl(): string {
  return blobUrl(PLAYBACK_SRC)
}
