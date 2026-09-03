<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { phoneApi } from '../services/phoneApi'
import type { TunnelListItem } from '../types'

defineProps<{ modelValue: string }>()
const emit = defineEmits<{ 'update:modelValue': [id: string] }>()

const tunnels = ref<TunnelListItem[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    tunnels.value = await phoneApi.listTunnels()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})

function select(id: string) {
  emit('update:modelValue', id)
}
</script>

<template>
  <div class="menu" role="listbox">
    <p v-if="loading" class="hint">Loading tunnels…</p>
    <p v-else-if="!tunnels.length" class="hint">No tunnels</p>
    <button
      v-for="t in tunnels"
      :key="t.id"
      type="button"
      class="item"
      role="option"
      :aria-selected="t.id === modelValue"
      :class="{ selected: t.id === modelValue }"
      @click="select(t.id)"
    >
      <span class="label">{{ t.label }}</span>
      <span class="state" :class="{ running: t.running }">{{ t.running ? 'running' : 'stopped' }}</span>
    </button>
  </div>
</template>

<style scoped>
.menu {
  min-width: 260px;
  max-height: 320px;
  overflow: auto;
  padding: 4px;
  background: #16162a;
  border: 1px solid #444;
  border-radius: 6px;
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.45);
}

.hint {
  margin: 0;
  padding: 10px 12px;
  font-size: 12px;
  color: #888;
}

.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 8px 10px;
  background: transparent;
  border: none;
  border-radius: 4px;
  color: #ddd;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.item:hover {
  background: #2a2a4a;
}

.item.selected {
  background: #24344f;
  color: #fff;
}

.label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.state {
  flex-shrink: 0;
  font-size: 11px;
  color: #888;
}

.state.running {
  color: #81c784;
}
</style>
