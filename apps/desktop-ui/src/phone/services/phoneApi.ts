import type { GestureInput, PhoneState, ScreenSnapshot, TunnelListItem } from '../types'

const BASE = '/phone-api'

export class PhoneApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = 'PhoneApiError'
    this.status = status
  }
}

async function parseError(res: Response): Promise<string> {
  try {
    const body = await res.json() as { error?: string }
    if (body && typeof body.error === 'string') return body.error
    return JSON.stringify(body)
  } catch {
    return res.statusText || `HTTP ${res.status}`
  }
}

async function json<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) throw new PhoneApiError(res.status, await parseError(res))
  return res.json() as Promise<T>
}

function post<T>(url: string, body?: unknown): Promise<T> {
  return json<T>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body === undefined ? {} : body),
  })
}

function del<T>(url: string): Promise<T> {
  return json<T>(url, { method: 'DELETE' })
}

function tunnel(id: string): string {
  return `${BASE}/tunnels/${encodeURIComponent(id)}`
}

export const phoneApi = {
  listTunnels(): Promise<TunnelListItem[]> {
    return json(`${BASE}/tunnels`)
  },

  getState(id: string): Promise<PhoneState> {
    return json(`${tunnel(id)}/state`)
  },

  screenshotSrc(id: string, at: number): string {
    return `${tunnel(id)}/screenshot?t=${at}`
  },

  enqueue(id: string, body: GestureInput | GestureInput[]): Promise<{ ok: boolean; state: PhoneState }> {
    return post(`${tunnel(id)}/queue`, body)
  },

  remove(id: string, gestureId: string): Promise<PhoneState> {
    return del(`${tunnel(id)}/queue/${encodeURIComponent(gestureId)}`)
  },

  clear(id: string): Promise<PhoneState> {
    return del(`${tunnel(id)}/queue`)
  },

  execute(id: string): Promise<PhoneState> {
    return post(`${tunnel(id)}/queue/execute`)
  },

  abort(id: string): Promise<{ ok: boolean; aborted: boolean }> {
    return post(`${tunnel(id)}/queue/abort`)
  },

  postSnapshot(id: string, body?: { hiRes?: boolean; scale?: number; quality?: number }): Promise<{ snapshot: ScreenSnapshot }> {
    return post(`${tunnel(id)}/snapshot`, body ?? {})
  },

  getSnapshot(id: string): Promise<{ snapshot: ScreenSnapshot }> {
    return json(`${tunnel(id)}/snapshot`)
  },
}
