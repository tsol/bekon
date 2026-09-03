import { HttpError, type TunnelListItem, type TunnelMessageLite } from './types.js'

async function parseError(res: Response): Promise<string> {
  try {
    const body = await res.json() as { error?: string }
    if (body && typeof body.error === 'string') return body.error
    return JSON.stringify(body)
  } catch {
    return res.statusText || `HTTP ${res.status}`
  }
}

export class WlyaTunnelClient {
  constructor(private readonly base: string) {}

  private url(path: string): string {
    return `${this.base}${path}`
  }

  private async json<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(this.url(path), init)
    if (!res.ok) throw new HttpError(res.status, await parseError(res))
    return res.json() as Promise<T>
  }

  listTunnels(): Promise<TunnelListItem[]> {
    return this.json('/api/tunnels')
  }

  getTunnel(id: string): Promise<{ id: string; running: boolean }> {
    return this.json(`/api/tunnels/${encodeURIComponent(id)}`)
  }

  start(id: string): Promise<{ running: boolean }> {
    return this.json(`/api/tunnels/${encodeURIComponent(id)}/start`, { method: 'POST' })
  }

  send(id: string, plaintext: string): Promise<{ seq: number }> {
    return this.json(`/api/tunnels/${encodeURIComponent(id)}/send`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ plaintext }),
    })
  }

  async getMessages(id: string): Promise<TunnelMessageLite[]> {
    const data = await this.json<{ messages: Array<{
      seq: number
      plaintext: string
      direction: string
      timestamp: number
    }> }>(`/api/tunnels/${encodeURIComponent(id)}/messages?full=1`)
    return data.messages
  }

  async ensureRunning(id: string): Promise<void> {
    const t = await this.getTunnel(id)
    if (t.running) return
    await this.start(id)
  }
}
