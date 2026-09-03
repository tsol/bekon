#!/usr/bin/env node
/**
 * dev-restart.mjs — Stop all dev services, then start fresh.
 * Usage: node scripts/dev-restart.mjs [--port N] [--no-tunnel]
 *
 * Calls dev-stop then dev-start — clean restart with no port conflicts.
 */
import { execSync } from "node:child_process"
import { resolve, dirname } from "node:path"
import { fileURLToPath } from "node:url"

const __dirname  = dirname(fileURLToPath(import.meta.url))
const projectDir = resolve(__dirname, "..")
const PORTS      = [5174, 18080, 18082]

function log(msg) { console.log(`[dev-restart] ${msg}`) }

// 1) Free ports (kill anything listening)
log("Freeing ports...")
for (const port of PORTS) {
  try {
    execSync(`lsof -ti :${port} 2>/dev/null | xargs -r kill -9 2>/dev/null`, { timeout: 5000 })
  } catch {}
}

// Wait for ports to drain
try { execSync("sleep 2", { timeout: 3000 }) } catch {}

// 2) Clean .dev dir
try { execSync(`rm -rf ${resolve(projectDir, ".dev")}`, { timeout: 2000 }) } catch {}

// 3) Detached start (must exit; used from Docker / project.sh)
log("Starting fresh (detached)...")
execSync(`node ${resolve(projectDir, "scripts/dev-start.mjs")} --bg`, {
  cwd: projectDir,
  stdio: "inherit",
  timeout: 120000,
})