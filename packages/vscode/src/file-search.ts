export const MENTION_FILE_LIMIT = 12

export const mentionFilePattern = (query: string): string | undefined => {
  const q = query.trim().replace(/[*?{}[\]\\]/g, "")
  if (q === "") return undefined
  return `**/*${q}*`
}

export const mentionFileExclude = "{**/node_modules/**,**/.git/**,**/target/**,**/dist/**}"

const basenameOf = (path: string): string => {
  const posix = path.replace(/\\/g, "/")
  return posix.slice(posix.lastIndexOf("/") + 1)
}

export const rankMentionFiles = <T extends { readonly path: string }>(
  files: ReadonlyArray<T>,
  query: string,
): Array<T> => {
  const q = query.trim().toLowerCase()
  return [...files].sort((left, right) => {
    const a = basenameOf(left.path).toLowerCase()
    const b = basenameOf(right.path).toLowerCase()
    const as = a.startsWith(q) ? 0 : a.includes(q) ? 1 : left.path.toLowerCase().includes(q) ? 2 : 3
    const bs = b.startsWith(q)
      ? 0
      : b.includes(q)
      ? 1
      : right.path.toLowerCase().includes(q)
      ? 2
      : 3
    if (as !== bs) return as - bs
    if (left.path.length !== right.path.length) return left.path.length - right.path.length
    return left.path.localeCompare(right.path)
  })
}
