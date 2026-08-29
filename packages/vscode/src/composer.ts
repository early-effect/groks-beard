import {
  buildPromptText,
  chipFromFile,
  chipFromSelection,
  chipsForSend,
  formatAtRef,
  type PromptChip
} from "@groks-beard/core"

export class ComposerState {
  chips: Array<PromptChip> = []
  pendingSelection: PromptChip | undefined

  addChip(chip: PromptChip): void {
    this.chips = [...this.chips.filter((existing) =>
      existing.absPath !== chip.absPath || existing.startLine !== chip.startLine
    ), chip]
  }

  clear(): void {
    this.chips = []
  }

  setPendingSelection(chip: PromptChip | undefined): void {
    this.pendingSelection = chip
  }

  promptText(text: string, includeActiveFileByDefault: boolean, activeFile?: PromptChip): string {
    const chips = chipsForSend({
      chips: this.chips,
      includeActiveFileByDefault,
      ...(activeFile !== undefined ? { activeFile } : {})
    })
    return buildPromptText(text, chips)
  }
}

export { buildPromptText, chipFromFile, chipFromSelection, chipsForSend, formatAtRef }
