import { createApp } from 'vue'
import App from './App.vue'
import { router } from './app/router.ts'
import './tunnels/adapters/index.ts'

window.__vue_errors = []

const app = createApp(App)

app.config.errorHandler = (err, _instance, info) => {
  const msg = `[Vue Error] ${err instanceof Error ? err.message : String(err)} | Info: ${info}`
  window.__vue_errors.push(msg)
  console.error(msg)
}

app.use(router)
app.mount('#app')

window.__vue_ready = true
