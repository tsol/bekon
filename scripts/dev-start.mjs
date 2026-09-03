#!/usr/bin/env node
/**
 * dev-start.mjs — Start Vite + wlya-desktop + phone-manager.
 *
 * Default: Docker → fire-and-forget; host → foreground (Ctrl+C).
 * --bg / --fg override. JAVA_HOME is resolved per environment.
 * Override env: WLYA_DEV_ENV=docker|host  JAVA_HOME=...
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync, openSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import http from 'node:http'
import {
  inDocker,
  resolveJavaHome,
  VITE_PORT,
  JAVA_PORT,
  PHONE_PORT,
  VITE_PORTS,
} from './dev-lib.mjs'

const __dirname  = dirname(fileURLToPath(import.meta.url))
const projectDir = resolve(__dirname, '..')
const devDir     = resolve(projectDir, '.dev')
const statePath  = resolve(devDir, 'dev-state.json')
const docker = inDocker()
const foreground = process.argv.includes('--fg') || (!docker && !process.argv.includes('--bg'))

function log(msg) { console.log(`[dev-start] ${msg}`) }

function ensureDir(dir) {
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
}

function httpReady(port, path = '/', timeoutMs = 2000) {
  return new Promise((resolvePromise) => {
    const req = http.get(`http://127.0.0.1:${port}${path}`, { timeout: timeoutMs }, (res) => {
      res.resume()
      resolvePromise(res.statusCode !== undefined && res.statusCode < 500)
    })
    req.on('error', () => resolvePromise(false))
    req.on('timeout', () => { req.destroy(); resolvePromise(false) })
  })
}

async function waitFor(label, fn, attempts, delayMs) {
  for (let i = 1; i <= attempts; i++) {
    if (await fn()) return true
    if (i === 1 || i % 5 === 0) log(`Waiting ${label}… ${i}/${attempts}`)
    await new Promise(r => setTimeout(r, delayMs))
  }
  return false
}

function spawnChild(name, cmd, args, env, { cwd = projectDir, detached, javaHome }) {
  const stdio = detached
    ? ['ignore', openSync(resolve(devDir, `${name}.log`), 'a'), openSync(resolve(devDir, `${name}.log`), 'a')]
    : ['ignore', 'pipe', 'pipe']

  const child = spawn(cmd, args, {
    cwd,
    stdio,
    shell: false,
    detached,
    env: { ...process.env, JAVA_HOME: javaHome, ...(env || {}) },
  })

  if (detached) {
    child.unref()
    child.on('error', (e) => log(`ERROR ${name}: ${e.message}`))
    return child
  }

  const prefix = `[${name}] `
  const write = (stream, d) => {
    stream.write(prefix + d.toString().replace(/\n/g, `\n${prefix}`).replace(new RegExp(`\\n${prefix}$`), '\n'))
  }
  child.stdout.on('data', (d) => write(process.stdout, d))
  child.stderr.on('data', (d) => write(process.stderr, d))
  child.on('error', (e) => log(`ERROR ${name}: ${e.message}`))
  return child
}

function saveState(vite, gradle, phone, port, extra = {}) {
  writeFileSync(statePath, JSON.stringify({
    port,
    backendPort: JAVA_PORT,
    phonePort: PHONE_PORT,
    vitePid: vite?.pid || null,
    backendPid: gradle?.pid || null,
    phonePid: phone?.pid || null,
    startedAt: new Date().toISOString(),
    ...extra,
  }, null, 2))
}

ensureDir(devDir)

async function main() {
  const javaHome = resolveJavaHome(docker)
  log(`env=${docker ? 'docker' : 'host'} JAVA_HOME=${javaHome} fg=${foreground}`)

  const viteBin = resolve(projectDir, 'node_modules/vite/bin/vite.js')
  if (!existsSync(viteBin)) throw new Error('Vite not installed — run pnpm install')

  const tsxBin = resolve(projectDir, 'packages/phone-manager/node_modules/tsx/dist/cli.mjs')
  if (!existsSync(tsxBin)) throw new Error('phone-manager deps missing — run npm install in packages/phone-manager/')

  const spawnOpts = { detached: !foreground, javaHome }
  const vitePort = foreground ? VITE_PORT : String(VITE_PORTS[0])
  const viteArgs = [viteBin, '--config', resolve(projectDir, 'apps/workbench/vite.config.ts'), '--port', String(vitePort), '--host', '0.0.0.0']
  if (foreground) viteArgs.push('--strictPort')

  const vite = spawnChild('vite', process.execPath, viteArgs, {}, spawnOpts)
  const gradle = spawnChild('gradle', resolve(projectDir, 'gradlew'), [':wlya-desktop:run'], {
    WLYA_PORT: String(JAVA_PORT),
  }, spawnOpts)
  const phone = spawnChild('phone-manager', process.execPath, [
    tsxBin,
    foreground ? 'src/server.ts' : 'watch',
    foreground ? undefined : 'src/server.ts',
  ].filter(Boolean), {
    PORT: String(PHONE_PORT),
    HOST: '0.0.0.0',
    WLYA_TUNNEL_URL: `http://127.0.0.1:${JAVA_PORT}`,
  }, { ...spawnOpts, cwd: resolve(projectDir, 'packages/phone-manager') })

  if (!foreground) {
    let actualPort = VITE_PORTS[0]
    outer:
    for (let i = 0; i < 30; i++) {
      await new Promise(r => setTimeout(r, 1000))
      for (const p of VITE_PORTS) {
        if (await httpReady(p)) { actualPort = p; break outer }
      }
      log(`Waiting Vite... ${i + 1}/30`)
    }
    if (!(await httpReady(actualPort))) {
      throw new Error(`Vite failed on ports ${VITE_PORTS.join(', ')}`)
    }
    log(`Vite ready :${actualPort}`)
    log(`Java spawned PID=${gradle.pid} (fire-and-forget)`)
    log(`phone-manager spawned PID=${phone.pid} :${PHONE_PORT}`)
    saveState(vite, gradle, phone, actualPort, { env: docker ? 'docker' : 'host' })
    console.log(`READY port=${actualPort} backendPort=${JAVA_PORT} phonePort=${PHONE_PORT} vitePid=${vite.pid} backendPid=${gradle.pid} phonePid=${phone.pid}`)
    process.exit(0)
    return
  }

  saveState(vite, gradle, phone, VITE_PORT, { mode: 'host', env: docker ? 'docker' : 'host' })

  let shuttingDown = false
  const shutdown = (code = 0) => {
    if (shuttingDown) return
    shuttingDown = true
    log('Stopping…')
    for (const c of [vite, gradle, phone]) {
      try { c.kill('SIGTERM') } catch { /* ignore */ }
    }
    setTimeout(() => {
      for (const c of [vite, gradle, phone]) {
        try { c.kill('SIGKILL') } catch { /* ignore */ }
      }
      process.exit(code)
    }, 2500)
  }

  process.on('SIGINT', () => shutdown(0))
  process.on('SIGTERM', () => shutdown(0))
  for (const [name, child] of [['Vite', vite], ['Java/Gradle', gradle], ['phone-manager', phone]]) {
    child.on('exit', (c) => {
      if (!shuttingDown) {
        log(`${name} exited (${c})`)
        shutdown(c || 1)
      }
    })
  }

  if (!await waitFor('Vite', () => httpReady(VITE_PORT), 45, 500)) {
    log('Vite failed to become ready')
    shutdown(1)
    return
  }
  log(`Vite ready http://localhost:${VITE_PORT}/`)

  if (!await waitFor(
    'API',
    async () => (await httpReady(JAVA_PORT, '/api/tunnels')) || (await httpReady(JAVA_PORT, '/')),
    120,
    1000,
  )) {
    log('Java API failed to become ready on :' + JAVA_PORT)
    shutdown(1)
    return
  }
  log(`API ready  http://localhost:${JAVA_PORT}/api/tunnels`)

  if (!await waitFor('phone-manager', () => httpReady(PHONE_PORT, '/health'), 30, 500)) {
    log('phone-manager failed to become ready on :' + PHONE_PORT)
    shutdown(1)
    return
  }
  log(`phone     http://localhost:${PHONE_PORT}/health`)
  log(`UI        http://localhost:${VITE_PORT}/#/tunnels`)
  log('Foreground — Ctrl+C to stop')
}

main().catch((e) => {
  log(`FAILED: ${e.message}`)
  process.exit(1)
})
