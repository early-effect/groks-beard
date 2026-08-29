export const utf8ByteLength = (text: string): number => {
  let n = 0
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i)
    if (c <= 0x7f) n += 1
    else if (c <= 0x7ff) n += 2
    else if (c >= 0xd800 && c <= 0xdbff) {
      n += 4
      i++
    } else n += 3
  }
  return n
}

export const truncateToByteCap = (text: string, cap: number): string => {
  if (utf8ByteLength(text) <= cap) return text
  let n = 0
  let i = 0
  while (i < text.length) {
    const c = text.charCodeAt(i)
    const size = c <= 0x7f ? 1 : c <= 0x7ff ? 2 : c >= 0xd800 && c <= 0xdbff ? 4 : 3
    if (n + size > cap) break
    n += size
    i += size === 4 ? 2 : 1
  }
  return text.slice(0, i)
}

export const truncateKeepingUtf8Tail = (text: string, cap: number): string => {
  const total = utf8ByteLength(text)
  if (total <= cap) return text
  let skip = total - cap
  let i = 0
  while (i < text.length && skip > 0) {
    const c = text.charCodeAt(i)
    const size = c <= 0x7f ? 1 : c <= 0x7ff ? 2 : c >= 0xd800 && c <= 0xdbff ? 4 : 3
    skip -= size
    i += size === 4 ? 2 : 1
  }
  return text.slice(i)
}
