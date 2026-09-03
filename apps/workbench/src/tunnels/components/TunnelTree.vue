<script setup lang="ts">
import { ref } from 'vue'
import type { AdapterInstance, TunnelListItem } from '../services'

export type TreeSelection =
  | { kind: 'root' }
  | { kind: 'tunnel'; tunnelId: string }
  | { kind: 'adapter'; tunnelId: string; adapterId: string }

const props = defineProps<{
  tunnels: TunnelListItem[]
  selection: TreeSelection
}>()

const emit = defineEmits<{
  select: [sel: TreeSelection]
  createTunnel: []
  addAdapter: [tunnelId: string]
  openPhone: [tunnelId: string]
}>()

const expanded = ref<Record<string, boolean>>({})

function toggle(id: string) {
  expanded.value[id] = !expanded.value[id]
}

function isExpanded(id: string) {
  return expanded.value[id] !== false
}

function select(sel: TreeSelection) {
  emit('select', sel)
}

function fmtRemain(atMs?: number | null): string {
  if (atMs == null) return ''
  const ms = atMs - Date.now()
  if (ms <= 0) return 'now'
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.round(s / 60)
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  const rm = m % 60
  return rm ? `${h}h${rm}m` : `${h}h`
}

function adapterMeta(a: AdapterInstance): string {
  const bits = [a.type]
  if (a.role) bits.push(a.role === 'primary' ? 'P' : 'B')
  if (a.running) bits.push(a.duty || 'active')
  if (a.running && a.nextPollAtMs) bits.push(`poll ${fmtRemain(a.nextPollAtMs)}`)
  if (a.duty === 'active' && a.idleUntilMs) bits.push(`idle ${fmtRemain(a.idleUntilMs)}`)
  return bits.join(' · ')
}

function dotClass(a: AdapterInstance): string {
  if (!a.running) return ''
  if (a.duty === 'sleeping') return 'sleep'
  return 'on'
}
</script>

<template>
  <div class="tree">
    <div class="root-row" :class="{ active: selection.kind === 'root' }" @click="select({ kind: 'root' })">
      <span class="chev">▼</span>
      <span class="label">Tunnels</span>
      <button class="icon" title="New tunnel" @click.stop="emit('createTunnel')">+</button>
    </div>

    <div v-for="t in tunnels" :key="t.id" class="tunnel-block">
      <div
        class="row tunnel"
        :class="{ active: selection.kind === 'tunnel' && selection.tunnelId === t.id }"
        @click="select({ kind: 'tunnel', tunnelId: t.id })"
      >
        <button class="chev-btn" @click.stop="toggle(t.id)">{{ isExpanded(t.id) ? '▼' : '▶' }}</button>
        <span class="dot" :class="{ on: t.running }" />
        <span class="label">{{ t.label }}</span>
        <span v-if="t.autostart" class="auto" title="Starts with desktop service">auto</span>
        <button class="icon" title="Add adapter" @click.stop="emit('addAdapter', t.id)">+</button>
        <button class="icon phone" title="Phone control" @click.stop="emit('openPhone', t.id)">📱</button>
      </div>

      <div v-if="isExpanded(t.id)" class="children">
        <div
          v-for="a in t.adapters"
          :key="a.id"
          class="row adapter"
          :class="{
            active: selection.kind === 'adapter' && selection.adapterId === a.id && selection.tunnelId === t.id,
            off: a.enabled === false,
          }"
          @click="select({ kind: 'adapter', tunnelId: t.id, adapterId: a.id })"
        >
          <span class="branch">├</span>
          <span class="dot" :class="dotClass(a)" />
          <span class="label">{{ a.label || a.type }}</span>
          <span class="type">{{ adapterMeta(a) }}</span>
        </div>
        <div v-if="t.adapters.length === 0" class="empty">No adapters</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tree { font-size: 13px; color: #ccc; }
.row, .root-row { display: flex; align-items: center; gap: 6px; padding: 6px 8px; border-radius: 4px; cursor: pointer; }
.row:hover, .root-row:hover { background: #22223a; }
.active { background: #2a2a4a; outline: 1px solid #455a64; }
.chev, .chev-btn, .branch { width: 16px; color: #666; background: none; border: none; cursor: pointer; padding: 0; }
.label { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #555; }
.dot.on { background: #66bb6a; }
.dot.sleep { background: #ffb74d; }
.auto { font-size: 9px; text-transform: uppercase; color: #90caf9; letter-spacing: .04em; }
.adapter.off { opacity: .55; }
.icon { border: none; background: #333; color: #bbb; border-radius: 4px; width: 22px; height: 22px; cursor: pointer; font-size: 12px; }
.icon.phone { font-size: 10px; }
.type { font-size: 10px; color: #777; text-transform: uppercase; }
.children { margin-left: 18px; }
.empty { padding: 4px 8px 8px 24px; color: #666; font-size: 11px; font-style: italic; }
</style>
