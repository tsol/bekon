/// <reference types="vite/client" />

export {}

declare global {
  interface Window {
    __vue_ready?: boolean
    __vue_errors: unknown[]
    __app_mounted?: boolean
    __main_errors: unknown[]
  }
}

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<object, object, unknown>
  export default component
}
