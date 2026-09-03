<script setup lang="ts">
import { reactive, watch } from 'vue'
import DutySection from '../../common/ui-vue/DutySection.vue'

const props = defineProps<{
  initialConfig?: Record<string, any>
}>()

const emit = defineEmits<{
  config: [config: Record<string, any>]
}>()

const cfg = reactive({
  role: props.initialConfig?.role || 'backup',
  pollIntervalMs: props.initialConfig?.pollIntervalMs ?? 2000,
  sleepPollMs: props.initialConfig?.sleepPollMs ?? 3_600_000,
  sleepJitterMs: props.initialConfig?.sleepJitterMs ?? 900_000,
  idleMs: props.initialConfig?.idleMs ?? 600_000,
})

watch(cfg, () => emit('config', { ...cfg }), { deep: true, immediate: true })

function onDuty(d: Record<string, any>) {
  Object.assign(cfg, d)
}
</script>

<template>
  <div>
    <p class="hint">In-memory adapter for local tests.</p>
    <DutySection
      :initial-config="initialConfig"
      default-role="backup"
      :default-poll-interval-ms="2000"
      @config="onDuty"
    />
  </div>
</template>

<style scoped>
.hint { margin: 0 0 8px; font-size: 12px; color: #777; font-style: italic; }
</style>
