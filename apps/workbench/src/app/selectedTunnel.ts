import { ref } from 'vue'

const logTunnelId = ref<string | null>(null)
const logAdapterId = ref<string | null>(null)

/** Shared tunnel/adapter selection for the global log panel. */
export function useAppLogContext() {
  function setLogTunnel(id: string | null) {
    logTunnelId.value = id
  }

  function setLogAdapter(id: string | null) {
    logAdapterId.value = id
  }

  function clearLogContext() {
    logTunnelId.value = null
    logAdapterId.value = null
  }

  return {
    logTunnelId,
    logAdapterId,
    setLogTunnel,
    setLogAdapter,
    clearLogContext,
  }
}
