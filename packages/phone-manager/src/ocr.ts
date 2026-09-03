import { createWorker, PSM, type Worker } from 'tesseract.js'

export type OcrBlock = {
  text: string
  bbox: [number, number, number, number]
  conf: number
}

let worker: Worker | null = null
let loading: Promise<Worker> | null = null

async function getWorker(): Promise<Worker> {
  if (worker) return worker
  if (!loading) {
    loading = (async () => {
      // Array form: 'eng+rus' looks for a nonexistent combined traineddata file.
      const w = await createWorker(['eng', 'rus'])
      await w.setParameters({
        tessedit_pageseg_mode: PSM.SPARSE_TEXT,
        user_defined_dpi: '160',
      })
      worker = w
      return w
    })()
  }
  return loading
}

function pushLine(
  blocks: OcrBlock[],
  text: string | undefined,
  bbox: { x0: number; y0: number; x1: number; y1: number } | undefined,
  confidence?: number,
) {
  const t = (text || '').replace(/\s+/g, ' ').trim()
  if (!t || !bbox) return
  const conf = typeof confidence === 'number' ? confidence / 100 : 0
  blocks.push({
    text: t,
    bbox: [Math.round(bbox.x0), Math.round(bbox.y0), Math.round(bbox.x1), Math.round(bbox.y1)],
    conf: Math.round(conf * 1000) / 1000,
  })
}

export async function ocrImage(image: Buffer): Promise<OcrBlock[]> {
  const w = await getWorker()
  const { data } = await w.recognize(image, {}, { text: true, blocks: true })
  const blocks: OcrBlock[] = []
  for (const block of data.blocks ?? []) {
    for (const para of block.paragraphs ?? []) {
      for (const line of para.lines ?? []) {
        pushLine(blocks, line.text, line.bbox, line.confidence)
        if (!(line.text || '').trim()) {
          for (const word of line.words ?? []) {
            pushLine(blocks, word.text, word.bbox, word.confidence)
          }
        }
      }
    }
  }
  return blocks
}
