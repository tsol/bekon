<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ScreenSnapshot, SnapshotItem } from '../types'

export type ReplyTab = 'snapshot' | 'a11y' | 'file' | 'logs'

const props = defineProps<{
  tab: ReplyTab
  text?: string
  snapshot?: ScreenSnapshot | null
  file?: { ok: boolean; path?: string; uri?: string; name?: string; size?: number; mime?: string; error?: string } | null
  logs?: { adapter: string[]; messages: string[]; core: string[]; apkUpdate: string[] } | null
}>()

const emit = defineEmits<{
  'update:tab': [tab: ReplyTab]
  tapItem: [item: SnapshotItem]
  highlight: [items: SnapshotItem[]]
}>()

const selected = ref<Set<string>>(new Set())

const items = computed(() => props.snapshot?.items ?? [])
const itemCount = computed(() => items.value.length)
const selectedCount = computed(() => {
  let n = 0
  for (const it of items.value) if (selected.value.has(it.ref)) n++
  return n
})
const allOn = computed(() => itemCount.value > 0 && selectedCount.value === itemCount.value)
const someOn = computed(() => selectedCount.value > 0 && !allOn.value)

function snapKey(s: ScreenSnapshot | null | undefined) {
  if (!s?.items.length) return ''
  const a = s.items[0]
  const b = s.items[s.items.length - 1]
  return `${s.items.length}:${a.ref}:${a.x}:${a.y}:${a.name}:${b.ref}:${b.x}:${b.y}:${b.name}:${s.ocrCount ?? 0}`
}

watch(
  () => snapKey(props.snapshot),
  () => {
    selected.value = new Set()
  },
)

watch(
  [selected, items],
  () => {
    emit('highlight', items.value.filter(i => selected.value.has(i.ref)))
  },
  { immediate: true },
)

function select(tab: ReplyTab) {
  emit('update:tab', tab)
}

function toggleAll() {
  if (allOn.value) selected.value = new Set()
  else selected.value = new Set(items.value.map(i => i.ref))
}

function toggleOne(refId: string, ev: Event) {
  const on = (ev.target as HTMLInputElement).checked
  const next = new Set(selected.value)
  if (on) next.add(refId)
  else next.delete(refId)
  selected.value = next
}
</script>

<template>
  <div class="reply-panel">
    <div class="head">
      <h3>Response</h3>
      <div class="tabs" role="tablist">
      <button
        type="button"
        class="tab"
        role="tab"
        :class="{ on: tab === 'snapshot' }"
        :aria-selected="tab === 'snapshot'"
        @click="select('snapshot')"
      >
        Snapshot
      </button>
      <button
        type="button"
        class="tab"
        role="tab"
        :class="{ on: tab === 'a11y' }"
        :aria-selected="tab === 'a11y'"
        @click="select('a11y')"
      >
        A11y
      </button>
      <button
        type="button"
        class="tab"
        role="tab"
        :class="{ on: tab === 'file' }"
        :aria-selected="tab === 'file'"
        @click="select('file')"
      >
        File
      </button>
      <button
        type="button"
        class="tab"
        role="tab"
        :class="{ on: tab === 'logs' }"
        :aria-selected="tab === 'logs'"
        @click="select('logs')"
      >
        Logs
      </button>
      </div>
    </div>

    <template v-if="tab === 'snapshot'">
      <p class="meta">
        ocr {{ snapshot?.ocrCount ?? 0 }}
        <span v-if="snapshot?.ocrError" class="err">{{ snapshot.ocrError }}</span>
      </p>
      <label v-if="itemCount" class="all">
        <input
          type="checkbox"
          :checked="allOn"
          :indeterminate="someOn"
          @click.prevent="toggleAll"
        />
        Select All / None
      </label>
      <ul class="refs">
        <li v-if="!itemCount" class="empty">No snapshot yet</li>
        <li
          v-for="it in items"
          :key="it.ref"
          class="ref"
          :class="{ hl: selected.has(it.ref) }"
          :title="`tap (${it.x}, ${it.y})`"
        >
          <input
            type="checkbox"
            :checked="selected.has(it.ref)"
            :aria-label="`highlight ${it.ref}`"
            @click.stop
            @change="toggleOne(it.ref, $event)"
          />
          <button type="button" class="hit" @click="emit('tapItem', it)">
            <code class="id">[{{ it.ref }}]</code>
            <span class="src">{{ it.source }}</span>
            <span class="name">{{ it.name }}</span>
          </button>
        </li>
      </ul>
    </template>

    <textarea
      v-else-if="tab === 'a11y'"
      class="tree"
      readonly
      :value="text || ''"
      :placeholder="text ? '' : 'No accessibility tree on the last screenshot'"
    />

    <div v-else-if="tab === 'file'" class="file-body">
      <p v-if="file?.ok && file.path" class="file-line" :title="file.path">
        <span class="file-name">{{ file.name || 'file' }}</span>
        <span class="arrow">→</span>
        <span class="file-path">{{ file.path }}</span>
      </p>
      <p v-else-if="file?.error" class="err">{{ file.error }}</p>
      <p v-else class="empty">No file uploaded yet</p>
    </div>

    <div v-else class="logs-body">
      <template v-if="logs">
        <p class="logs-h">adapter</p>
        <pre class="logs-pre">{{ (logs.adapter || []).join('\n') || '(empty)' }}</pre>
        <p class="logs-h">messages</p>
        <pre class="logs-pre">{{ (logs.messages || []).join('\n') || '(empty)' }}</pre>
        <p class="logs-h">core</p>
        <pre class="logs-pre">{{ (logs.core || []).join('\n') || '(empty)' }}</pre>
        <p class="logs-h">apkUpdate</p>
        <pre class="logs-pre">{{ (logs.apkUpdate || []).join('\n') || '(empty)' }}</pre>
      </template>
      <p v-else class="empty">No logs fetched yet. Enable Share logs on request on the phone.</p>
    </div>
  </div>
</template>

<style scoped>
.reply-panel {
  background: #11111f;
  border: 1px solid #333;
  border-radius: 6px;
  padding: 12px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 160px;
}
.head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
h3 { margin: 0; font-size: 14px; color: #eee; }
.tabs {
  display: flex;
  gap: 4px;
}
.tab {
  padding: 5px 10px;
  background: transparent;
  border: 1px solid #444;
  border-radius: 4px;
  color: #999;
  font-size: 12px;
  cursor: pointer;
}
.tab.on {
  background: #24344f;
  border-color: #64b5f6;
  color: #fff;
}
.meta { margin: 0; font-size: 11px; color: #888; }
.all {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #bbb;
  cursor: pointer;
  user-select: none;
}
.all input,
.ref > input {
  width: 14px;
  height: 14px;
  margin: 0;
  flex-shrink: 0;
  accent-color: #64b5f6;
  cursor: pointer;
}
.err { color: #ef9a9a; margin: 0; font-size: 12px; }
.tree {
  width: 100%;
  flex: 1;
  min-height: 180px;
  box-sizing: border-box;
  resize: vertical;
  border: 1px solid #444;
  border-radius: 4px;
  padding: 8px;
  background: #1a1a2a;
  color: #c5e1a5;
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  line-height: 1.35;
}
.refs {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow: auto;
  flex: 1;
  min-height: 180px;
  border: 1px solid #444;
  border-radius: 4px;
  background: #1a1a2a;
}
.ref {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  font-size: 12px;
  color: #ddd;
  border-bottom: 1px solid #2a2a3a;
}
.ref:hover { background: #252540; }
.ref.hl { background: #1a3048; }
.hit {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  flex: 1;
  margin: 0;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.id { color: #90caf9; font-size: 11px; }
.src { color: #888; font-size: 10px; text-transform: uppercase; width: 32px; flex-shrink: 0; }
.name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { padding: 12px; color: #777; font-size: 12px; margin: 0; }
.file-body {
  flex: 1;
  min-height: 180px;
  border: 1px solid #444;
  border-radius: 4px;
  background: #1a1a2a;
  padding: 12px;
  overflow: auto;
}
.file-line {
  margin: 0;
  font-size: 12px;
  color: #ddd;
  word-break: break-all;
  line-height: 1.45;
}
.file-name { color: #90caf9; }
.arrow { color: #888; margin: 0 6px; }
.file-path { color: #c5e1a5; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 11px; }
.logs-body {
  flex: 1;
  min-height: 180px;
  border: 1px solid #444;
  border-radius: 4px;
  background: #1a1a2a;
  padding: 8px 12px;
  overflow: auto;
}
.logs-h {
  margin: 8px 0 2px;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: .04em;
  color: #90caf9;
}
.logs-h:first-child { margin-top: 0; }
.logs-pre {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 9px;
  line-height: 1.35;
  color: #ccc;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
