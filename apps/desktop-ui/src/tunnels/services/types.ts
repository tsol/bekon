export interface AdapterTypeInfo {
  type: string
  label: string
  defaultConfig: Record<string, string>
}

export interface AdapterInstance {
  id: string
  type: string
  label: string
  config: Record<string, string>
  enabled?: boolean
  running?: boolean
  role?: string
  effectiveRole?: string
  duty?: string
  nextPollAtMs?: number | null
  lastInboundAtMs?: number | null
  idleUntilMs?: number | null
  lastPollAtMs?: number | null
  lastPollError?: string | null
  lastPollErrorAtMs?: number | null
}

export interface TunnelListItem {
  id: string
  label: string
  channel: string
  running: boolean
  autostart?: boolean
  adapters: AdapterInstance[]
}

export interface TunnelDetail {
  id: string
  label: string
  channel: string
  secret: string
  running: boolean
  autostart: boolean
  clientId: string
}

export interface AttachmentInfo {
  id: string
  name: string
  mimeType: string
  size: number
}

export interface UIMessage {
  seq: number
  from: string
  plaintext: string
  direction: 'in' | 'out' | string
  timestamp: number
  attachments?: AttachmentInfo[]
}

export interface DebugMessage {
  transportSeq: number
  from: string
  plaintext: string
  timestamp: number
  raw?: string
  iv?: string
  crc?: string
}

export interface SendPayload {
  plaintext: string
  attachments?: Array<{ name: string; mimeType: string; data: string }>
}

export interface AddAdapterPayload {
  type: string
  id?: string
  label?: string
  config?: Record<string, string>
}

export type AdapterLogMap = Record<string, string[]>
export type DebugMap = Record<string, DebugMessage[]>
