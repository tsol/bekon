import { deleteJson, fetchJson, patchJson, postJson } from './http'
import type {
  AdapterInstance,
  AdapterLogMap,
  AdapterTypeInfo,
  AddAdapterPayload,
  DebugMap,
  DebugMessage,
  SendPayload,
  TunnelDetail,
  TunnelListItem,
  UIMessage,
} from './types'

export class WlyaCoreClient {
  private base: string

  constructor(base = '') {
    this.base = base
  }

  private url(path: string): string {
    return `${this.base}${path}`
  }

  listAdapterTypes(): Promise<AdapterTypeInfo[]> {
    return fetchJson(this.url('/api/adapter-types'))
  }

  listTunnels(): Promise<TunnelListItem[]> {
    return fetchJson(this.url('/api/tunnels'))
  }

  getTunnel(id: string): Promise<TunnelDetail> {
    return fetchJson(this.url(`/api/tunnels/${id}`))
  }

  createTunnel(payload: { label: string; channel?: string; secret?: string }): Promise<TunnelDetail> {
    return postJson(this.url('/api/tunnels'), payload)
  }

  deleteTunnel(id: string): Promise<{ ok: boolean }> {
    return deleteJson(this.url(`/api/tunnels/${id}`))
  }

  updateConfig(id: string, patch: { label?: string; channel?: string; secret?: string; autostart?: boolean }): Promise<TunnelDetail> {
    return patchJson(this.url(`/api/tunnels/${id}/config`), patch)
  }

  start(id: string): Promise<{ clientId: string; running: boolean }> {
    return postJson(this.url(`/api/tunnels/${id}/start`))
  }

  stop(id: string): Promise<{ ok: boolean }> {
    return postJson(this.url(`/api/tunnels/${id}/stop`))
  }

  send(id: string, payload: SendPayload): Promise<{ seq: number }> {
    return postJson(this.url(`/api/tunnels/${id}/send`), payload)
  }

  advertiseAdapters(id: string, adapterIds: string[]): Promise<{ seq: number }> {
    return postJson(this.url(`/api/tunnels/${id}/advertise-adapters`), { adapterIds })
  }

  async getMessages(id: string): Promise<UIMessage[]> {
    const data = await fetchJson<{ messages: UIMessage[] }>(this.url(`/api/tunnels/${id}/messages`))
    return data.messages
  }

  listAdapters(id: string): Promise<AdapterInstance[]> {
    return fetchJson(this.url(`/api/tunnels/${id}/adapters`))
  }

  addAdapter(id: string, payload: AddAdapterPayload): Promise<{ ok: boolean; id: string }> {
    return postJson(this.url(`/api/tunnels/${id}/adapters`), payload)
  }

  updateAdapter(id: string, adapterId: string, config: Record<string, string>): Promise<{ ok: boolean; id: string }> {
    return patchJson(this.url(`/api/tunnels/${id}/adapters/${adapterId}`), config)
  }

  removeAdapter(id: string, adapterId: string): Promise<{ ok: boolean }> {
    return deleteJson(this.url(`/api/tunnels/${id}/adapters/${adapterId}`))
  }

  clearAdapter(id: string, adapterId: string): Promise<{ ok: boolean }> {
    return postJson(this.url(`/api/tunnels/${id}/adapters/${adapterId}/clear`))
  }

  startAdapter(id: string, adapterId: string): Promise<{ ok: boolean; id: string }> {
    return postJson(this.url(`/api/tunnels/${id}/adapters/${adapterId}/start`))
  }

  stopAdapter(id: string, adapterId: string): Promise<{ ok: boolean; id: string }> {
    return postJson(this.url(`/api/tunnels/${id}/adapters/${adapterId}/stop`))
  }

  getDebug(id: string): Promise<DebugMap> {
    return fetchJson(this.url(`/api/tunnels/${id}/debug`))
  }

  async getDebugAdapter(id: string, adapterName: string, last = 10): Promise<DebugMessage[]> {
    const data = await fetchJson<{ messages: DebugMessage[] }>(
      this.url(`/api/tunnels/${id}/debug/${encodeURIComponent(adapterName)}?last=${last}`)
    )
    return data.messages
  }

  getAdapterLog(id: string): Promise<AdapterLogMap> {
    return fetchJson(this.url(`/api/tunnels/${id}/adapter-log`))
  }

  /** Start tunnel if stopped (required before send reaches adapters). */
  async ensureRunning(id: string): Promise<TunnelDetail> {
    const t = await this.getTunnel(id)
    if (t.running) return t
    await this.start(id)
    return this.getTunnel(id)
  }
}

export const coreClient = new WlyaCoreClient()
