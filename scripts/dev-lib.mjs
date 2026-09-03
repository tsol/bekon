import { execSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'

export const VITE_PORT = Number(process.env.WLYA_VITE_PORT || 5174)
export const JAVA_PORT = Number(process.env.WLYA_PORT || 18080)
export const PHONE_PORT = Number(process.env.PHONE_MANAGER_PORT || 18082)
export const VITE_PORTS = [VITE_PORT, 5175, 5176, 5177, 5178, 5179]

const DOCKER_JDK = '/usr/lib/jvm/java-21-openjdk-amd64'

export function inDocker() {
  if (process.env.WLYA_DEV_ENV === 'docker') return true
  if (process.env.WLYA_DEV_ENV === 'host') return false
  if (existsSync('/.dockerenv')) return true
  try {
    const cgroup = readFileSync('/proc/1/cgroup', 'utf8')
    if (cgroup.includes('docker') || cgroup.includes('containerd') || cgroup.includes('/lxc/')) return true
  } catch { /* not linux / no cgroup */ }
  return false
}

function javaBinOk(home) {
  return !!home && existsSync(join(home, 'bin', 'java'))
}

/** JDK 21+ if possible; works in the Docker image and on a local host. */
export function resolveJavaHome(docker = inDocker()) {
  const candidates = []

  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME)
  if (docker) candidates.push(DOCKER_JDK)

  const gradleJdks = resolve(homedir(), '.gradle', 'jdks')
  if (existsSync(gradleJdks)) {
    for (const name of readdirSync(gradleJdks)) {
      const root = join(gradleJdks, name)
      try {
        if (!statSync(root).isDirectory()) continue
        for (const child of readdirSync(root)) {
          if (child.startsWith('jdk-')) candidates.push(join(root, child))
        }
        candidates.push(root)
      } catch { /* ignore */ }
    }
  }

  candidates.push(
    DOCKER_JDK,
    '/usr/lib/jvm/java-21-openjdk',
    '/usr/lib/jvm/temurin-21-jdk-amd64',
    '/usr/lib/jvm/java-17-openjdk-amd64',
  )

  for (const c of candidates) {
    if (javaBinOk(c)) return c
  }

  try {
    const javaPath = execSync('readlink -f "$(command -v java)"', {
      shell: '/bin/bash',
      encoding: 'utf8',
    }).trim()
    const home = resolve(dirname(javaPath), '..')
    if (javaBinOk(home)) return home
  } catch { /* ignore */ }

  throw new Error(
    'No usable JAVA_HOME found. Install JDK 21 or set JAVA_HOME. ' +
    'Override env with WLYA_DEV_ENV=docker|host if autodetection is wrong.',
  )
}
