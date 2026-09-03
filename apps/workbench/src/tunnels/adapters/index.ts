import { registerAdapterType } from './registry.ts'
import { mockSchema } from './mock/schema.ts'
import { MockAdapter } from './mock/adapter.ts'

export function registerAllAdapters(): void {
  registerAdapterType({
    type: 'mock',
    label: 'In-Memory Mock',
    schema: mockSchema,
    defaultConfig: {},
    factory: (config: Record<string, any>) => new MockAdapter(config.id || 'default'),
  })
}

registerAllAdapters()
