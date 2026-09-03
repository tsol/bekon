import type { AdapterManifest, BaseAdapter } from '../core/types.ts'

// ── Runtime: adapter manifests + factory ──

const manifests = new Map<string, AdapterManifest>()

export function registerAdapterType(manifest: AdapterManifest): void {
  manifests.set(manifest.type, manifest)
}

export function getAdapterType(type: string): AdapterManifest | undefined {
  return manifests.get(type)
}

export function listAdapterTypes(): Omit<AdapterManifest, 'factory' | 'schema'>[] {
  return Array.from(manifests.values()).map((m) => ({
    type: m.type,
    label: m.label,
    defaultConfig: m.defaultConfig,
  }))
}

export function createAdapter(type: string, config: any): BaseAdapter {
  const manifest = manifests.get(type)
  if (manifest) {
    const parsed = manifest.schema.parse(config)
    return manifest.factory(parsed)
  }
  throw new Error(`Unknown adapter type: ${type}`)
}

export function getDefaultConfig(type: string): Record<string, any> {
  const manifest = manifests.get(type)
  if (manifest) return manifest.defaultConfig
  return {}
}