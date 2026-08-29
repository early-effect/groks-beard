export const VIEW_PLACEMENT_KEY = "groksBeard.viewPlacement"
export const CHAT_VIEW_ID = "groksBeard.chat"
export const CHANGES_VIEW_ID = "groksBeard.changes"
export const SECONDARY_SIDE_BAR_DESTINATION = "workbench.view.secondarySideBar"

export type ViewPlacement = "activitybar" | "secondarySidebar"

export const isVsCodeHost = (appName: string): boolean =>
  appName === "Visual Studio Code" || appName === "VS Code"

export const shouldMoveViewsOnActivate = (input: {
  readonly appName: string
  readonly persisted: ViewPlacement | undefined
}): boolean => isVsCodeHost(input.appName) && input.persisted === undefined

export const maybePlaceViews = async (input: {
  readonly appName: string
  readonly persisted: ViewPlacement | undefined
  readonly persist: (placement: ViewPlacement) => PromiseLike<void>
  readonly moveViews: (
    viewIds: ReadonlyArray<string>,
    destinationId: string,
  ) => PromiseLike<unknown>
}): Promise<ViewPlacement> => {
  if (!shouldMoveViewsOnActivate({ appName: input.appName, persisted: input.persisted })) {
    return input.persisted ?? "activitybar"
  }
  try {
    await input.moveViews([CHAT_VIEW_ID, CHANGES_VIEW_ID], SECONDARY_SIDE_BAR_DESTINATION)
    await input.persist("secondarySidebar")
    return "secondarySidebar"
  } catch {
    await input.persist("activitybar")
    return "activitybar"
  }
}
