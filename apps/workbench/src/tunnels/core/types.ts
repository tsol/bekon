import type { z } from 'zod'

// ── Adapter manifest (registered by each adapter type) ──

export interface AdapterManifest {
  type: string
  label: string
  schema: z.ZodObject<any>
  defaultConfig: Record<string, any>
  factory: (config: any) => BaseAdapter
}

// ── Base adapter interface ──

export abstract class BaseAdapter {
  abstract readonly name: string
  readonly log: string[] = []

  /** Maximum content bytes per TransportMessage. Larger content is auto-split into multipart. */
  readonly windowSize: number = 4096

  protected logEvent(msg: string): void {
    const ts = new Date().toISOString().slice(11, 23)
    this.log.push(`[${ts}] [${this.name}] ${msg}`)
  }

  abstract init(channel: string): Promise<void>
  abstract poll(lastTransportSeq: number): Promise<TransportMessage[]>
  abstract send(msg: TransportMessage): Promise<void>
}

// ── Transport layer — raw adapter messages ──

export interface TransportMessage {
  id: string
  from: string
  content: string      // encrypted JSON of TunnelMessage (or a part of it)
  iv: string
  crc: string
  timestamp: number
  transportSeq: number  // per-adapter seq (its window)

  // Multipart — set when content is split across multiple TransportMessages
  partOf?: string       // TunnelMessage UUID that this is a part of
  partIndex?: number    // index of this part (0-based)
  totalParts?: number   // total number of parts
}

// ── Tunnel layer — logical messages ──

export interface Attachment {
  id: string
  name: string
  mimeType: string
  size: number
  data: string  // base64
}

export interface TunnelMessage {
  id: string           // UUID, for dedup across adapters
  seq: number          // tunnel-level sequence
  from: string
  text: string         // plaintext content
  timestamp: number
  attachments?: Attachment[]
}

// ── Handlers called by Tunnel (data layer) ──

export interface TunnelHandlers {
  onMessage(msg: TunnelMessage, direction: 'in' | 'out'): void
  onDebug(adapterName: string, tMsg: TransportMessage, decryptedJson: string): void
}

// ── Persisted configs ──

export interface AdapterInstanceConfig {
  type: string
  id: string
  label: string
  config: Record<string, any>
}

export interface TunnelConfig {
  id: string
  label: string
  channel: string
  secret: string
  clientId: string
  running: boolean
  tunnelSeq: number
  lastTransportSeqs: Record<string, number>
  deliveredSeq: number
  writeSeqs: Record<string, number>
  adapters: AdapterInstanceConfig[]
}

export type TunnelList = string[]

// ── Store adapter interface ──

export interface StoreAdapter {
  get<T>(key: string): Promise<T | null>
  set<T>(key: string, value: T): Promise<void>
  remove(key: string): Promise<void>
}

// ── API response types ──

export interface AttachmentInfo {
  id: string
  name: string
  mimeType: string
  size: number
  data: string  // base64 — included for direct download
}

export interface UIMessage {
  seq: number
  from: string
  plaintext: string
  direction: 'in' | 'out'
  timestamp: number
  attachments?: AttachmentInfo[]
}

export interface DebugMessage {
  transportSeq: number
  from: string
  raw?: string       // encrypted content (truncated)
  iv?: string
  crc?: string
  tunnelId?: string   // high-level message id
  tunnelSeq?: number  // high-level seq
  plaintext: string
  timestamp: number
  partOf?: string     // multipart
  partIndex?: number
  totalParts?: number
}
