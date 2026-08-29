export const ORIGINAL_SCHEME = "beard-original"
export const PROPOSED_SCHEME = "beard-proposed"

export const normalizeAbsPath = (absPath: string): string => {
  const posix = absPath.replace(/\\/g, "/")
  return posix.startsWith("/") ? posix : `/${posix}`
}

export const docKey = (scheme: string, absPath: string): string =>
  `${scheme}:${normalizeAbsPath(absPath)}`

export const virtualDocRef = (
  scheme: string,
  absPath: string
): { readonly scheme: string; readonly path: string } => ({
  scheme,
  path: normalizeAbsPath(absPath)
})

export class BeardDocStore {
  private readonly bodies = new Map<string, string>()
  private readonly listeners: Array<(scheme: string, absPath: string) => void> = []

  onDidChange(listener: (scheme: string, absPath: string) => void): () => void {
    this.listeners.push(listener)
    return () => {
      const idx = this.listeners.indexOf(listener)
      if (idx >= 0) this.listeners.splice(idx, 1)
    }
  }

  set(scheme: string, absPath: string, text: string): void {
    this.bodies.set(docKey(scheme, absPath), text)
    for (const listener of this.listeners) listener(scheme, absPath)
  }

  get(scheme: string, absPath: string): string {
    return this.bodies.get(docKey(scheme, absPath)) ?? ""
  }

  setPair(absPath: string, original: string, proposed: string): void {
    this.set(ORIGINAL_SCHEME, absPath, original)
    this.set(PROPOSED_SCHEME, absPath, proposed)
  }
}
