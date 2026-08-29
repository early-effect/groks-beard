export const chatCss = `
:root {
  color: var(--vscode-foreground);
  background: var(--vscode-sideBar-background);
  font-family: var(--vscode-font-family);
  font-size: var(--vscode-font-size);
}
html, body, #root {
  height: 100%;
  margin: 0;
}
#root {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  box-sizing: border-box;
  padding: 8px;
  gap: 8px;
}
header.meta {
  display: flex;
  gap: 8px;
  align-items: center;
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
}
.transcript {
  flex: 1;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.turn .user {
  white-space: pre-wrap;
  color: var(--vscode-descriptionForeground);
}
.turn .agent {
  white-space: normal;
}
.thought {
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
}
.thought summary {
  cursor: pointer;
  list-style: disclosure-closed;
  user-select: none;
}
.thought[open] summary {
  list-style: disclosure-open;
}
.thought-stream {
  white-space: pre-wrap;
  margin: 6px 0 0;
  padding: 8px;
  font-family: var(--vscode-editor-font-family);
  font-size: var(--vscode-editor-font-size);
  background: var(--vscode-editor-background);
  border-left: 2px solid var(--vscode-widget-border, var(--vscode-focusBorder));
  overflow: auto;
  max-height: 50vh;
}
.tools {
  margin: 0;
  padding-left: 18px;
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
}
.card {
  border: 1px solid var(--vscode-widget-border, var(--vscode-focusBorder));
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.card pre {
  white-space: pre-wrap;
  margin: 0;
}
.composer {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.chip {
  font-size: 12px;
  padding: 2px 6px;
  background: var(--vscode-badge-background);
  color: var(--vscode-badge-foreground);
}
textarea {
  width: 100%;
  min-height: 64px;
  resize: vertical;
  box-sizing: border-box;
  background: var(--vscode-input-background);
  color: var(--vscode-input-foreground);
  border: 1px solid var(--vscode-input-border, transparent);
  padding: 6px;
  font-family: inherit;
}
button {
  background: var(--vscode-button-background);
  color: var(--vscode-button-foreground);
  border: none;
  padding: 4px 8px;
  cursor: pointer;
}
.slash, .mentions {
  list-style: none;
  margin: 0;
  padding: 4px;
  background: var(--vscode-editorSuggestWidget-background, var(--vscode-editor-background));
  border: 1px solid var(--vscode-widget-border, var(--vscode-focusBorder));
}
.slash li, .mentions li {
  padding: 4px;
  cursor: pointer;
}
.error {
  color: var(--vscode-errorForeground);
}
`

export const chatHtml = (input: {
  readonly cspSource: string
  readonly scriptUri: string
  readonly ctrlEnterToSend: boolean
}): string => {
  const csp = [
    "default-src 'none'",
    `script-src ${input.cspSource}`,
    `style-src ${input.cspSource} 'unsafe-inline'`,
    `img-src ${input.cspSource} data:`,
    "connect-src 'none'",
  ].join("; ")
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="${csp}" />
  <style>${chatCss}</style>
</head>
<body data-ctrl-enter="${input.ctrlEnterToSend ? "true" : "false"}">
  <div id="root"></div>
  <script src="${input.scriptUri}"></script>
</body>
</html>`
}
