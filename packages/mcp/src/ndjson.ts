export const splitNdjson = (buffer: string, chunk: string): {
  readonly lines: ReadonlyArray<string>
  readonly rest: string
} => {
  const combined = buffer + chunk
  const parts = combined.split("\n")
  const rest = parts.pop() ?? ""
  return {
    lines: parts.filter((line) => line.trim().length > 0),
    rest,
  }
}

export const encodeNdjson = (value: unknown): string => `${JSON.stringify(value)}\n`
