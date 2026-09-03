import { onUnmounted, ref, watch, type Ref } from 'vue'
import { PhoneApiError, phoneApi } from '../services/phoneApi'
import { emptyPhoneState, type GestureInput, type PhoneState } from '../types'

const POLL_MS = 1500

export function usePhoneSession(tunnelId: Ref<string>) {
  const state = ref<PhoneState>(emptyPhoneState())
  const localError = ref('')
  const sseOk = ref(false)
  let stop: (() => void) | null = null

  async function pull(id: string) {
    try {
      state.value = await phoneApi.getState(id)
    } catch {
      /* keep last snapshot */
    }
  }

  function bind(id: string) {
    stop?.()
    stop = null
    sseOk.value = false
    if (!id) {
      state.value = emptyPhoneState()
      return
    }

    let es: EventSource | null = null
    let pollTimer: ReturnType<typeof setInterval> | null = null
    let closed = false

    const startPoll = () => {
      if (pollTimer || closed) return
      pollTimer = setInterval(() => { void pull(id) }, POLL_MS)
    }
    const stopPoll = () => {
      if (pollTimer) {
        clearInterval(pollTimer)
        pollTimer = null
      }
    }

    void pull(id)

    es = new EventSource(`/phone-api/tunnels/${encodeURIComponent(id)}/events`)
    es.onopen = () => {
      sseOk.value = true
      stopPoll()
      void pull(id)
    }
    es.onmessage = (ev) => {
      sseOk.value = true
      stopPoll()
      try {
        state.value = JSON.parse(ev.data) as PhoneState
      } catch { /* ignore */ }
    }
    es.onerror = () => {
      sseOk.value = false
      startPoll()
    }

    stop = () => {
      closed = true
      stopPoll()
      es?.close()
      es = null
    }
  }

  watch(tunnelId, (id) => bind(id), { immediate: true })
  onUnmounted(() => stop?.())

  async function run(fn: () => Promise<unknown>) {
    localError.value = ''
    try {
      await fn()
      await pull(id())
    } catch (e) {
      localError.value = e instanceof Error ? e.message : String(e)
      if (e instanceof PhoneApiError && e.status === 409) await pull(id())
    }
  }

  const id = () => tunnelId.value

  return {
    state,
    localError,
    sseOk,
    screenshotSrc: () => {
      const s = state.value
      if (!s.screenshotAt || !s.tunnelId) return null
      return phoneApi.screenshotSrc(s.tunnelId, s.screenshotAt)
    },
    enqueue: (body: GestureInput | GestureInput[]) => run(() => phoneApi.enqueue(id(), body)),
    remove: (gestureId: string) => run(() => phoneApi.remove(id(), gestureId)),
    clear: () => run(() => phoneApi.clear(id())),
    execute: () => run(() => phoneApi.execute(id())),
    abort: () => run(() => phoneApi.abort(id())),
  }
}
