import { ref, watch, onMounted, onUnmounted } from 'vue'
import { coreClient, isHttpNotFound } from '../services'
import type { AdapterLogMap, UIMessage } from '../services'
import { useAppLogContext } from '../../app/selectedTunnel'

type LogEvent = { t: number; text: string }

function parseLineTime(line: string, fallback: number): number {
  const m = line.match(/\[(\d{2}):(\d{2}):(\d{2})\.(\d{3})\]/)
  if (!m) return fallback
  const now = new Date()
  const d = new Date(
    now.getFullYear(),
    now.getMonth(),
    now.getDate(),
    Number(m[1]),
    Number(m[2]),
    Number(m[3]),
    Number(m[4]),
  )
  if (d.getTime() - now.getTime() > 12 * 60 * 60 * 1000) {
    d.setDate(d.getDate() - 1)
  }
  return d.getTime()
}

function formatMessage(m: UIMessage): string {
  const arrow = m.direction === 'in' ? '←' : '→'
  return `${arrow} #${m.seq} ${m.plaintext}`
}

function toLines(events: LogEvent[], empty: string): string[] {
  events.sort((a, b) => a.t - b.t)
  return events.length ? events.map(e => e.text) : [empty]
}

function adapterLogKeys(adapterLog: AdapterLogMap, adapterId: string | null, type?: string): string[] {
  const keys = Object.keys(adapterLog)
  if (!adapterId) return keys
  const matched = keys.filter(k =>
    k.includes(adapterId) ||
    k.endsWith(`:${adapterId}`) ||
    (type != null && k.startsWith(`${type}:`))
  )
  return matched
}

export function useTunnelLogs(tunnelId: () => string | null, adapterId: () => string | null) {
  const messageLines = ref<string[]>(['Select a tunnel to view logs.'])
  const adapterLines = ref<string[]>(['Select a tunnel to view logs.'])
  const error = ref('')
  const { clearLogContext } = useAppLogContext()
  let timer: ReturnType<typeof setInterval> | null = null

  function emptyAll(text: string) {
    messageLines.value = [text]
    adapterLines.value = [text]
  }

  function handleMissingTunnel() {
    clearLogContext()
    emptyAll('Tunnel no longer exists — select another tunnel.')
    error.value = ''
  }

  async function refresh() {
    const id = tunnelId()
    if (!id) {
      emptyAll('Select a tunnel to view logs.')
      error.value = ''
      return
    }

    try {
      error.value = ''
      const aid = adapterId()
      const [msgs, adapterLog, adapters] = await Promise.all([
        coreClient.getMessages(id),
        coreClient.getAdapterLog(id),
        aid ? coreClient.listAdapters(id).catch(() => []) : Promise.resolve([]),
      ])

      const adapter = aid ? adapters.find(a => a.id === aid) : undefined

      const msgEvents: LogEvent[] = msgs.slice(-80).map(m => ({
        t: m.timestamp || 0,
        text: formatMessage(m),
      }))
      messageLines.value = toLines(msgEvents, '(no messages yet)')

      const logEvents: LogEvent[] = []
      const logKeys = adapterLogKeys(adapterLog, aid, adapter?.type)
      for (const key of logKeys) {
        for (const line of adapterLog[key] || []) {
          logEvents.push({ t: parseLineTime(line, 0), text: line })
        }
      }
      adapterLines.value = toLines(logEvents, '(no adapter log yet)')
    } catch (e) {
      if (isHttpNotFound(e)) {
        handleMissingTunnel()
        return
      }
      error.value = e instanceof Error ? e.message : String(e)
      emptyAll(`Error: ${error.value}`)
    }
  }

  function startPolling(intervalMs = 1500) {
    stopPolling()
    refresh()
    timer = setInterval(refresh, intervalMs)
  }

  function stopPolling() {
    if (timer) clearInterval(timer)
    timer = null
  }

  watch(() => [tunnelId(), adapterId()], () => refresh())

  onMounted(() => startPolling())
  onUnmounted(() => stopPolling())

  return {
    messageLines,
    adapterLines,
    error,
    refresh,
    startPolling,
    stopPolling,
  }
}
