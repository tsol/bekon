<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  lines: string[]
  title?: string
  filter?: string
}>()

const filtered = computed(() => {
  const q = (props.filter || '').trim().toLowerCase()
  const lines = q
    ? props.lines.filter(l => l.toLowerCase().includes(q))
    : props.lines
  // Newest entries first — no scroll-to-bottom needed.
  return [...lines].reverse()
})
</script>

<template>
  <div class="log-pane">
    <div class="log-head">
      <span class="log-title">{{ title || 'Logs' }}</span>
      <span class="hint">newest first</span>
    </div>
    <div class="log-body">
      <div v-if="filtered.length === 0" class="empty">No log lines</div>
      <div v-for="(line, i) in filtered" :key="i" class="line">{{ line }}</div>
    </div>
  </div>
</template>

<style scoped>
.log-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: #11111f;
  border: 1px solid #333;
  border-radius: 6px;
}
.log-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 10px;
  border-bottom: 1px solid #333;
  font-size: 12px;
  color: #aaa;
}
.log-title { font-weight: 600; color: #ddd; }
.hint { font-size: 10px; color: #666; text-transform: uppercase; letter-spacing: 0.04em; }
.log-body { flex: 1; overflow: auto; padding: 8px; font-family: ui-monospace, monospace; font-size: 9px; line-height: 1.35; }
.line { color: #bdbdbd; white-space: pre-wrap; word-break: break-all; padding: 1px 0; }
.empty { color: #666; font-style: italic; }
</style>
