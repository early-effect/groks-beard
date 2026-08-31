export const CHANGES_PANE_CONTEXT = "groksBeard.changesPane"
export const CHANGES_PRESENTATION_KEY = "groksBeard.changesPresentation"

export type ChangesPresentation = "toast" | "pane"

export const changesPresentationFrom = (value: unknown): ChangesPresentation =>
  value === "pane" ? "pane" : "toast"

export const changesPaneVisible = (presentation: ChangesPresentation): boolean =>
  presentation === "pane"
