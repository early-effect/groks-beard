export type SessionState = {
  modeId: string | undefined
  planActive: boolean
}

export const emptySessionState = (): SessionState => ({
  modeId: undefined,
  planActive: false
})

export const commitMode = (state: SessionState, modeId: string): void => {
  state.modeId = modeId
  state.planActive = modeId.toLowerCase().includes("plan")
}
