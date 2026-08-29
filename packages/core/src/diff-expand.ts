export const MAX_DIFF_EXPAND_BYTES = 2 * 1024 * 1024

export type DiffSite = {
  readonly oldText: string
  readonly newText: string
  readonly oldLine?: number
  readonly newLine?: number
}

export type DiffExpandInput = {
  readonly diskText: string | undefined
  readonly oldRegion: string
  readonly newRegion: string
  readonly diskIsBefore: boolean
  readonly replaceAll?: boolean
  readonly sites?: ReadonlyArray<DiffSite>
}

export type DiffSides = {
  readonly oldText: string
  readonly newText: string
  readonly firstChangedLine: number
  readonly wholeFile: boolean
}

import { utf8ByteLength } from "./utf8.js"

const utf8Bytes = utf8ByteLength

const oversize = (text: string | undefined): boolean =>
  text !== undefined && utf8Bytes(text) > MAX_DIFF_EXPAND_BYTES

const toLf = (text: string): string => text.replace(/\r\n/g, "\n")

const restoreCrlf = (lfText: string): string => lfText.replace(/\n/g, "\r\n")

const firstChangedLine = (oldText: string, newText: string): number => {
  const oldLines = oldText.split("\n")
  const newLines = newText.split("\n")
  const n = Math.min(oldLines.length, newLines.length)
  for (let i = 0; i < n; i++) {
    if (oldLines[i] !== newLines[i]) return i
  }
  return n === 0 ? 0 : n
}

const regionOnly = (oldRegion: string, newRegion: string): DiffSides => ({
  oldText: oldRegion,
  newText: newRegion,
  firstChangedLine: firstChangedLine(oldRegion, newRegion),
  wholeFile: false
})

const wholeFile = (oldText: string, newText: string): DiffSides => ({
  oldText,
  newText,
  firstChangedLine: firstChangedLine(oldText, newText),
  wholeFile: true
})

const indexesOf = (haystack: string, needle: string): Array<number> => {
  if (needle === "") return []
  const out: Array<number> = []
  let from = 0
  while (from <= haystack.length) {
    const at = haystack.indexOf(needle, from)
    if (at === -1) break
    out.push(at)
    from = at + Math.max(needle.length, 1)
  }
  return out
}

const lineAtOffset = (text: string, offset: number): number => {
  let line = 1
  const limit = Math.min(offset, text.length)
  for (let i = 0; i < limit; i++) {
    if (text[i] === "\n") line++
  }
  return line
}

const pickIndex = (
  haystack: string,
  needle: string,
  siteLine: number | undefined
): number => {
  const hits = indexesOf(haystack, needle)
  if (hits.length === 0) return -1
  if (siteLine === undefined) return hits[0] ?? -1
  const match = hits.find((at) => lineAtOffset(haystack, at) === siteLine)
  return match ?? hits[0] ?? -1
}

const replaceOnceAt = (haystack: string, start: number, needle: string, replacement: string): string =>
  haystack.slice(0, start) + replacement + haystack.slice(start + needle.length)

const replaceAll = (haystack: string, needle: string, replacement: string): string => {
  if (needle === "") return haystack
  return haystack.split(needle).join(replacement)
}

type Located = {
  readonly text: string
  readonly crlf: boolean
}

const locateHaystack = (haystack: string, needle: string): Located | undefined => {
  if (needle === "" || haystack.includes(needle)) return { text: haystack, crlf: haystack.includes("\r\n") }
  if (haystack.includes("\r\n") && !needle.includes("\r\n")) {
    const lf = toLf(haystack)
    if (lf.includes(needle)) return { text: lf, crlf: true }
  }
  return undefined
}

const emit = (located: Located): string => located.crlf && !located.text.includes("\r\n")
  ? restoreCrlf(located.text)
  : located.text

export const expandDiffToWholeFile = (input: DiffExpandInput): DiffSides => {
  const { diskText, oldRegion, newRegion, diskIsBefore } = input
  if (oversize(diskText) || oversize(oldRegion) || oversize(newRegion)) {
    return regionOnly(oldRegion, newRegion)
  }

  if (diskText === undefined) {
    if (oldRegion === "") return wholeFile("", newRegion)
    return regionOnly(oldRegion, newRegion)
  }

  if (oldRegion === "" && diskIsBefore && diskText.length > 0) {
    return wholeFile(diskText, newRegion)
  }

  const needle = diskIsBefore ? oldRegion : newRegion
  const replacement = diskIsBefore ? newRegion : oldRegion
  if (needle === "") {
    return diskIsBefore ? wholeFile(diskText, newRegion) : regionOnly(oldRegion, newRegion)
  }

  const located = locateHaystack(diskText, needle)
  if (located === undefined) return regionOnly(oldRegion, newRegion)

  const siteLine = input.sites?.[0]?.oldLine
  let nextText: string
  if (input.replaceAll === true) {
    nextText = replaceAll(located.text, needle, replacement)
  } else {
    const at = pickIndex(located.text, needle, siteLine)
    if (at === -1) return regionOnly(oldRegion, newRegion)
    nextText = replaceOnceAt(located.text, at, needle, replacement)
  }

  const proposed = emit({ text: nextText, crlf: located.crlf })
  const original = emit(located)
  return diskIsBefore ? wholeFile(original, proposed) : wholeFile(proposed, original)
}
