<script setup lang="ts">
import { ref, watch } from 'vue'
import DutySection from '../../common/ui-vue/DutySection.vue'

const props = defineProps<{
  initialConfig?: Record<string, any>
}>()

const emit = defineEmits<{
  config: [config: Record<string, any>]
}>()

const cfg = ref({
  label: props.initialConfig?.label || '',
  serverUrl: props.initialConfig?.serverUrl || 'https://relay.example',
  clientId: props.initialConfig?.clientId || '',
  windowSize: Number(props.initialConfig?.windowSize) || 262144,
  pollIntervalMs: props.initialConfig?.pollIntervalMs ?? 2000,
  role: props.initialConfig?.role || 'primary',
  sleepPollMs: props.initialConfig?.sleepPollMs ?? 3_600_000,
  sleepJitterMs: props.initialConfig?.sleepJitterMs ?? 900_000,
  idleMs: props.initialConfig?.idleMs ?? 600_000,
})

watch(cfg, () => emit('config', { ...cfg.value }), { deep: true, immediate: true })

function onDuty(d: Record<string, any>) {
  Object.assign(cfg.value, d)
}
</script>

<template>
  <div class="wlya-server-form">
    <label>
      <span>Display name</span>
      <input v-model="cfg.label" type="text" />
    </label>
    <label>
      <span>Server URL</span>
      <input v-model="cfg.serverUrl" type="text" placeholder="https://relay.example" />
    </label>
    <label>
      <span>Client ID (optional)</span>
      <input v-model="cfg.clientId" type="text" placeholder="auto" />
    </label>
    <label>
      <span>Max packet size (bytes, default 262144)</span>
      <input v-model.number="cfg.windowSize" type="number" min="256" max="1048576" step="256" />
    </label>
    <DutySection
      :initial-config="initialConfig"
      default-role="primary"
      :default-poll-interval-ms="2000"
      @config="onDuty"
    />
  </div>
</template>

<style scoped>
.wlya-server-form { display: flex; flex-direction: column; gap: 10px; }
label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #616161; }
input { font-size: 16px; padding: 6px 8px; }
</style>
