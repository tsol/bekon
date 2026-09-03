<script setup lang="ts">
import type { Gesture, GestureStatus, KeyCmd } from '../types'

const props = defineProps<{
  queue: Gesture[]
  executing?: boolean
  lastError?: string
  actShot?: boolean
  sleepMs?: number
  draft?: string
  asKeys?: boolean
}>()

const emit = defineEmits<{
  execute: []
  clear: []
  remove: [id: string]
  'update:actShot': [value: boolean]
  'update:sleepMs': [value: number]
  'update:draft': [value: string]
  'update:asKeys': [value: boolean]
  input: [text: string]
  key: [key: KeyCmd]
  clipboard: []
  abort: []
}>()

function submitInput() {
  const t = props.draft ?? ''
  if (!t) return
  emit('input', t)
}

function label(g: Gesture): string {
  if (g.kind === 'nav') return g.nav ?? 'nav'
  if (g.kind === 'tap') return `tap (${g.x}, ${g.y})`
  if (g.kind === 'longPress') return `longPress (${g.x}, ${g.y})`
  if (g.kind === 'drag') return `drag (${g.x}, ${g.y})`
  if (g.kind === 'release') return 'release'
  if (g.kind === 'swipe') return `swipe (${g.x1},${g.y1})→(${g.x2},${g.y2})`
  if (g.kind === 'input') {
    const body = JSON.stringify(g.text ?? '')
    return g.inputMode === 'keys' ? `keys ${body}` : body
  }
  if (g.kind === 'key') return g.key === 'backspace' && (g.n ?? 1) > 1 ? `backspace ×${g.n}` : (g.key ?? 'key')
  if (g.kind === 'clipboard') return 'get clipboard'
  if (g.kind === 'sleep') return `wait ${g.ms ?? 0}ms`
  if (g.kind === 'screenshot') {
    const q = g.quality != null ? ` q${g.quality}` : ''
    const s = g.scale != null ? ` ${g.scale}×` : ''
    return g.hiRes ? `hi-res jpeg${s}${q}` : `screenshot${s}${q}`
  }
  if (g.kind === 'snapshot') {
    const q = g.quality != null ? ` q${g.quality}` : ''
    const s = g.scale != null ? ` ${g.scale}×` : ''
    return g.hiRes === false ? `snapshot preview${s}${q}` : `snapshot${s}${q}`
  }
  if (g.kind === 'file') {
    const n = g.name || 'file'
    return g.size ? `file ${n} (${g.size} B)` : `file ${n}`
  }
  if (g.kind === 'share') {
    const dest = g.pkg ? g.pkg : 'chooser'
    const who = g.path || g.uri || 'last file'
    return `share ${who} → ${dest}`
  }
  if (g.kind === 'ping') return g.latencyMs != null ? `ping ${g.latencyMs}ms` : 'ping'
  if (g.kind === 'logs') return g.n != null ? `logs n=${g.n}` : 'logs'
  return g.kind
}

function statusOf(g: Gesture): GestureStatus {
  return g.status ?? 'pending'
}
</script>

<template>
  <div class="queue-panel">
    <div class="head">
      <h3>
        <button
          type="button"
          class="mode-toggle"
          :class="{ immediate: actShot }"
          :disabled="executing"
          :title="actShot ? 'Switch to queue mode' : 'Switch to immediate mode'"
          @click="emit('update:actShot', !actShot)"
        >
          <svg v-if="actShot" class="mode-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="currentColor" d="M13 2 4 14h7l-1 8 10-14h-7z" />
          </svg>
          <svg v-else class="mode-icon" viewBox="0 0 24 24" aria-hidden="true">
            <path fill="currentColor" d="M4 6h16v2H4zm0 5h16v2H4zm0 5h10v2H4z" />
          </svg>
          {{ actShot ? 'Immediate' : 'Queue' }}
        </button>
      </h3>
      <div class="actions">
        <label v-if="actShot" class="sleep-ms" title="Phone waits this many ms after the gesture before taking the screenshot">
          <input
            type="number"
            min="0"
            max="30000"
            step="50"
            :value="sleepMs ?? 500"
            :disabled="executing"
            @change="emit('update:sleepMs', Math.min(30000, Math.max(0, Number(($event.target as HTMLInputElement).value) || 0)))"
          />
          ms gap
        </label>
        <button
          v-if="!actShot"
          :disabled="executing || !queue.some(g => !g.status || g.status === 'pending')"
          @click="emit('execute')"
        >
          Execute
        </button>
        <button v-if="!actShot" :disabled="!queue.length" @click="emit('clear')">Clr</button>
        <button v-if="executing" type="button" class="abort" @click="emit('abort')">Abort</button>
      </div>
    </div>
    <ul class="list">
      <li v-for="g in queue" :key="g.id" :class="statusOf(g)">
        <span class="status-icon" :title="g.error || statusOf(g)">
          <span v-if="statusOf(g) === 'sending'" class="spinner" />
          <span v-else-if="statusOf(g) === 'ok'" class="check">✓</span>
          <span v-else-if="statusOf(g) === 'error'" class="cross">✕</span>
          <span v-else-if="statusOf(g) === 'timeout'" class="timeout">⏱</span>
          <span v-else-if="statusOf(g) === 'aborted'" class="timeout">⊘</span>
          <span v-else class="dot" />
        </span>
        <span class="kind">{{ g.kind }}</span>
        <code>{{ label(g) }}</code>
        <button class="x" :disabled="statusOf(g) === 'sending'" @click="emit('remove', g.id)">×</button>
      </li>
    </ul>
    <p v-if="lastError" class="err">{{ lastError }}</p>
    <p v-if="executing" class="busy">
      Waiting for phone ACK…
      <button type="button" class="abort" @click="emit('abort')">Abort</button>
    </p>
    <form class="kb" @submit.prevent="submitInput">
      <div class="keys">
        <button type="button" :disabled="executing" title="Backspace" @click="emit('key', 'backspace')">⌫</button>
        <button type="button" :disabled="executing" title="Enter" @click="emit('key', 'enter')">⏎</button>
        <button type="button" :disabled="executing" title="Select all" @click="emit('key', 'selectAll')">Sel</button>
        <button type="button" :disabled="executing" title="Clear field" @click="emit('key', 'clear')">Clr</button>
        <button type="button" :disabled="executing" title="Copy field → phone clipboard, then show here" @click="emit('key', 'copy')">Copy</button>
        <button type="button" :disabled="executing" title="Cut" @click="emit('key', 'cut')">Cut</button>
        <button type="button" :disabled="executing" title="Paste phone clipboard into field" @click="emit('key', 'paste')">Paste</button>
        <button type="button" :disabled="executing" title="Read phone clipboard into this box" @click="emit('clipboard')">Clip</button>
      </div>
      <textarea
        :value="draft"
        rows="2"
        placeholder="Keyboard input"
        :disabled="executing"
        @input="emit('update:draft', ($event.target as HTMLTextAreaElement).value)"
      />
      <div class="kb-actions">
        <label class="act-shot" title="Type via KeyEvents (Termux). One input command; the phone splits into keys.">
          <input
            type="checkbox"
            :checked="asKeys"
            :disabled="executing"
            @change="emit('update:asKeys', ($event.target as HTMLInputElement).checked)"
          />
          as keys
        </label>
        <button type="submit" :disabled="executing || !(draft || '').trim()">Send</button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.queue-panel { background: #11111f; border: 1px solid #333; border-radius: 6px; padding: 12px; min-width: 260px; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 8px; flex-wrap: wrap; }
h3 { margin: 0; font-size: 14px; color: #eee; }
.mode-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.mode-toggle:disabled { opacity: .5; cursor: default; }
.mode-toggle.immediate { color: #ffcc80; }
.mode-icon { width: 16px; height: 16px; flex-shrink: 0; }
.actions { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.act-shot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #ccc;
  cursor: pointer;
  user-select: none;
}
.act-shot input { margin: 0; accent-color: #64b5f6; }
.sleep-ms {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #aaa;
}
.sleep-ms input {
  width: 64px;
  border: 1px solid #444;
  border-radius: 4px;
  padding: 4px 6px;
  background: #1a1a2a;
  color: #ddd;
  font-size: 12px;
}
button { border: none; border-radius: 4px; padding: 5px 10px; background: #2a2a4a; color: #ddd; cursor: pointer; font-size: 12px; }
button:disabled { opacity: .5; cursor: default; }
.abort { background: #5c2a2a; color: #ffcdd2; }
.list { list-style: none; margin: 0; padding: 0; max-height: 320px; overflow: auto; }
li { display: flex; gap: 8px; align-items: center; padding: 6px 0; border-bottom: 1px solid #222; font-size: 11px; }
.kind { color: #bbb; min-width: 64px; text-transform: uppercase; font-size: 10px; }
code { flex: 1; color: #999; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.x { width: 24px; padding: 2px 0; background: #333; }
.err { color: #ef5350; font-size: 12px; margin: 8px 0 0; }
.busy { display: flex; align-items: center; gap: 8px; color: #ffb74d; font-size: 12px; margin: 8px 0 0; }
.kb {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 10px;
}
.kb textarea {
  width: 100%;
  min-height: 72px;
  resize: vertical;
  box-sizing: border-box;
  border: 1px solid #444;
  border-radius: 4px;
  padding: 6px 8px;
  background: #1a1a2a;
  color: #eee;
  font-size: 13px;
  font-family: inherit;
  line-height: 1.35;
}
.kb-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.kb-actions .act-shot { margin-right: auto; }
.keys {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.keys button { padding: 4px 8px; font-size: 11px; }

.status-icon {
  width: 18px;
  height: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #64b5f6;
}
.check { color: #81c784; font-weight: 700; font-size: 14px; }
.cross { color: #ef5350; font-weight: 700; font-size: 13px; }
.timeout { color: #ce93d8; font-size: 12px; }
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid #555;
  border-top-color: #ffb74d;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
li.sending code { color: #ffcc80; }
li.ok code { color: #a5d6a7; }
li.error code, li.timeout code, li.aborted code { color: #ef9a9a; }
</style>
