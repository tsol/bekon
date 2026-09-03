import { buildApp } from './app.js'
import { config } from './config.js'

const app = await buildApp(config.wlyaTunnelUrl)

try {
  await app.listen({ port: config.port, host: config.host })
  app.log.info(`phone-control-api listening on ${config.host}:${config.port} tunnel=${config.wlyaTunnelUrl}`)
} catch (err) {
  app.log.error(err)
  process.exit(1)
}
