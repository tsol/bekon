<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { coreClient } from '../services'
import type { AdapterTypeInfo, TunnelListItem } from '../services'
import { useAppLogContext } from '../../app/selectedTunnel'
import TunnelTree, { type TreeSelection } from './TunnelTree.vue'
import TunnelDetail from './TunnelDetail.vue'
import AdapterDetail from './AdapterDetail.vue'
import CreateTunnelModal from './CreateTunnelModal.vue'
import AddAdapterModal from './AddAdapterModal.vue'

const router = useRouter()
const { logTunnelId, setLogTunnel, setLogAdapter } = useAppLogContext()

const tunnels = ref<TunnelListItem[]>([])
const adapterTypes = ref<AdapterTypeInfo[]>([])
const selection = ref<TreeSelection>({ kind: 'root' })
const statusMsg = ref('')

const showCreate = ref(false)
const showAddAdapter = ref(false)
const addAdapterTunnelId = ref('')

let listTimer: ReturnType<typeof setInterval> | null = null

const selectedAdapter = computed(() => {
  const sel = selection.value
  if (sel.kind !== 'adapter') return null
  const t = tunnels.value.find(x => x.id === sel.tunnelId)
  return t?.adapters.find(a => a.id === sel.adapterId) || null
})

const selectedTunnelRunning = computed(() => {
  const sel = selection.value
  if (sel.kind === 'root') return false
  return !!tunnels.value.find(x => x.id === sel.tunnelId)?.running
})

async function refreshList() {
  try {
    tunnels.value = await coreClient.listTunnels()
    pruneStaleSelection()
  } catch (e) {
    statusMsg.value = e instanceof Error ? e.message : String(e)
  }
}

function pruneStaleSelection() {
  if (logTunnelId.value && !tunnels.value.some(t => t.id === logTunnelId.value)) {
    setLogTunnel(null)
    setLogAdapter(null)
  }
  if (selection.value.kind === 'root') return
  const selectedId = selection.value.tunnelId
  if (!tunnels.value.some(t => t.id === selectedId)) {
    selection.value = { kind: 'root' }
  }
}

function onTunnelDeleted() {
  selection.value = { kind: 'root' }
  setLogTunnel(null)
  setLogAdapter(null)
  refreshList()
}

function syncLogContext(sel: TreeSelection) {
  if (sel.kind === 'root') {
    setLogTunnel(null)
    setLogAdapter(null)
    return
  }
  setLogTunnel(sel.tunnelId)
  setLogAdapter(sel.kind === 'adapter' ? sel.adapterId : null)
}

function onSelect(sel: TreeSelection) {
  selection.value = sel
  syncLogContext(sel)
}

async function createTunnel(label: string, channel: string, secret: string) {
  try {
    const t = await coreClient.createTunnel({
      label,
      channel: channel || undefined,
      secret: secret || undefined,
    })
    showCreate.value = false
    await refreshList()
    onSelect({ kind: 'tunnel', tunnelId: t.id })
    statusMsg.value = `Created ${t.label}`
  } catch (e) {
    statusMsg.value = e instanceof Error ? e.message : String(e)
  }
}

function openAddAdapter(tunnelId: string) {
  addAdapterTunnelId.value = tunnelId
  showAddAdapter.value = true
}

async function addAdapter(type: string, label: string, config: Record<string, string>) {
  try {
    const res = await coreClient.addAdapter(addAdapterTunnelId.value, { type, label, config })
    showAddAdapter.value = false
    await refreshList()
    onSelect({ kind: 'adapter', tunnelId: addAdapterTunnelId.value, adapterId: res.id })
    statusMsg.value = `Added adapter ${res.id}`
  } catch (e) {
    statusMsg.value = e instanceof Error ? e.message : String(e)
  }
}

function openPhone(tunnelId: string) {
  router.push(`/phone/${tunnelId}`)
}

onMounted(async () => {
  adapterTypes.value = await coreClient.listAdapterTypes()
  await refreshList()
  listTimer = setInterval(refreshList, 2500)
  syncLogContext(selection.value)
})

onUnmounted(() => {
  if (listTimer) clearInterval(listTimer)
})

watch(selection, syncLogContext)

watch(logTunnelId, (id) => {
  if (id === null) {
    if (selection.value.kind !== 'root') selection.value = { kind: 'root' }
    return
  }
  const cur = selection.value.kind === 'root' ? null : selection.value.tunnelId
  if (cur === id) return
  selection.value = { kind: 'tunnel', tunnelId: id }
})
</script>

<template>
  <div class="workbench">
    <header class="bar">
      <h1>WLYA Tunnels</h1>
      <span v-if="statusMsg" class="status">{{ statusMsg }}</span>
    </header>

    <div class="panes">
      <aside class="pane tree-pane">
        <TunnelTree
          :tunnels="tunnels"
          :selection="selection"
          @select="onSelect"
          @create-tunnel="showCreate = true"
          @add-adapter="openAddAdapter"
          @open-phone="openPhone"
        />
      </aside>

      <main class="pane detail-pane">
        <div v-if="selection.kind === 'root'" class="placeholder">
          <p>Select a tunnel in the tree, or create one with <strong>+</strong>.</p>
        </div>
        <TunnelDetail
          v-else-if="selection.kind === 'tunnel'"
          :tunnel-id="selection.tunnelId"
          @changed="refreshList"
          @deleted="onTunnelDeleted"
          @error="statusMsg = $event"
        />
        <AdapterDetail
          v-else-if="selectedAdapter"
          :tunnel-id="selection.tunnelId"
          :adapter="selectedAdapter"
          :tunnel-running="selectedTunnelRunning"
          @changed="refreshList"
          @error="statusMsg = $event"
        />
      </main>
    </div>

    <CreateTunnelModal :open="showCreate" @close="showCreate = false" @create="createTunnel" />
    <AddAdapterModal
      :open="showAddAdapter"
      :adapter-types="adapterTypes"
      @close="showAddAdapter = false"
      @add="addAdapter"
    />
  </div>
</template>

<style scoped>
.workbench {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #333;
}
h1 { margin: 0; font-size: 18px; color: #eee; font-weight: 600; }
.status { font-size: 12px; color: #888; }
.panes {
  flex: 1;
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 0;
}
.pane { border-right: 1px solid #333; overflow: auto; min-height: 0; }
.detail-pane { border-right: none; }
.placeholder { padding: 24px; color: #777; }
@media (max-width: 700px) {
  .panes { grid-template-columns: 1fr; grid-template-rows: auto 1fr; }
}
</style>
