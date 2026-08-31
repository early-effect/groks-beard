import { BEARD_THEME_CSS } from "./beard-theme.js"

export const chatCss = `
${BEARD_THEME_CSS.trim()}
:root {
  color: var(--vscode-foreground);
  background: var(--vscode-sideBar-background);
  font-family: var(--vscode-font-family);
  font-size: var(--vscode-font-size);
  accent-color: var(--beard-orange);
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
}
.transcript {
  flex: 1;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 12px 14px 8px;
}
.empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  padding-top: 48px;
  gap: 12px;
  user-select: none;
  animation: fade-in 0.25s ease-out;
}
@keyframes fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
.empty-logo {
  width: 132px;
  height: 132px;
  object-fit: contain;
  display: block;
  flex-shrink: 0;
  pointer-events: none;
}
.empty-title {
  margin: 8px 0 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.01em;
}
.empty-copy {
  margin: 0;
  color: var(--vscode-descriptionForeground);
  font-size: 13px;
}
.turn {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.turn .user {
  align-self: flex-end;
  max-width: 95%;
  white-space: pre-wrap;
  background: var(--vscode-editor-inactiveSelectionBackground, rgba(127, 127, 127, 0.14));
  color: var(--vscode-foreground);
  border-radius: 10px;
  padding: 8px 10px;
  line-height: 1.45;
}
.turn .agent {
  white-space: normal;
  line-height: 1.5;
}
.turn .agent p {
  margin: 0 0 8px;
}
.turn .agent p:last-child {
  margin-bottom: 0;
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
  border-left: 2px solid var(--beard-brown);
  overflow: auto;
  max-height: 50vh;
}
.tools {
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tool-rollup {
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
}
.tool-rollup summary {
  cursor: pointer;
  list-style-position: outside;
}
.tool-rollup-list {
  margin: 4px 0 6px 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tool {
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
}
.tool summary {
  cursor: pointer;
  list-style-position: outside;
}
.tool-body {
  margin: 4px 0 8px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tool-payload h4 {
  margin: 0 0 4px;
  font-size: 11px;
  font-weight: 600;
  color: var(--vscode-foreground);
}
.tool-stream {
  margin: 0;
  max-height: 220px;
  overflow: auto;
  padding: 8px;
  border-radius: 4px;
  background: var(--vscode-editor-background);
  font-family: var(--vscode-editor-font-family);
  font-size: 11px;
  white-space: pre-wrap;
  word-break: break-word;
}
.tool-more {
  appearance: none;
  align-self: flex-start;
  background: transparent;
  border: none;
  color: var(--beard-copper);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  padding: 2px 0;
}
.tool-empty {
  margin: 0;
  opacity: 0.8;
}
.stop {
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
}
.cards {
  padding: 0 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.cards:empty {
  display: none;
}
.card {
  border: 1px solid var(--vscode-widget-border, var(--beard-brown));
  background: var(--vscode-editor-background);
  border-radius: 8px;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.card h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.card pre {
  white-space: pre-wrap;
  margin: 0;
}
.card.plan pre {
  max-height: 28vh;
  overflow: auto;
}
.card button,
.card button.plan-approve {
  appearance: none;
  background: var(--beard-orange);
  color: var(--beard-cream);
  border: none;
  border-radius: 4px;
  padding: 5px 10px;
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.card button.secondary,
.card button.plan-revise,
.card button.plan-abandon {
  background: var(--beard-black);
  color: var(--beard-cream);
  border: 1px solid var(--beard-brown);
}
.card button.plan-open {
  background: transparent;
  color: var(--beard-copper);
  border: none;
  padding: 2px 0;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.card button:hover {
  filter: brightness(1.08);
}
.card button.plan-open:hover {
  color: var(--beard-orange);
  filter: none;
}
.plan-open-link {
  appearance: none;
  background: transparent;
  border: none;
  color: var(--beard-copper);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  padding: 2px 4px;
  text-decoration: underline;
  text-underline-offset: 2px;
}
.plan-open-link:hover {
  color: var(--beard-orange);
}
.status {
  padding: 0 14px 6px;
}
.status:empty {
  display: none;
}
.dock {
  position: relative;
  padding: 4px 10px 12px;
}
.toast {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  padding: 6px 8px 6px 10px;
  border-radius: 10px;
  background: var(--vscode-notifications-background, var(--vscode-editorWidget-background));
  border: 1px solid var(--vscode-widget-border, var(--vscode-notifications-border, transparent));
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.18);
}
.toast[hidden] {
  display: none;
}
.toast-body {
  appearance: none;
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  margin: 0;
  padding: 2px 4px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.toast-body:hover {
  background: var(--vscode-toolbar-hoverBackground, rgba(127, 127, 127, 0.16));
}
.toast-count {
  font-weight: 600;
  font-size: 12px;
}
.toast-stats {
  display: inline-flex;
  gap: 4px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.toast-add, .stats-add {
  color: var(--vscode-gitDecoration-addedResourceForeground, #3fb950);
}
.toast-del, .stats-del {
  color: var(--vscode-gitDecoration-deletedResourceForeground, #f85149);
}
.toast-keep, .toast-commit {
  appearance: none;
  flex-shrink: 0;
  border-radius: 6px;
  padding: 4px 8px;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
.toast-keep {
  border: none;
  background: var(--beard-orange);
  color: var(--beard-cream);
}
.toast-commit {
  background: var(--vscode-button-secondaryBackground, transparent);
  color: var(--vscode-button-secondaryForeground, var(--vscode-foreground));
  border: 1px solid var(--vscode-widget-border, transparent);
}
.toast-keep:hover, .toast-commit:hover {
  filter: brightness(1.08);
}
.composer {
  display: flex;
  flex-direction: column;
  background: var(--vscode-input-background);
  border: 1px solid var(--vscode-widget-border, var(--vscode-input-border, transparent));
  border-radius: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.12);
}
.composer:focus-within {
  border-color: var(--beard-orange);
  box-shadow: 0 0 0 1px var(--beard-orange);
}
.composer[data-mode="plan"]:focus-within {
  border-color: var(--beard-brown);
  box-shadow: 0 0 0 1px var(--beard-brown);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  padding: 8px 10px 0;
}
.chips[hidden] {
  display: none;
}
.chip {
  display: inline-flex;
  align-items: center;
  gap: 1px;
  max-width: 100%;
  border: 1px solid var(--beard-brown);
  border-radius: 999px;
  background: var(--beard-cream);
  color: var(--beard-black);
  font-size: 11px;
  line-height: 1.2;
  padding: 0 2px 0 8px;
}
.chip-open,
.chip-remove {
  appearance: none;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font: inherit;
  font-size: 11px;
}
.chip-open {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 3px 4px 3px 0;
  text-align: left;
}
.chip-remove {
  flex-shrink: 0;
  padding: 2px 6px 2px 4px;
  opacity: 0.7;
  line-height: 1;
}
.chip-open:hover,
.chip-remove:hover {
  color: var(--beard-brown);
}
.chip-remove:hover {
  opacity: 1;
}
textarea {
  width: 100%;
  min-height: 40px;
  max-height: 160px;
  resize: none;
  overflow-y: auto;
  box-sizing: border-box;
  background: transparent;
  color: var(--vscode-input-foreground);
  border: none;
  outline: none;
  padding: 10px 12px 4px;
  font-family: inherit;
  font-size: inherit;
  line-height: 1.45;
}
.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 2px 6px 6px;
}
.composer-left,
.composer-right {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}
.composer-left {
  flex: 1;
  gap: 6px;
}
.composer-context {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: 0 8px 2px;
}
.composer-context[hidden] {
  display: none;
}
.editor-context {
  appearance: none;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 2px 4px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--vscode-foreground);
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.editor-context .chip-copy {
  display: flex;
  flex-direction: row;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  width: 100%;
  line-height: 1.3;
}
.editor-context .chip-kind {
  flex-shrink: 0;
  width: 5.6em;
}
.editor-path {
  min-width: 0;
  font-size: 12px;
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}
.editor-context:hover {
  background: var(--vscode-toolbar-hoverBackground, rgba(127, 127, 127, 0.16));
}
.editor-context.add-selection {
  align-items: center;
  margin: 0 2px 4px;
  padding: 6px 10px;
  border: 1px solid var(--beard-orange);
  border-radius: 8px;
  background: var(--beard-cream);
  color: var(--beard-black);
}
.editor-context.add-selection .chip-kind {
  width: auto;
  color: var(--beard-orange);
  font-weight: 700;
  letter-spacing: 0.08em;
}
.editor-context.add-selection .editor-path {
  font-weight: 600;
}
.editor-context.add-selection:hover {
  background: var(--beard-cream);
  border-color: var(--beard-copper);
  color: var(--beard-black);
}
.composer-right {
  justify-content: flex-end;
  flex: 1;
  gap: 4px;
}
.occupancy {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  color: var(--vscode-descriptionForeground);
  font-size: 11px;
  padding: 0 4px;
}
.occupancy-track {
  display: inline-block;
  width: 36px;
  height: 4px;
  border-radius: 2px;
  background: var(--vscode-widget-border, rgba(127, 127, 127, 0.35));
  overflow: hidden;
  flex-shrink: 0;
}
.occupancy-fill {
  display: block;
  height: 100%;
  background: var(--beard-brown);
}
.occupancy-warn .occupancy-fill {
  background: var(--beard-orange);
}
.occupancy-hot .occupancy-fill {
  background: var(--beard-copper);
}
.occupancy-copy {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mode-chip {
  appearance: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  max-width: 196px;
  min-height: 28px;
  height: auto;
  padding: 2px 8px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--vscode-descriptionForeground);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
}
.chip-copy {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-width: 0;
  line-height: 1.15;
}
.chip-kind {
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--beard-copper);
}
.mode-chip .label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
  color: var(--vscode-foreground);
}
.mode-chip svg {
  flex-shrink: 0;
  opacity: 0.8;
}
.mode-chip:hover,
.mode-chip[aria-expanded="true"] {
  background: var(--vscode-toolbar-hoverBackground, rgba(127, 127, 127, 0.16));
  color: var(--vscode-foreground);
}
.tools-warn {
  color: var(--vscode-list-warningForeground, var(--vscode-editorWarning-foreground));
}
.icon-btn[aria-expanded="true"] {
  background: var(--vscode-toolbar-hoverBackground, rgba(127, 127, 127, 0.16));
  color: var(--vscode-foreground);
}
.settings-panel {
  margin-right: 0;
  margin-left: auto;
  width: min(100%, 360px);
  max-height: 46vh;
  overflow: auto;
  padding: 8px;
  background: var(--vscode-editorSuggestWidget-background, var(--vscode-editor-background));
  border: 1px solid var(--vscode-editorSuggestWidget-border, var(--vscode-widget-border, var(--beard-brown)));
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.22);
}
.json-btn {
  appearance: none;
  background: transparent;
  border: 1px solid var(--vscode-widget-border, transparent);
  border-radius: 4px;
  color: var(--vscode-descriptionForeground);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  font-family: var(--vscode-editor-font-family, monospace);
  padding: 1px 6px;
}
.json-btn:hover {
  color: var(--vscode-foreground);
  background: var(--vscode-toolbar-hoverBackground, rgba(127, 127, 127, 0.16));
}
.settings-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--vscode-widget-border, transparent);
}
.setting {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.setting-check {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}
.setting input[type="text"], .setting select {
  width: 100%;
  box-sizing: border-box;
  background: var(--vscode-input-background);
  color: var(--vscode-input-foreground);
  border: 1px solid var(--vscode-input-border, var(--vscode-widget-border, transparent));
  border-radius: 4px;
  padding: 4px 6px;
  font: inherit;
}
.setting input[type="checkbox"] {
  margin: 0;
}
.tools-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.tools-title {
  flex: 1;
  font-weight: 600;
  font-size: 13px;
}
.tools-link, .tools-toggle {
  appearance: none;
  background: transparent;
  border: none;
  color: var(--beard-copper);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  padding: 2px 4px;
}
.tools-toggle {
  color: var(--vscode-foreground);
  border: 1px solid var(--vscode-widget-border, transparent);
  border-radius: 4px;
  padding: 2px 8px;
}
.tools-note, .tools-status {
  margin: 0 0 8px;
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
  line-height: 1.4;
}
.tools-error {
  margin: 0 0 8px;
  color: var(--vscode-errorForeground);
  font-size: 12px;
}
.mcp-trust {
  margin: 0 0 8px;
  padding: 8px;
  border: 1px solid var(--vscode-inputValidation-warningBorder, var(--vscode-widget-border, transparent));
  border-radius: 6px;
  background: var(--vscode-inputValidation-warningBackground, transparent);
}
.mcp-trust p {
  margin: 0 0 8px;
  color: var(--vscode-foreground);
  font-size: 12px;
  line-height: 1.4;
}
.mcp-trust-btn {
  appearance: none;
  background: var(--beard-orange);
  color: var(--beard-cream);
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  padding: 4px 10px;
}
.tools-servers {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.mcp-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.mcp-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
}
.mcp-dot {
  width: 8px;
  height: 8px;
  margin-top: 5px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--vscode-testing-iconFailed, var(--vscode-errorForeground));
}
.mcp-ok .mcp-dot {
  background: var(--vscode-testing-iconPassed, var(--vscode-charts-green));
}
.mcp-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.mcp-name {
  font-weight: 600;
  font-size: 12px;
}
.mcp-meta, .mcp-checks {
  color: var(--vscode-descriptionForeground);
  font-size: 11px;
}
.mcp-checks {
  list-style: none;
  margin: 4px 0 0 16px;
  padding: 0;
}
.check-bad {
  color: var(--vscode-errorForeground);
}
.mcp-tools {
  margin: 6px 0 0 16px;
}
.mcp-tools summary {
  cursor: pointer;
  color: var(--vscode-descriptionForeground);
  font-size: 11px;
}
.mcp-tool-list {
  list-style: none;
  margin: 4px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 220px;
  overflow: auto;
}
.mcp-tool {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 11px;
  cursor: pointer;
  color: var(--vscode-foreground);
}
.mcp-tool input {
  margin: 2px 0 0;
  flex-shrink: 0;
}
.mcp-tool-off .mcp-tool-name {
  color: var(--vscode-descriptionForeground);
  text-decoration: line-through;
}
.icon-btn,
.send-btn {
  appearance: none;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: none;
  cursor: pointer;
  flex-shrink: 0;
  color: var(--vscode-descriptionForeground);
  background: transparent;
}
.icon-btn {
  border-radius: 50%;
}
.icon-btn:hover {
  background: var(--vscode-toolbar-hoverBackground, rgba(127, 127, 127, 0.16));
  color: var(--vscode-foreground);
}
.send-btn {
  border-radius: 6px;
  background: var(--beard-orange);
  color: var(--beard-cream);
}
.send-btn:hover:not(:disabled) {
  background: var(--beard-copper);
}
.send-btn:disabled {
  opacity: 0.4;
  cursor: default;
}
.stop-btn {
  background: var(--beard-black);
  color: var(--beard-cream);
  border: 1px solid var(--beard-brown);
}
.stop-btn:hover:not(:disabled) {
  background: var(--beard-brown);
}
.stop-btn svg {
  overflow: hidden;
  border-radius: 2px;
}
.popovers {
  position: absolute;
  left: 10px;
  right: 10px;
  bottom: 100%;
  margin-bottom: 6px;
  z-index: 5;
}
.slash, .mentions, .menu {
  list-style: none;
  margin: 0;
  padding: 4px;
  background: var(--vscode-editorSuggestWidget-background, var(--vscode-editor-background));
  border: 1px solid var(--vscode-editorSuggestWidget-border, var(--vscode-widget-border, var(--beard-brown)));
  border-radius: 8px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.22);
  max-height: 220px;
  overflow: auto;
}
.menu {
  margin-left: auto;
  width: max-content;
  min-width: 148px;
  max-width: 220px;
}
.menu.model-menu {
  min-width: 220px;
  max-width: 280px;
}
.slash li, .mentions li, .menu li {
  padding: 6px 8px;
  cursor: pointer;
  border-radius: 6px;
  font-size: 12px;
}
.mentions-empty {
  cursor: default;
  color: var(--vscode-descriptionForeground);
}
.slash li:hover, .mentions li:hover, .menu li:hover,
.mentions li.selected, .menu li.selected {
  background: var(--vscode-list-hoverBackground);
}
.mentions-empty:hover {
  background: transparent;
}
.menu li.model-item {
  padding: 2px;
  cursor: default;
}
.menu li.model-item:hover,
.menu li.model-item.selected {
  background: transparent;
}
.model-row {
  display: flex;
  align-items: center;
  gap: 2px;
  min-width: 0;
}
.model-pick {
  appearance: none;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: inherit;
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  padding: 6px 8px;
}
.model-pick:hover,
.model-item.selected .model-pick {
  background: var(--vscode-list-hoverBackground);
}
.model-effort {
  appearance: none;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  background: transparent;
  border: none;
  border-radius: 6px;
  color: var(--beard-copper);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
  padding: 4px 6px;
}
.model-effort:hover,
.model-effort[aria-expanded="true"] {
  background: var(--vscode-toolbar-hoverBackground, rgba(127, 127, 127, 0.16));
  color: var(--vscode-foreground);
}
.menu-sub {
  list-style: none;
  margin: 0;
  padding: 0 4px 4px 14px;
}
.menu-sub li {
  font-size: 11px;
  padding: 4px 8px;
}
.error {
  color: var(--vscode-errorForeground);
  background: color-mix(in srgb, var(--vscode-errorForeground) 8%, transparent);
  border-radius: 6px;
  padding: 6px 8px;
  font-size: 12px;
}
.queued {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  color: var(--vscode-descriptionForeground);
  font-size: 12px;
}
.queued-now {
  appearance: none;
  background: transparent;
  border: 1px solid var(--beard-brown);
  border-radius: 4px;
  color: var(--beard-copper);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  padding: 2px 8px;
}
`

const attr = (value: string): string =>
  value.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;")

export const chatHtml = (input: {
  readonly cspSource: string
  readonly scriptUri: string
  readonly logoUri?: string
  readonly ctrlEnterToSend: boolean
}): string => {
  const csp = [
    "default-src 'none'",
    `script-src ${input.cspSource}`,
    `style-src ${input.cspSource} 'unsafe-inline'`,
    `img-src ${input.cspSource} data:`,
    "connect-src 'none'",
  ].join("; ")
  const logo = input.logoUri !== undefined && input.logoUri !== ""
    ? ` data-logo="${attr(input.logoUri)}"`
    : ""
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta http-equiv="Content-Security-Policy" content="${csp}" />
  <style>${chatCss}</style>
</head>
<body data-ctrl-enter="${input.ctrlEnterToSend ? "true" : "false"}"${logo}>
  <div id="root"></div>
  <script src="${attr(input.scriptUri)}"></script>
</body>
</html>`
}
