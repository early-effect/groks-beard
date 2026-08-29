import { expect, it } from "@effect/vitest"
import { filterSlashCommands, slashQueryFromDraft } from "../src/slash.ts"

const commands = [
  { name: "compact", description: "Compact context" },
  { name: "always-approve", description: "Skip permission prompts" },
  { name: "init", description: "Initialize project memory" },
]

it("filters slash commands prefix, then mid-name, then description", () => {
  expect(filterSlashCommands(commands, "always").map((c) => c.name)).toEqual(["always-approve"])
  expect(filterSlashCommands(commands, "approve").map((c) => c.name)).toEqual(["always-approve"])
  expect(filterSlashCommands(commands, "memory").map((c) => c.name)).toEqual(["init"])
})

it("does not hide always-approve", () => {
  expect(filterSlashCommands(commands, "").map((c) => c.name)).toContain("always-approve")
})

it("reads a slash query from the composer draft", () => {
  expect(slashQueryFromDraft("/comp")).toBe("comp")
  expect(slashQueryFromDraft("hello")).toBeUndefined()
})
