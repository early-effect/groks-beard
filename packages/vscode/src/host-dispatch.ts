import { WEBVIEW_MSG_HANDLED, type WebviewMsg } from "@groks-beard/core"

export type WebviewHandlers = {
  [K in WebviewMsg["_tag"]]: (msg: Extract<WebviewMsg, { _tag: K }>) => void
}

export const dispatchWebviewMsg = (msg: WebviewMsg, handlers: WebviewHandlers): void => {
  switch (msg._tag) {
    case "ready":
      handlers.ready(msg)
      return
    case "send":
      handlers.send(msg)
      return
    case "queue":
      handlers.queue(msg)
      return
    case "steer":
      handlers.steer(msg)
      return
    case "cancel":
      handlers.cancel(msg)
      return
    case "permissionChoice":
      handlers.permissionChoice(msg)
      return
    case "permissionPark":
      handlers.permissionPark(msg)
      return
    case "openDiff":
      handlers.openDiff(msg)
      return
    case "planVerdict":
      handlers.planVerdict(msg)
      return
    case "questionChoice":
      handlers.questionChoice(msg)
      return
    case "questionDismiss":
      handlers.questionDismiss(msg)
      return
    case "questionPark":
      handlers.questionPark(msg)
      return
    case "elicitAccept":
      handlers.elicitAccept(msg)
      return
    case "elicitDecline":
      handlers.elicitDecline(msg)
      return
    case "slashPick":
      handlers.slashPick(msg)
      return
    case "mentionQuery":
      handlers.mentionQuery(msg)
      return
    case "mentionPick":
      handlers.mentionPick(msg)
      return
    case "cycleMode":
      handlers.cycleMode(msg)
      return
    case "setMode":
      handlers.setMode(msg)
      return
    case "setModel":
      handlers.setModel(msg)
      return
    case "sendNow":
      handlers.sendNow(msg)
      return
    case "setReasoning":
      handlers.setReasoning(msg)
      return
    case "openSettings":
      handlers.openSettings(msg)
      return
    case "openChanges":
      handlers.openChanges(msg)
      return
    case "keepAllPending":
      handlers.keepAllPending(msg)
      return
    case "undoAllPending":
      handlers.undoAllPending(msg)
      return
    case "commitAllPending":
      handlers.commitAllPending(msg)
      return
    case "refreshMcp":
      handlers.refreshMcp(msg)
      return
    case "setMcpEnabled":
      handlers.setMcpEnabled(msg)
      return
    case "setMcpToolEnabled":
      handlers.setMcpToolEnabled(msg)
      return
    case "revealEditor":
      handlers.revealEditor(msg)
      return
    case "addSelection":
      handlers.addSelection(msg)
      return
    case "openPlan":
      handlers.openPlan(msg)
      return
    case "openMcpConfig":
      handlers.openMcpConfig(msg)
      return
    case "openSettingsJson":
      handlers.openSettingsJson(msg)
      return
    case "trustFolder":
      handlers.trustFolder(msg)
      return
    case "setSetting":
      handlers.setSetting(msg)
      return
  }
}

export const WEBVIEW_DISPATCH_HANDLED: Record<WebviewMsg["_tag"], true> = {
  ready: true,
  send: true,
  queue: true,
  steer: true,
  cancel: true,
  permissionChoice: true,
  permissionPark: true,
  openDiff: true,
  planVerdict: true,
  questionChoice: true,
  questionDismiss: true,
  questionPark: true,
  elicitAccept: true,
  elicitDecline: true,
  slashPick: true,
  mentionQuery: true,
  mentionPick: true,
  cycleMode: true,
  setMode: true,
  setModel: true,
  sendNow: true,
  setReasoning: true,
  openSettings: true,
  openChanges: true,
  keepAllPending: true,
  undoAllPending: true,
  commitAllPending: true,
  refreshMcp: true,
  setMcpEnabled: true,
  setMcpToolEnabled: true,
  revealEditor: true,
  addSelection: true,
  openPlan: true,
  openMcpConfig: true,
  openSettingsJson: true,
  trustFolder: true,
  setSetting: true,
}

const _lock: typeof WEBVIEW_MSG_HANDLED = WEBVIEW_DISPATCH_HANDLED
void _lock
