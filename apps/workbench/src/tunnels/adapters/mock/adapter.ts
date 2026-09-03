import type { TransportMessage } from '../../core/types.ts'
import { BaseAdapter } from '../../core/types.ts'

class SharedTransportStore {
  messages: TransportMessage[] = []
  transportSeq = 0

  push(msg: TransportMessage): void {
    this.messages.push(msg)
    this.transportSeq = msg.transportSeq
  }

  gc(): void {
    if (this.messages.length > 200) {
      this.messages = this.messages.slice(-100)
    }
  }

  clear(): void {
    this.messages = []
    this.transportSeq = 0
  }
}

const globalStore = new SharedTransportStore()

export class MockAdapter extends BaseAdapter {
  readonly name: string
  readonly windowSize: number = 65536
  private shared = globalStore

  constructor(instanceId: string) {
    super()
    this.name = `mock:${instanceId}`
  }

  get sharedSeq(): number {
    return this.shared.transportSeq
  }

  async init(_channel: string): Promise<void> {
    this.logEvent('MockAdapter ready')
  }

  async poll(lastTransportSeq: number): Promise<TransportMessage[]> {
    return this.shared.messages.filter((m) => m.transportSeq > lastTransportSeq)
  }

  async send(msg: TransportMessage): Promise<void> {
    this.shared.push(msg)
    this.shared.gc()
  }

  clearHistory(): void {
    this.shared.clear()
    this.log.length = 0
    this.logEvent('history cleared')
  }
}
