export const config = {
  host: process.env.HOST ?? '0.0.0.0',
  port: Number(process.env.PORT ?? 18082),
  wlyaTunnelUrl: (process.env.WLYA_TUNNEL_URL ?? 'http://127.0.0.1:18080').replace(/\/$/, ''),
}
