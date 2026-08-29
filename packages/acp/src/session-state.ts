export type SessionState = {
  modeId: string | undefined
  planActive: boolean
}

export const emptySessionState = (): SessionState => ({
  modeId: undefined,
  planActive: false,
})

export const commitMode = (state: SessionState, modeId: string): void => {
  state.modeId = modeId
  state.planActive = modeId.toLowerCase().includes("plan")
}

export const modeIdFromSessionResult = (result: unknown): string | undefined => {
  if (typeof result !== "object" || result === null) return undefined
  const modes = (result as { modes?: unknown }).modes
  if (typeof modes !== "object" || modes === null) return undefined
  const id = (modes as { currentModeId?: unknown }).currentModeId
  return typeof id === "string" && id !== "" ? id : undefined
}

export const modeIdFromSessionUpdateParams = (params: unknown): string | undefined => {
  if (typeof params !== "object" || params === null) return undefined
  const rec = params as Record<string, unknown>
  const update = typeof rec.update === "object" && rec.update !== null
    ? rec.update as Record<string, unknown>
    : rec
  if (update.sessionUpdate !== "current_mode_update") return undefined
  const modeId = update.modeId ?? update.currentModeId
  return typeof modeId === "string" && modeId !== "" ? modeId : undefined
}
