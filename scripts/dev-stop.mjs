#!/usr/bin/env node
/**
 * dev-stop.mjs — Kill dev processes by PID from .dev/dev-state.json.
 *
 * Usage: node scripts/dev-stop.mjs [--keep-state]
 */
import { existsSync, readFileSync, rmSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execSync } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'

const __dirname  = dirname(fileURLToPath(import.meta.url))
const devDir     = resolve(__dirname, '..', '.dev')
const statePath  = resolve(devDir, 'dev-state.json')
const keepState  = process.argv.includes('--keep-state')

function log(msg) { console.log(`[dev-stop] ${msg}`) }

function killPid(pid, sig) {
  if (!pid) return
  try { process.kill(pid, sig || 'SIGKILL') } catch {}
}

function isAlive(pid) {
  if (!pid) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

async function main() {
  if (!existsSync(statePath)) {
    log('No state file — nothing to stop')
    return
  }

  let state
  try {
    state = JSON.parse(readFileSync(statePath, 'utf-8'))
  } catch {
    log('State file corrupted — cleaning')
    if (!keepState) rmSync(statePath)
    return
  }

  log(`Killing vite=${state.vitePid} java=${state.backendPid} phone=${state.phonePid}`)

  // 1. Graceful: SIGTERM
  killPid(state.vitePid, 'SIGTERM')
  killPid(state.backendPid, 'SIGTERM')
  killPid(state.phonePid, 'SIGTERM')

  // 2. Wait 2s, then SIGKILL if still alive
  log('Waiting 2s for graceful shutdown...')
  await sleep(2000)

  if (isAlive(state.vitePid)) {
    log(`Vite still alive — SIGKILL`)
    killPid(state.vitePid, 'SIGKILL')
  }
  if (isAlive(state.backendPid)) {
    log(`Java still alive — SIGKILL`)
    killPid(state.backendPid, 'SIGKILL')
  }
  if (isAlive(state.phonePid)) {
    log(`phone-manager still alive — SIGKILL`)
    killPid(state.phonePid, 'SIGKILL')
  }

  // 3. Final force-kill after 2 more seconds (for stubborn processes)
  await sleep(2000)
  if (isAlive(state.vitePid)) {
    log(`Vite stubborn — final SIGKILL`)
    killPid(state.vitePid, 'SIGKILL')
  }
  if (isAlive(state.backendPid)) {
    log(`Java stubborn — final SIGKILL`)
    killPid(state.backendPid, 'SIGKILL')
  }
  if (isAlive(state.phonePid)) {
    log(`phone-manager stubborn — final SIGKILL`)
    killPid(state.phonePid, 'SIGKILL')
  }

  if (!keepState && existsSync(statePath)) rmSync(statePath)
  log('Done.')
}

main().then(() => process.exit(0)).catch(e => {
  log(`ERROR: ${e.message}`)
  process.exit(1)
})