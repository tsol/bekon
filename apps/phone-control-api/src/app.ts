import Fastify from 'fastify'
import cors from '@fastify/cors'
import { registerRoutes } from './routes.js'
import { SessionRegistry } from './session.js'
import { WlyaTunnelClient } from './tunnel-client.js'

export async function buildApp(wlyaTunnelUrl: string) {
  const app = Fastify({ logger: true, bodyLimit: 40 * 1024 * 1024 })
  await app.register(cors, { origin: true })

  const tunnels = new WlyaTunnelClient(wlyaTunnelUrl)
  const sessions = new SessionRegistry(tunnels)
  registerRoutes(app, tunnels, sessions)
  return app
}
