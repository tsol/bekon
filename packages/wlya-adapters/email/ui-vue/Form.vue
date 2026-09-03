<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import DutySection from '../../common/ui-vue/DutySection.vue'

const props = defineProps<{
  initialConfig?: Record<string, any>
}>()

const emit = defineEmits<{
  config: [config: Record<string, any>]
}>()

const cfg = ref({
  label: props.initialConfig?.label || '',
  host: props.initialConfig?.host || 'imap.mail.ru',
  port: props.initialConfig?.port ?? 993,
  smtpHost: props.initialConfig?.smtpHost || 'smtp.mail.ru',
  smtpPort: props.initialConfig?.smtpPort ?? 465,
  login: props.initialConfig?.login || '',
  password: props.initialConfig?.password || '',
  useSSL: props.initialConfig?.useSSL ?? true,
  smtpUseSSL: props.initialConfig?.smtpUseSSL ?? true,
  sendMode: props.initialConfig?.sendMode || 'smtp',
  imapFolder: props.initialConfig?.imapFolder || 'INBOX',
  smtpTo: props.initialConfig?.smtpTo || '',
  tunnelMessageTtlSeconds: props.initialConfig?.tunnelMessageTtlSeconds ?? 900,
  windowSize: Number(props.initialConfig?.windowSize) || 262144,
  pollIntervalMs: props.initialConfig?.pollIntervalMs ?? 10000,
  role: props.initialConfig?.role || 'backup',
  sleepPollMs: props.initialConfig?.sleepPollMs ?? 3_600_000,
  sleepJitterMs: props.initialConfig?.sleepJitterMs ?? 900_000,
  idleMs: props.initialConfig?.idleMs ?? 600_000,
})

const isSmtpMode = computed(() => cfg.value.sendMode === 'smtp')

watch(cfg, () => emit('config', { ...cfg.value }), { deep: true, immediate: true })

function emitConfig() {
  emit('config', { ...cfg.value })
}

function onDuty(d: Record<string, any>) {
  Object.assign(cfg.value, d)
}
</script>

<template>
  <div class="email-form">
    <label>
      <span>Display name</span>
      <input v-model="cfg.label" placeholder="Work email" @input="emitConfig" />
    </label>
    <label>
      <span>Send mode</span>
      <select v-model="cfg.sendMode" @change="emitConfig">
        <option value="smtp">SMTP (send) + IMAP (poll)</option>
        <option value="imap">IMAP only (append + poll)</option>
      </select>
    </label>
    <label>
      <span>IMAP folder</span>
      <input v-model="cfg.imapFolder" placeholder="INBOX" @input="emitConfig" />
    </label>
    <label>
      <span>Host</span>
      <input v-model="cfg.host" placeholder="imap.gmail.com" @input="emitConfig" />
    </label>
    <label>
      <span>Port</span>
      <input v-model.number="cfg.port" type="number" placeholder="993" @input="emitConfig" />
    </label>
    <template v-if="isSmtpMode">
      <label>
        <span>SMTP host</span>
        <input v-model="cfg.smtpHost" placeholder="smtp.mail.ru" @input="emitConfig" />
      </label>
      <label>
        <span>SMTP port</span>
        <input v-model.number="cfg.smtpPort" type="number" placeholder="465" @input="emitConfig" />
      </label>
      <label>
        <span>SMTP To</span>
        <input v-model="cfg.smtpTo" :placeholder="cfg.login || 'same as login'" @input="emitConfig" />
      </label>
    </template>
    <label>
      <span>Login</span>
      <input v-model="cfg.login" placeholder="user@domain.com" @input="emitConfig" />
    </label>
    <label>
      <span>Password</span>
      <input v-model="cfg.password" type="password" placeholder="••••••••" @input="emitConfig" />
    </label>
    <label>
      <span>Delete messages older than (sec)</span>
      <input v-model.number="cfg.tunnelMessageTtlSeconds" type="number" min="0" placeholder="900" @input="emitConfig" />
    </label>
    <label>
      <span>Max packet size (bytes, default 262144)</span>
      <input v-model.number="cfg.windowSize" type="number" min="256" max="1048576" step="256" @input="emitConfig" />
    </label>
    <label class="checkbox-row">
      <input v-model="cfg.useSSL" type="checkbox" @change="emitConfig" />
      <span>IMAP SSL/TLS</span>
    </label>
    <label v-if="isSmtpMode" class="checkbox-row">
      <input v-model="cfg.smtpUseSSL" type="checkbox" @change="emitConfig" />
      <span>SMTP SSL/TLS</span>
    </label>
    <DutySection
      :initial-config="initialConfig"
      default-role="backup"
      :default-poll-interval-ms="10000"
      @config="onDuty"
    />
  </div>
</template>

<style scoped>
.email-form { display: flex; flex-direction: column; gap: 6px; }
label { display: flex; flex-direction: column; gap: 2px; font-size: 10px; color: #888; text-transform: uppercase; }
input, select { padding: 4px 6px; background: #0d0d1a; border: 1px solid #555; border-radius: 3px; color: #ccc; font-size: 11px; font-family: monospace; }
.checkbox-row { flex-direction: row; align-items: center; gap: 6px; }
.checkbox-row input { width: auto; }
</style>
