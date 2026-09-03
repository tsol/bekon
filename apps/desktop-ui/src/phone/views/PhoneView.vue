<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import PhoneWorkbench from '../components/PhoneWorkbench.vue'

const route = useRoute()
const tunnelId = computed(() => {
  const id = route.params.tunnelId
  return typeof id === 'string' ? id : undefined
})
</script>

<template>
  <PhoneWorkbench :tunnel-id="tunnelId" />
</template>
