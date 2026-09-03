<script setup lang="ts">
import { ref } from 'vue'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; create: [label: string, channel: string, secret: string] }>()

const label = ref('')
const channel = ref('')
const secret = ref('')
const error = ref('')

function submit() {
  if (!label.value.trim()) {
    error.value = 'Label required'
    return
  }
  error.value = ''
  emit('create', label.value.trim(), channel.value.trim(), secret.value)
  label.value = ''
  channel.value = ''
  secret.value = ''
}
</script>

<template>
  <div v-if="open" class="overlay" @click.self="emit('close')">
    <div class="modal">
      <h3>New tunnel</h3>
      <label>Label<input v-model="label" placeholder="desktop" @keyup.enter="submit" /></label>
      <label>Channel (optional)<input v-model="channel" placeholder="room-name" spellcheck="false" @keyup.enter="submit" /></label>
      <label>Secret (optional)<input v-model="secret" type="password" placeholder="empty = same as channel" @keyup.enter="submit" /></label>
      <p v-if="error" class="err">{{ error }}</p>
      <div class="actions">
        <button class="ghost" @click="emit('close')">Cancel</button>
        <button class="primary" @click="submit">Create</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay { position: fixed; inset: 0; background: rgba(0,0,0,.65); display: flex; align-items: center; justify-content: center; z-index: 50; }
.modal { width: 360px; background: #171728; border: 1px solid #444; border-radius: 8px; padding: 16px; display: flex; flex-direction: column; gap: 10px; }
h3 { margin: 0; color: #eee; font-size: 15px; }
label { display: flex; flex-direction: column; gap: 4px; font-size: 11px; color: #888; text-transform: uppercase; }
input { padding: 8px; background: #0f0f1a; border: 1px solid #555; border-radius: 4px; color: #ddd; }
.actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.ghost, .primary { border: none; border-radius: 4px; padding: 6px 12px; cursor: pointer; font-size: 12px; }
.ghost { background: #333; color: #bbb; }
.primary { background: #1565c0; color: #fff; }
.err { color: #ef5350; font-size: 12px; margin: 0; }
</style>
