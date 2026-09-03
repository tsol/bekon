import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify'
import type { GestureInput, SessionRegistry } from './session.js'
import type { WlyaTunnelClient } from './tunnel-client.js'
import { BusyError, HttpError, type GestureKind, type KeyCmd, type NavCmd } from './types.js'

function sendError(reply: FastifyReply, err: unknown) {
  if (err instanceof BusyError) {
    return reply.code(409).send({ error: err.message })
  }
  if (err instanceof HttpError) {
    return reply.code(err.status).send({ error: err.message })
  }
  const msg = err instanceof Error ? err.message : String(err)
  return reply.code(500).send({ error: msg })
}

async function readBody(req: FastifyRequest): Promise<unknown> {
  const raw = req.body
  if (raw == null || raw === '') return {}
  if (typeof raw === 'string') {
    if (!raw.trim()) return {}
    return JSON.parse(raw)
  }
  return raw
}

function asGesture(body: Record<string, unknown>, kind?: GestureKind): GestureInput {
  const k = (kind ?? body.kind) as GestureKind
  const kinds: GestureKind[] = ['tap', 'swipe', 'longPress', 'drag', 'release', 'nav', 'input', 'key', 'clipboard', 'sleep', 'screenshot', 'snapshot', 'file', 'share', 'ping', 'logs']
  if (!k || !kinds.includes(k)) throw new HttpError(400, 'kind required')
  const ms = num(body.ms) ?? num(body.sleepMs)
  return {
    kind: k,
    x: num(body.x),
    y: num(body.y),
    x1: num(body.x1),
    y1: num(body.y1),
    x2: num(body.x2),
    y2: num(body.y2),
    nav: body.nav as NavCmd | undefined,
    text: typeof body.text === 'string' ? body.text : undefined,
    inputMode: body.inputMode === 'keys' ? 'keys' : body.inputMode === 'text' ? 'text' : undefined,
    key: body.key as KeyCmd | undefined,
    n: num(body.n),
    ms,
    hiRes: parseBool(body.hiRes),
    scale: num(body.scale),
    quality: num(body.quality),
    name: typeof body.name === 'string' ? body.name : undefined,
    mime: typeof body.mime === 'string' ? body.mime : typeof body.mimeType === 'string' ? body.mimeType : undefined,
    data: typeof body.data === 'string' ? body.data : undefined,
    path: typeof body.path === 'string' ? body.path : undefined,
    uri: typeof body.uri === 'string' ? body.uri : undefined,
    pkg: typeof body.pkg === 'string' ? body.pkg : typeof body.package === 'string' ? body.package : undefined,
    package: typeof body.package === 'string' ? body.package : undefined,
  }
}

function parseBool(v: unknown): boolean | undefined {
  if (v === true || v === 'true') return true
  if (v === false || v === 'false') return false
  return undefined
}

function num(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && v.trim() && Number.isFinite(Number(v))) return Number(v)
  return undefined
}

function tunnelId(req: FastifyRequest): string {
  return (req.params as { id: string }).id
}

function enqueueBody(session: ReturnType<SessionRegistry['get']>, raw: unknown) {
  const items: GestureInput[] = []
  if (Array.isArray(raw)) {
    for (const el of raw) {
      if (el && typeof el === 'object' && !Array.isArray(el)) items.push(asGesture(el as Record<string, unknown>))
    }
  } else if (raw && typeof raw === 'object' && Array.isArray((raw as { items?: unknown }).items)) {
    for (const el of (raw as { items: unknown[] }).items) {
      if (el && typeof el === 'object' && !Array.isArray(el)) items.push(asGesture(el as Record<string, unknown>))
    }
  } else if (raw && typeof raw === 'object') {
    items.push(asGesture(raw as Record<string, unknown>))
  }
  if (items.length === 0) throw new HttpError(400, 'kind required')
  const gestures = session.enqueueMany(items)
  return { ok: true, gestures, state: session.snapshot() }
}

export function registerRoutes(
  app: FastifyInstance,
  tunnels: WlyaTunnelClient,
  sessions: SessionRegistry,
) {
  app.get('/health', async () => ({ ok: true }))

  app.get('/tunnels', async (_req, reply) => {
    try {
      const list = await tunnels.listTunnels()
      return list.map(t => ({ id: t.id, label: t.label, running: t.running }))
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.get('/tunnels/:id/state', async (req, reply) => {
    try {
      return sessions.get(tunnelId(req)).snapshot()
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.get('/tunnels/:id/screenshot', async (req, reply) => {
    const session = sessions.get(tunnelId(req))
    const jpeg = session.jpeg
    if (!jpeg) return reply.code(404).send({ error: 'no screenshot' })
    return reply.type(session.screenshotMime || 'image/jpeg').send(jpeg)
  })

  app.get('/tunnels/:id/events', async (req, reply) => {
    const session = sessions.get(tunnelId(req))
    reply.hijack()
    reply.raw.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
      'Access-Control-Allow-Origin': '*',
    })
    reply.raw.write('retry: 2000\n\n')
    const write = (data: unknown) => {
      reply.raw.write(`data: ${JSON.stringify(data)}\n\n`)
    }
    const unsub = session.subscribe(state => write(state))
    const ping = setInterval(() => {
      reply.raw.write(': ping\n\n')
    }, 15000)
    req.raw.on('close', () => {
      clearInterval(ping)
      unsub()
    })
  })

  app.post('/tunnels/:id/queue', async (req, reply) => {
    try {
      return enqueueBody(sessions.get(tunnelId(req)), await readBody(req))
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.delete('/tunnels/:id/queue/:gestureId', async (req, reply) => {
    try {
      const { gestureId } = req.params as { id: string; gestureId: string }
      const session = sessions.get(tunnelId(req))
      session.remove(gestureId)
      return session.snapshot()
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.delete('/tunnels/:id/queue', async (req, reply) => {
    try {
      const session = sessions.get(tunnelId(req))
      session.clear()
      return session.snapshot()
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.post('/tunnels/:id/queue/execute', async (req, reply) => {
    try {
      return await sessions.get(tunnelId(req)).doExecute()
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.post('/tunnels/:id/queue/abort', async (req, reply) => {
    try {
      return sessions.get(tunnelId(req)).abort()
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.post('/tunnels/:id/snapshot', async (req, reply) => {
    try {
      const raw = await readBody(req)
      const body = raw && typeof raw === 'object' && !Array.isArray(raw)
        ? raw as Record<string, unknown>
        : {}
      return await sessions.get(tunnelId(req)).captureAndSnapshot({
        hiRes: parseBool(body.hiRes),
        scale: num(body.scale),
        quality: num(body.quality),
      })
    } catch (err) {
      return sendError(reply, err)
    }
  })

  app.get('/tunnels/:id/snapshot', async (req, reply) => {
    const snapshot = sessions.get(tunnelId(req)).lastSnapshot
    if (!snapshot) return reply.code(404).send({ error: 'no snapshot' })
    return { snapshot }
  })
}
