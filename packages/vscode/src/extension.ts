import * as vscode from "vscode"
import { Layer, ManagedRuntime } from "effect"

let runtime: ManagedRuntime.ManagedRuntime<never, never> | undefined

export const activate = (context: vscode.ExtensionContext): void => {
  runtime = ManagedRuntime.make(Layer.empty)
  context.subscriptions.push(
    vscode.commands.registerCommand("groksBeard.open", () => {
      void vscode.window.showInformationMessage("Grok's Beard: chat host is not wired yet.")
    })
  )
}

export const deactivate = (): Thenable<void> | undefined => {
  const rt = runtime
  runtime = undefined
  return rt?.dispose()
}
