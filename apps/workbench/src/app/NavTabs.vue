<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppLogContext } from './selectedTunnel'
import PhoneTunnelPicker from '../phone/components/PhoneTunnelPicker.vue'

const route = useRoute()
const router = useRouter()
const { logTunnelId, setLogTunnel } = useAppLogContext()

const logsVisible = defineModel<boolean>('logsVisible', { default: true })

const menuOpen = ref(false)
const splitRef = ref<HTMLElement | null>(null)

const activeTab = computed(() => {
  if (route.path.startsWith('/phone')) return 'phone'
  if (route.path.startsWith('/voice')) return 'voice'
  return 'tunnels'
})

const currentPhoneTunnel = computed(() => {
  const fromRoute = route.params.tunnelId
  if (typeof fromRoute === 'string' && fromRoute) return fromRoute
  return logTunnelId.value || ''
})

function goPhone() {
  menuOpen.value = false
  const id = currentPhoneTunnel.value
  if (id) router.push(`/phone/${id}`)
  else router.push('/phone')
}

function toggleMenu() {
  menuOpen.value = !menuOpen.value
}

function selectTunnel(id: string) {
  menuOpen.value = false
  setLogTunnel(id)
  router.push(`/phone/${id}`)
}

function onDocClick(ev: MouseEvent) {
  const el = splitRef.value
  if (!el || !menuOpen.value) return
  if (!el.contains(ev.target as Node)) menuOpen.value = false
}

function onKey(ev: KeyboardEvent) {
  if (ev.key === 'Escape') menuOpen.value = false
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKey)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <nav class="nav-tabs">
    <button class="tab" :class="{ active: activeTab === 'tunnels' }" @click="router.push('/tunnels')">
      Tunnels
    </button>
    <button class="tab" :class="{ active: activeTab === 'voice' }" @click="router.push('/voice')">
      Voice
    </button>
    <div ref="splitRef" class="phone-split">
      <button
        class="tab tab-main"
        :class="{ active: activeTab === 'phone' }"
        @click="goPhone"
      >
        Phone Control
      </button>
      <button
        class="tab tab-chevron"
        :class="{ active: activeTab === 'phone', open: menuOpen }"
        type="button"
        title="Select phone tunnel"
        aria-label="Select phone tunnel"
        :aria-expanded="menuOpen"
        @click.stop="toggleMenu"
      >
        <svg class="chevron" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
          <path
            d="M2.5 4.5L6 8L9.5 4.5"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
      </button>
      <PhoneTunnelPicker
        v-if="menuOpen"
        class="phone-menu"
        :model-value="currentPhoneTunnel"
        @update:model-value="selectTunnel"
      />
    </div>
    <label class="logs-toggle" title="Show or hide the live log panel">
      <input v-model="logsVisible" type="checkbox" />
      Logs
    </label>
  </nav>
</template>

<style scoped>
.nav-tabs {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: #1a1a2e;
  border-bottom: 1px solid #333;
}

.tab {
  padding: 6px 14px;
  background: transparent;
  border: 1px solid #444;
  border-radius: 6px;
  color: #999;
  font-size: 13px;
  cursor: pointer;
}

.tab.active {
  background: #2a2a4a;
  border-color: #64b5f6;
  color: #fff;
}

.phone-split {
  position: relative;
  display: inline-flex;
}

.tab-main {
  border-radius: 6px 0 0 6px;
  border-right: none;
}

.tab-chevron {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 8px;
  border-radius: 0 6px 6px 0;
  border-left: 1px solid #444;
}

.tab-chevron.active {
  border-left-color: #64b5f6;
}

.tab-chevron.open .chevron {
  transform: rotate(180deg);
}

.chevron {
  display: block;
  transition: transform 0.15s ease;
}

.phone-menu {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 30;
}

.logs-toggle {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #ccc;
  cursor: pointer;
  user-select: none;
}

.logs-toggle input {
  margin: 0;
  accent-color: #64b5f6;
}
</style>
