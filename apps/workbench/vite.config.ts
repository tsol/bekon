import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

const workbenchRoot = fileURLToPath(new URL('.', import.meta.url))
const repoRoot = path.resolve(workbenchRoot, '../..')
const adaptersRoot = path.resolve(repoRoot, 'packages/wlya-adapters')

export default defineConfig({
  root: workbenchRoot,
  plugins: [
    vue(),
  ],
  resolve: {
    alias: {
      '@wlya/adapters': adaptersRoot,
    },
  },
  server: {
    port: 5174,
    host: true,
    cors: true,
    allowedHosts: ['.trycloudflare.com', '.loca.lt', 'localhost', '127.0.0.1'],
    hmr: true,
    fs: { strict: false },
    watch: {
      usePolling: true,
      interval: 500,
    },
    proxy: {
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/phone-api': {
        target: 'http://localhost:18082',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/phone-api/, ''),
        timeout: 0,
        proxyTimeout: 0,
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes, req) => {
            if (String(req.url ?? '').includes('/events')) {
              proxyRes.headers['cache-control'] = 'no-cache, no-transform'
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        },
      },
    },
  },
})
