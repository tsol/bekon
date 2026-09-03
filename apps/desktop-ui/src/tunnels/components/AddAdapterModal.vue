<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import type { AdapterTypeInfo } from '../services'
import { getAdapterForm } from '../adapters/form-registry'

const props = defineProps<{
  open: boolean
  adapterTypes: AdapterTypeInfo[]
}>()
const emit = defineEmits<{ close: []; add: [type: string, label: string, config: Record<string, string>] }>()

const selectedType = ref('mock')
const config = ref<Record<string, string>>({})
const error = ref('')

const formEntry = computed(() => getAdapterForm(selectedType.value))

watch(() => props.open, (v) => {
  if (!v) return
  selectedType.value = props.adapterTypes[0]?.type || 'mock'
  config.value = {}
  error.value = ''
})

watch(selectedType, (t) => {
  const entry = getAdapterForm(t)
  const defaults = props.adapterTypes.find(a => a.type === t)?.defaultConfig || entry?.defaultConfig || {}
  config.value = Object.fromEntries(Object.entries(defaults).map(([k, v]) => [k, String(v)]))
})

function onConfig(c: Record<string, any>) {
  config.value = Object.fromEntries(Object.entries(c).map(([k, v]) => [k, String(v ?? '')]))
}

function instanceLabel(): string {
  if (config.value.label?.trim()) return config.value.label.trim()
  if (config.value.login?.trim()) return config.value.login.trim()
  return selectedType.value
}

function submit() {
  if (!selectedType.value) {
    error.value = 'Pick adapter type'
    return
  }
  error.value = ''
  emit('add', selectedType.value, instanceLabel(), config.value)
}
</script>

<template>
  <div v-if="open" class="overlay" @click.self="emit('close')">
    <div class="modal">
      <h3>Add adapter</h3>
      <label>Type
        <select v-model="selectedType">
          <option v-for="t in adapterTypes" :key="t.type" :value="t.type">{{ t.label }}</option>
        </select>
      </label>
      <component
        :is="formEntry?.component"
        v-if="formEntry"
        :key="selectedType"
        :initial-config="config"
        @config="onConfig"
      />
      <p v-if="error" class="err">{{ error }}</p>
      <div class="actions">
        <button class="ghost" @click="emit('close')">Cancel</button>
        <button class="primary" @click="submit">Add</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; background: rgba(0,0,0,.65); display: flex; align-items: center; justify-content: center; z-index: 50; }
.modal { width: 420px; max-height: 90vh; overflow: auto; background: #171728; border: 1px solid #444; border-radius: 8px; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
h3 { margin: 0; color: #eee; font-size: 15px; }
label { display: flex; flex-direction: column; gap: 4px; font-size: 11px; color: #888; text-transform: uppercase; }
.modal :deep(input),
.modal :deep(select) {
  padding: 8px;
  background: #0f0f1a;
  border: 1px solid #555;
  border-radius: 4px;
  color: #ddd;
  font-size: 13px;
  width: 100%;
}
.modal :deep(label) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  color: #888;
  text-transform: uppercase;
}
input, select { padding: 8px; background: #0f0f1a; border: 1px solid #555; border-radius: 4px; color: #ddd; }
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.ghost, .primary { border: none; border-radius: 4px; padding: 6px 12px; cursor: pointer; font-size: 12px; }
.ghost { background: #333; color: #bbb; }
.primary { background: #1565c0; color: #fff; }
.err { color: #ef5350; font-size: 12px; margin: 0; }
</style>
