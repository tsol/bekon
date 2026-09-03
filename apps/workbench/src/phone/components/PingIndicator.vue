<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  pinging?: boolean
  latencyMs?: number | null
  ok?: boolean | null
}>()

const emit = defineEmits<{ ping: [] }>()

const state = computed(() => {
  if (props.pinging) return 'pinging'
  if (props.ok === true) return 'ok'
  if (props.ok === false) return 'fail'
  return 'idle'
})
</script>

<template>
  <div class="ping" :class="state">
    <button :disabled="pinging" @click="emit('ping')">Ping</button>
    <span v-if="latencyMs != null" class="badge">{{ latencyMs }} ms</span>
    <span v-else-if="state === 'fail'" class="badge bad">timeout</span>
  </div>
</template>

<style scoped>
.ping { display: flex; align-items: center; gap: 8px; }
button { border: none; border-radius: 4px; padding: 6px 10px; background: #2a2a4a; color: #ddd; cursor: pointer; font-size: 12px; }
.badge { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: #1b5e20; color: #a5d6a7; font-family: monospace; }
.badge.bad { background: #b71c1c; color: #ffcdd2; }
.ok .badge { background: #1b5e20; }
.fail .badge { background: #b71c1c; }
</style>
