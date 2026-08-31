export const USE_ACTIVITY_BAR_CONTEXT = "groksBeard.useActivityBar"
export const CHAT_VIEW_ID = "groksBeard.chat"
export const CHAT_VIEW_ID_SECONDARY = "groksBeard.chatSecondary"
export const CHANGES_VIEW_ID = "groksBeard.changes"
export const CHANGES_VIEW_ID_SECONDARY = "groksBeard.changesSecondary"

export type ViewPlacement = "activitybar" | "secondarySidebar"

export const isVsCodeHost = (appName: string): boolean =>
  appName === "Visual Studio Code" || appName === "VS Code"

export const placementForHost = (appName: string): ViewPlacement =>
  isVsCodeHost(appName) ? "secondarySidebar" : "activitybar"

export const chatViewIdForHost = (appName: string): string =>
  placementForHost(appName) === "secondarySidebar" ? CHAT_VIEW_ID_SECONDARY : CHAT_VIEW_ID

export const changesViewIdForHost = (appName: string): string =>
  placementForHost(appName) === "secondarySidebar" ? CHANGES_VIEW_ID_SECONDARY : CHANGES_VIEW_ID
