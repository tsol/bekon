import { z } from 'zod'

export const mockSchema = z.object({
  // Mock adapter has no config
})

export type MockConfig = z.infer<typeof mockSchema>
