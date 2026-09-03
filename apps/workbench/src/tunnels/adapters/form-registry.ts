import type { Component } from 'vue'
import { defineAsyncComponent } from 'vue'

// ── UI: adapter configuration forms (co-located in wlya-adapters/*/ui-vue/) ──

export interface AdapterFormEntry {
  type: string
  label: string
  component: Component
  defaultConfig: Record<string, any>
}

const forms = new Map<string, AdapterFormEntry>()

export function registerAdapterForm(entry: AdapterFormEntry): void {
  forms.set(entry.type, entry)
}

export function getAdapterForm(type: string): AdapterFormEntry | undefined {
  return forms.get(type)
}

export function listAdapterForms(): AdapterFormEntry[] {
  return Array.from(forms.values())
}

const formModules = import.meta.glob<{ default: Component }>('@wlya/adapters/*/ui-vue/Form.vue')

for (const modulePath in formModules) {
  const match = modulePath.match(/wlya-adapters\/([^/]+)\/ui-vue\/Form\.vue$/)
  if (!match) continue
  const type = match[1]
  registerAdapterForm({
    type,
    label: type,
    component: defineAsyncComponent(formModules[modulePath]),
    defaultConfig: {},
  })
}
