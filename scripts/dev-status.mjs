#!/usr/bin/env node
/**
 * dev-status.mjs — Check dev service health (no tunnel).
 *
 * Output: key=value lines, exit 0 if Vite ok.
 */
import { existsSync, readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const devDir = resolve(__dirname, '..', '.dev')
const statePath = resolve(devDir, 'dev-state.json')

function isAlive(pid) {
  if (!pid) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

function checkHttp(port, timeout = 3000) {
  return new Promise((resolve) => {
    import('node:http').then(({ get }) => {
      const req = get(`http://localhost:${port}/`, { timeout }, (res) => {
        resolve(res.statusCode < 500 ? 'ok' : 'error')
      })
      req.on('error', () => resolve('down'))
      req.on('timeout', () => { req.destroy(); resolve('timeout') })
    }).catch(() => resolve('error'))
  })
}

let state = null
if (existsSync(statePath)) {
  try { state = JSON.parse(readFileSync(statePath, 'utf-8')) } catch {}
}

if (!state) {
  console.log('status=stopped')
  console.log('vite=no')
  console.log('java=no')
  console.log('phone=no')
  process.exit(1)
}

const viteAlive = isAlive(state.vitePid)
const javaAlive = isAlive(state.backendPid)
const phoneAlive = isAlive(state.phonePid)
const viteHealth = viteAlive ? await checkHttp(state.port) : 'down'
const javaHealth = javaAlive ? await checkHttp(state.backendPort) : 'down'
const phoneHealth = phoneAlive ? await checkHttp(state.phonePort || 18082) : 'down'

console.log(`status=${viteHealth === 'ok' ? 'running' : 'degraded'}`)
console.log(`vite=${viteAlive ? 'yes' : 'no'}`)
console.log(`java=${javaAlive ? 'yes' : 'no'}`)
console.log(`phone=${phoneAlive ? 'yes' : 'no'}`)
console.log(`port=${state.port}`)
console.log(`backendPort=${state.backendPort}`)
console.log(`phonePort=${state.phonePort || 18082}`)
console.log(`viteHealth=${viteHealth}`)
console.log(`javaHealth=${javaHealth}`)
console.log(`phoneHealth=${phoneHealth}`)
console.log(`startedAt=${state.startedAt || '-'}`)

process.exit(viteHealth === 'ok' ? 0 : 1)
