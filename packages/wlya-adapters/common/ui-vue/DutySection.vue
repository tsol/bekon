<script setup lang="ts">
import { reactive, watch } from 'vue'

const props = defineProps<{
  initialConfig?: Record<string, any>
  defaultRole?: 'primary' | 'backup'
  defaultPollIntervalMs?: number
}>()

const emit = defineEmits<{
  config: [config: Record<string, any>]
}>()

function num(v: unknown, fallback: number): number {
  const n = typeof v === 'number' ? v : Number(v)
  return Number.isFinite(n) ? n : fallback
}

const duty = reactive({
  role: (props.initialConfig?.role === 'primary' || props.initialConfig?.role === 'backup')
    ? props.initialConfig.role
    : (props.defaultRole || 'backup'),
  pollIntervalMs: num(props.initialConfig?.pollIntervalMs, props.defaultPollIntervalMs ?? 2000),
  sleepPollMs: num(props.initialConfig?.sleepPollMs, 3_600_000),
  sleepJitterMs: num(props.initialConfig?.sleepJitterMs, 900_000),
  idleMs: num(props.initialConfig?.idleMs, 600_000),
})

watch(duty, () => emit('config', { ...duty }), { deep: true, immediate: true })
</script>

<template>
  <fieldset class="duty">
    <legend>Polling / role</legend>
    <label>
      <span>Role</span>
      <select v-model="duty.role">
        <option value="primary">Primary (always active)</option>
        <option value="backup">Backup (sleeps until inbound)</option>
      </select>
    </label>
    <label>
      <span>Active poll interval (ms)</span>
      <input v-model.number="duty.pollIntervalMs" type="number" min="250" />
    </label>
    <label>
      <span>Sleep poll interval (ms)</span>
      <input v-model.number="duty.sleepPollMs" type="number" min="1000" />
    </label>
    <label>
      <span>Sleep jitter (ms)</span>
      <input v-model.number="duty.sleepJitterMs" type="number" min="0" />
    </label>
    <label>
      <span>Backup idle before sleep (ms)</span>
      <input v-model.number="duty.idleMs" type="number" min="1000" />
    </label>
  </fieldset>
</template>

<style scoped>
.duty {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
  padding: 10px;
  border: 1px solid #444;
  border-radius: 6px;
}
legend { font-size: 11px; color: #aaa; text-transform: uppercase; padding: 0 6px; }
label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #888; }
input, select { padding: 6px 8px; background: #0d0d1a; border: 1px solid #555; border-radius: 3px; color: #ccc; font-size: 12px; }
</style>
