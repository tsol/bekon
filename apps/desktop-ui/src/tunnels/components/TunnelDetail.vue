<script setup lang="ts">
import { ref, watch } from 'vue'
import type { AdapterInstance, TunnelDetail } from '../services'
import { coreClient } from '../services'

const props = defineProps<{ tunnelId: string }>()
const emit = defineEmits<{ changed: []; deleted: []; error: [msg: string] }>()

const detail = ref<TunnelDetail | null>(null)
const adapters = ref<AdapterInstance[]>([])
const selectedAdapterIds = ref<string[]>([])
const label = ref('')
const channel = ref('')
const secret = ref('')
const autostart = ref(false)
const sendText = ref('')
const busy = ref(false)

async function load() {
  detail.value = await coreClient.getTunnel(props.tunnelId)
  label.value = detail.value.label
  channel.value = detail.value.channel
  secret.value = detail.value.secret
  autostart.value = !!detail.value.autostart
  adapters.value = await coreClient.listAdapters(props.tunnelId)
  const known = new Set(adapters.value.map(a => a.id))
  const prev = selectedAdapterIds.value.filter(id => known.has(id))
  selectedAdapterIds.value = prev.length > 0 ? prev : adapters.value.map(a => a.id)
}

function toggleAdapter(id: string, checked: boolean) {
  if (checked) {
    if (!selectedAdapterIds.value.includes(id)) selectedAdapterIds.value = [...selectedAdapterIds.value, id]
  } else {
    selectedAdapterIds.value = selectedAdapterIds.value.filter(x => x !== id)
  }
}

function advertiseAdapters() {
  if (selectedAdapterIds.value.length === 0) {
    emit('error', 'Select at least one adapter to advertise')
    return
  }
  act(async () => {
    await coreClient.ensureRunning(props.tunnelId)
    await coreClient.advertiseAdapters(props.tunnelId, selectedAdapterIds.value)
  })
}

watch(() => props.tunnelId, load, { immediate: true })

async function act(fn: () => Promise<unknown>) {
  busy.value = true
  try {
    await fn()
    await load()
    emit('changed')
  } catch (e) {
    emit('error', e instanceof Error ? e.message : String(e))
  } finally {
    busy.value = false
  }
}

function saveConfig() {
  act(() => coreClient.updateConfig(props.tunnelId, {
    label: label.value,
    channel: channel.value,
    secret: secret.value,
    autostart: autostart.value,
  }))
}

function setAutostart(on: boolean) {
  autostart.value = on
  act(() => coreClient.updateConfig(props.tunnelId, { autostart: on }))
}

function start() { act(() => coreClient.start(props.tunnelId)) }
function stop() { act(() => coreClient.stop(props.tunnelId)) }
function del() {
  if (!confirm('Delete tunnel?')) return
  busy.value = true
  coreClient.deleteTunnel(props.tunnelId)
    .then(() => emit('deleted'))
    .catch(e => emit('error', e instanceof Error ? e.message : String(e)))
    .finally(() => { busy.value = false })
}

async function sendMessage() {
  if (!sendText.value.trim()) return
  act(async () => {
    await coreClient.ensureRunning(props.tunnelId)
    await coreClient.send(props.tunnelId, { plaintext: sendText.value.trim() })
    sendText.value = ''
  })
}
</script>

<template>
  <div v-if="detail" class="detail">
    <div class="head">
      <h2>{{ detail.label }}</h2>
      <span class="badge" :class="{ on: detail.running }">{{ detail.running ? 'running' : 'stopped' }}</span>
    </div>
    <p class="meta">ID: {{ detail.id }}</p>
    <p class="meta">Channel: {{ detail.channel || '—' }}</p>
    <p class="meta warn" v-if="!detail.running">Tunnel stopped — Start before send/email will work.</p>

    <div class="field"><label>Label</label><input v-model="label" /></div>
    <div class="field"><label>Channel</label><input v-model="channel" spellcheck="false" /></div>
    <div class="field"><label>Secret</label><input v-model="secret" type="password" placeholder="empty = same as channel" /></div>
    <label class="checkbox-row autostart">
      <input
        type="checkbox"
        :checked="autostart"
        :disabled="busy"
        @change="setAutostart(($event.target as HTMLInputElement).checked)"
      />
      <span>Autostart with desktop service</span>
    </label>
    <div class="row">
      <button :disabled="busy" @click="saveConfig">Save config</button>
      <button :disabled="busy || detail.running" class="ok" @click="start">Start</button>
      <button :disabled="busy || !detail.running" class="warn" @click="stop">Stop</button>
      <button :disabled="busy" class="danger" @click="del">Delete</button>
    </div>

    <div class="send">
      <input v-model="sendText" placeholder="Send plaintext message…" @keyup.enter="sendMessage" />
      <button :disabled="busy" @click="sendMessage">Send</button>
    </div>

    <div class="advertise">
      <h3>Advertise adapters</h3>
      <p class="hint">Peers that accept advertisements will add or update these adapters by id.</p>
      <label v-for="a in adapters" :key="a.id" class="checkbox-row">
        <input
          type="checkbox"
          :checked="selectedAdapterIds.includes(a.id)"
          @change="toggleAdapter(a.id, ($event.target as HTMLInputElement).checked)"
        />
        <span>{{ a.label || a.id }} <em>{{ a.type }}</em></span>
      </label>
      <p v-if="adapters.length === 0" class="hint">No adapters on this tunnel.</p>
      <button :disabled="busy || adapters.length === 0" @click="advertiseAdapters">Advertise adapters</button>
    </div>
  </div>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; gap: 10px; padding: 12px; }
.head { display: flex; align-items: center; gap: 10px; }
h2 { margin: 0; font-size: 16px; color: #eee; }
.badge { font-size: 10px; padding: 2px 8px; border-radius: 999px; background: #333; color: #aaa; text-transform: uppercase; }
.badge.on { background: #1b5e20; color: #a5d6a7; }
.meta { margin: 0; font-size: 11px; color: #777; font-family: monospace; }
.meta.warn { color: #ffb74d; }
.field label { display: block; font-size: 10px; color: #888; text-transform: uppercase; margin-bottom: 4px; }
.field input { width: 100%; padding: 6px 8px; background: #0f0f1a; border: 1px solid #555; border-radius: 4px; color: #ddd; }
.row, .send { display: flex; gap: 8px; flex-wrap: wrap; }
button { border: none; border-radius: 4px; padding: 6px 10px; font-size: 12px; cursor: pointer; background: #2a2a4a; color: #ddd; }
button:disabled { opacity: .5; cursor: default; }
.ok { background: #1b5e20; }
.warn { background: #e65100; }
.danger { background: #b71c1c; }
.send input { flex: 1; min-width: 180px; padding: 6px 8px; background: #0f0f1a; border: 1px solid #555; border-radius: 4px; color: #ddd; }
.advertise { display: flex; flex-direction: column; gap: 8px; padding-top: 8px; border-top: 1px solid #333; }
.advertise h3 { margin: 0; font-size: 13px; color: #ccc; }
.hint { margin: 0; font-size: 11px; color: #777; }
.checkbox-row { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #ddd; }
.checkbox-row em { font-style: normal; color: #888; font-size: 11px; margin-left: 6px; }
</style>
