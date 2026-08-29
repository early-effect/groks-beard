export const CLI_INSTALL_HINT =
  "Install the Grok Build CLI: curl -fsSL https://x.ai/cli/install.sh | bash && grok login"

export const missingCliMessage = (searched: ReadonlyArray<string>): string =>
  `Grok CLI not found. ${CLI_INSTALL_HINT}\nLooked in: ${searched.join(", ")}`

export const missingNodeMessage = (searched: ReadonlyArray<string>): string =>
  `Node.js not found. Install Node or set groksBeard.nodePath to an absolute node binary.\nLooked in: ${
    searched.join(", ")
  }`
