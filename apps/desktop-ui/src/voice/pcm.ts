export const SAMPLE_RATE = 16_000
export const FRAME_MS = 10
export const FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 10 ms
export const FRAME_PREFIX = 0xa1

export function resample(input: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (input.length === 0) return input
  if (!Number.isFinite(fromRate) || !Number.isFinite(toRate) || fromRate <= 0 || toRate <= 0) {
    return input
  }
  if (fromRate === toRate) return input
  const outLen = Math.round(input.length * toRate / fromRate)
  if (outLen <= 0 || outLen > 48_000) return new Float32Array(0)
  const ratio = fromRate / toRate
  const out = new Float32Array(outLen)
  for (let i = 0; i < outLen; i++) {
    const src = i * ratio
    const i0 = Math.floor(src)
    const i1 = Math.min(i0 + 1, input.length - 1)
    const t = src - i0
    out[i] = input[i0] * (1 - t) + input[i1] * t
  }
  return out
}

/** Chunked resample that keeps the interpolation phase across packets (avoids ticks at 20 ms edges). */
export function createResampler(fromRate: number, toRate: number): (chunk: Float32Array) => Float32Array {
  let rest = new Float32Array(0)
  if (!Number.isFinite(fromRate) || !Number.isFinite(toRate) || fromRate <= 0 || toRate <= 0) {
    return (chunk) => chunk
  }
  if (fromRate === toRate) {
    return (chunk) => chunk
  }
  const ratio = fromRate / toRate
  return (chunk: Float32Array): Float32Array => {
    if (chunk.length === 0) return chunk
    const merged = new Float32Array(rest.length + chunk.length)
    merged.set(rest)
    merged.set(chunk, rest.length)
    if (merged.length < 2) {
      rest = merged
      return new Float32Array(0)
    }
    const outLen = Math.floor((merged.length - 1) / ratio)
    if (outLen <= 0) {
      rest = merged
      return new Float32Array(0)
    }
    const out = new Float32Array(outLen)
    for (let i = 0; i < outLen; i++) {
      const src = i * ratio
      const i0 = Math.floor(src)
      const i1 = Math.min(i0 + 1, merged.length - 1)
      const t = src - i0
      out[i] = merged[i0] * (1 - t) + merged[i1] * t
    }
    const consumed = Math.min(merged.length, Math.floor((outLen) * ratio))
    rest = merged.subarray(consumed)
    return out
  }
}

export function floatToS16(input: Float32Array): Int16Array {
  const out = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i]))
    out[i] = s < 0 ? Math.round(s * 0x8000) : Math.round(s * 0x7fff)
  }
  return out
}

export function s16ToFloat(input: Int16Array): Float32Array {
  const out = new Float32Array(input.length)
  for (let i = 0; i < input.length; i++) out[i] = input[i] / 0x8000
  return out
}

export function encodeFrame(samples: Int16Array): ArrayBuffer {
  const buf = new ArrayBuffer(1 + samples.length * 2)
  const view = new DataView(buf)
  view.setUint8(0, FRAME_PREFIX)
  for (let i = 0; i < samples.length; i++) view.setInt16(1 + i * 2, samples[i], true)
  return buf
}

export function decodeFrame(raw: ArrayBuffer): Int16Array | null {
  if (raw.byteLength < 3) return null
  const view = new DataView(raw)
  if (view.getUint8(0) !== FRAME_PREFIX) return null
  const n = (raw.byteLength - 1) >> 1
  const out = new Int16Array(n)
  for (let i = 0; i < n; i++) out[i] = view.getInt16(1 + i * 2, true)
  return out
}

export function rms(samples: ArrayLike<number>): number {
  if (samples.length === 0) return 0
  let s = 0
  for (let i = 0; i < samples.length; i++) {
    const v = samples[i]
    s += v * v
  }
  return Math.sqrt(s / samples.length)
}
