<script setup lang="ts">
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'
import { coreClient } from '../services'
import type { AdapterInstance } from '../services'
import { useTunnelLogs } from '../composables/useTunnelLogs'
import LogPane from './LogPane.vue'

const props = defineProps<{
  tunnelId?: string | null
  /** Filters the Adapter pane. Empty = all adapters. */
  adapterId?: string | null
}>()

const emit = defineEmits<{
  'update:adapterId': [id: string | null]
}>()

const tunnels = ref<Array<{ id: string; label: string }>>([])
const adapters = ref<AdapterInstance[]>([])
const localAdapterId = ref(props.adapterId ?? '')
const logFilter = ref('')

const effectiveTunnelId = computed(() => props.tunnelId || null)
const tunnelLabel = computed(() => {
  const id = effectiveTunnelId.value
  if (!id) return 'No tunnel selected'
  return tunnels.value.find(t => t.id === id)?.label || id
})

watch(() => props.adapterId, (id) => {
  localAdapterId.value = id ?? ''
}, { immediate: true })

const effectiveAdapterId = computed(() => localAdapterId.value || null)

const { messageLines, adapterLines } = useTunnelLogs(
  () => effectiveTunnelId.value,
  () => effectiveAdapterId.value,
)

async function loadTunnels() {
  try {
    const list = await coreClient.listTunnels()
    tunnels.value = list.map(t => ({ id: t.id, label: t.label }))
  } catch { /* ignore */ }
}

async function loadAdapters() {
  const id = effectiveTunnelId.value
  if (!id) {
    adapters.value = []
    return
  }
  try {
    adapters.value = await coreClient.listAdapters(id)
  } catch {
    adapters.value = []
  }
}

let tunnelListTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  loadTunnels()
  tunnelListTimer = setInterval(loadTunnels, 3000)
})

onUnmounted(() => {
  if (tunnelListTimer) clearInterval(tunnelListTimer)
})

watch(effectiveTunnelId, () => {
  loadAdapters()
}, { immediate: true })

function onAdapterChange(ev: Event) {
  const v = (ev.target as HTMLSelectElement).value
  localAdapterId.value = v
  emit('update:adapterId', v || null)
}
</script>

<template>
  <div class="tunnel-log-panel">
    <div class="toolbar">
      <span class="panel-title">Live logs</span>
      <div class="meta">{{ tunnelLabel }}</div>
      <div class="controls">
        <select
          class="ctl"
          :value="localAdapterId"
          :disabled="!effectiveTunnelId"
          @change="onAdapterChange"
        >
          <option value="">All adapters</option>
          <option v-for="a in adapters" :key="a.id" :value="a.id">
            {{ a.label || a.type }} ({{ a.type }})
          </option>
        </select>
        <input v-model="logFilter" class="ctl filter" placeholder="Filter…" />
      </div>
    </div>
    <LogPane :lines="messageLines" :filter="logFilter" title="Messages" />
    <LogPane :lines="adapterLines" :filter="logFilter" title="Adapter" />
  </div>
</template>

<style scoped>
.tunnel-log-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  height: 100%;
}
.toolbar {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex: 0 0 auto;
}
.panel-title {
  font-size: 12px;
  font-weight: 600;
  color: #ddd;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}
.meta {
  font-size: 12px;
  color: #9aa;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.controls {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ctl {
  flex: 1;
  min-width: 120px;
  padding: 6px 8px;
  background: #0f0f1a;
  border: 1px solid #555;
  border-radius: 4px;
  color: #ddd;
  font-size: 12px;
}
.ctl.filter {
  flex: 1.2;
  min-width: 80px;
}
.ctl:disabled {
  opacity: 0.5;
}
.tunnel-log-panel :deep(.log-pane) {
  flex: 1 1 0;
  min-height: 80px;
}
</style>
