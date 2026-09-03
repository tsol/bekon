<script setup lang="ts">
import { ref, watch } from 'vue'
import type { AdapterInstance } from '../services'
import { coreClient } from '../services'
import { getAdapterForm } from '../adapters/form-registry'

const props = defineProps<{
  tunnelId: string
  adapter: AdapterInstance
  tunnelRunning: boolean
}>()
const emit = defineEmits<{ changed: []; error: [msg: string] }>()

const config = ref<Record<string, string>>({})
const busy = ref(false)

const formEntry = () => getAdapterForm(props.adapter.type)

// Reload the form only when switching adapters. The workbench polls /api/tunnels
// every 2.5s; a deep watch on `adapter` was resetting serverUrl (and other
// fields) back to the last saved value before Save could run.
watch(
  () => props.adapter.id,
  () => {
    config.value = { ...props.adapter.config }
  },
  { immediate: true },
)

function onConfig(c: Record<string, any>) {
  config.value = Object.fromEntries(Object.entries(c).map(([k, v]) => [k, String(v ?? '')]))
}

async function act(fn: () => Promise<unknown>) {
  busy.value = true
  try {
    await fn()
    emit('changed')
  } catch (e) {
    emit('error', e instanceof Error ? e.message : String(e))
  } finally {
    busy.value = false
  }
}

function save() {
  act(() => coreClient.updateAdapter(props.tunnelId, props.adapter.id, config.value))
}

function clearHistory() {
  act(() => coreClient.clearAdapter(props.tunnelId, props.adapter.id))
}

function remove() {
  if (!confirm('Remove adapter?')) return
  act(() => coreClient.removeAdapter(props.tunnelId, props.adapter.id))
}

function startAdapter() {
  act(() => coreClient.startAdapter(props.tunnelId, props.adapter.id))
}

function stopAdapter() {
  act(() => coreClient.stopAdapter(props.tunnelId, props.adapter.id))
}

function remain(atMs?: number | null): string {
  if (atMs == null) return '—'
  const ms = atMs - Date.now()
  const abs = Math.abs(ms)
  const s = Math.round(abs / 1000)
  const label = s < 60 ? `${s}s` : `${Math.round(s / 60)}m`
  return ms <= 0 && atMs > Date.now() - 2000 ? 'now' : (ms < 0 ? `${label} ago` : `in ${label}`)
}
</script>

<template>
  <div class="detail">
    <div class="head">
      <h2>{{ adapter.label || adapter.id }}</h2>
      <span class="type">{{ adapter.type }}</span>
    </div>
    <p class="meta">
      ID: {{ adapter.id }}
      · {{ adapter.enabled === false ? 'disabled' : 'enabled' }}
      · {{ adapter.running ? 'running' : 'stopped' }}
      · {{ adapter.role || 'backup' }}
      · {{ adapter.running ? (adapter.duty || 'active') : 'stopped' }}
    </p>
    <p v-if="adapter.running" class="meta duty">
      next poll {{ remain(adapter.nextPollAtMs) }}
      <template v-if="adapter.idleUntilMs"> · idle {{ remain(adapter.idleUntilMs) }}</template>
      <template v-if="adapter.lastInboundAtMs"> · last inbound {{ remain(adapter.lastInboundAtMs) }}</template>
      <template v-if="adapter.lastPollError"> · error {{ adapter.lastPollError }}</template>
    </p>
    <component
      :is="formEntry()?.component"
      :key="adapter.id"
      :initial-config="config"
      @config="onConfig"
    />
    <div class="row">
      <button
        v-if="adapter.enabled !== false && adapter.running"
        :disabled="busy || !tunnelRunning"
        @click="stopAdapter"
      >Stop</button>
      <button
        v-else
        :disabled="busy || !tunnelRunning"
        @click="startAdapter"
      >Start</button>
      <button :disabled="busy" @click="save">Save</button>
      <button :disabled="busy" @click="clearHistory">Clear history</button>
      <button :disabled="busy" class="danger" @click="remove">Remove</button>
    </div>
    <p v-if="!tunnelRunning" class="hint">Start the tunnel to run this adapter.</p>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; gap: 10px; padding: 12px; }
.head { display: flex; align-items: center; gap: 10px; }
h2 { margin: 0; font-size: 16px; color: #eee; }
.type { font-size: 10px; padding: 2px 8px; border-radius: 999px; background: #333; color: #aaa; text-transform: uppercase; }
.meta { margin: 0; font-size: 11px; color: #777; font-family: monospace; }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
button { border: none; border-radius: 4px; padding: 6px 10px; font-size: 12px; cursor: pointer; background: #2a2a4a; color: #ddd; }
button:disabled { opacity: .5; }
.danger { background: #b71c1c; }
.hint { margin: 0; font-size: 12px; color: #888; }
</style>
