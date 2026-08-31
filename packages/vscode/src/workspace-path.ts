const normalize = (path: string): string => path.replace(/\\/g, "/").replace(/\/+$/, "")

const isAbsolutePath = (path: string): boolean => {
  const posix = normalize(path)
  return posix.startsWith("/") || /^[A-Za-z]:\//.test(posix)
}

export const pathIsInsideRoot = (absPath: string, root: string): boolean => {
  if (root === "") return false
  const path = normalize(absPath)
  const base = normalize(root)
  if (path === base) return true
  const prefix = `${base}/`
  return path.startsWith(prefix) || path.toLowerCase().startsWith(prefix.toLowerCase())
}

export const isWorkspaceFilePath = (
  filePath: string,
  roots: ReadonlyArray<string>,
): boolean => {
  if (roots.length === 0) return false
  if (filePath === "" || filePath === ".") return false
  if (!isAbsolutePath(filePath)) return true
  return roots.some((root) => pathIsInsideRoot(filePath, root))
}
