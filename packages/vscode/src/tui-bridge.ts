import {
  type BridgeHandleFn,
  type BridgeServer,
  listenBridge,
  socketAddress,
} from "@groks-beard/mcp"

export const TUI_BRIDGE_STATE_KEY = "groksBeard.tuiBridge.enabled"

export type TuiBridgeDeps = {
  readonly getEnabled: () => boolean
  readonly workspace: () => string | undefined
  readonly handle: BridgeHandleFn
  readonly log: (message: string) => void
}

export class TuiBridge {
  private server: BridgeServer | undefined

  constructor(private readonly deps: TuiBridgeDeps) {}

  get listening(): boolean {
    return this.server?.listening === true
  }

  address(): string | undefined {
    const workspace = this.deps.workspace()
    if (workspace === undefined || workspace === "") return undefined
    return socketAddress({ workspace })
  }

  async sync(): Promise<void> {
    const workspace = this.deps.workspace()
    if (!this.deps.getEnabled() || workspace === undefined || workspace === "") {
      await this.unbind()
      return
    }
    if (this.server !== undefined) return
    const address = socketAddress({ workspace })
    this.server = await listenBridge(address, this.deps.handle)
    this.deps.log(`TUI bridge listening on ${address}`)
  }

  async unbind(): Promise<void> {
    const server = this.server
    this.server = undefined
    if (server !== undefined) await server.close()
  }
}
