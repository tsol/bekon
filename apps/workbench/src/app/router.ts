import { createRouter, createWebHashHistory } from 'vue-router'
import TunnelsView from '../tunnels/views/TunnelsView.vue'
import PhoneView from '../phone/views/PhoneView.vue'
import VoiceView from '../voice/VoiceView.vue'

const routes = [
  { path: '/', redirect: '/tunnels' },
  { path: '/tunnels', component: TunnelsView, name: 'tunnels' },
  { path: '/voice', component: VoiceView, name: 'voice' },
  { path: '/phone', component: PhoneView, name: 'phone' },
  { path: '/phone/:tunnelId', component: PhoneView, name: 'phone-tunnel' },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})
