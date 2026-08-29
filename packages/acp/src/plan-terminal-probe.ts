/**
 * Open question 5: on live Grok, in Plan mode, does the agent send mutating
 * `terminal/create` to the client?
 *
 * Live CLI: `grok 1.0.13 (5e9a58528b76) [stable]`. A 25s stdio probe
 * (initialize with `terminal: true`, `session/set_mode` plan, then a prompt
 * asking for `rm`) returned no `terminal/create` before the prompt result.
 * Treat as unverified and keep the handler allowlist armed while `planActive`.
 */
export const PLAN_TERMINAL_MUTATION_DELEGATED: boolean | "unverified" = "unverified"

export const planTerminalAllowlistArmed = (
  delegated: boolean | "unverified" = PLAN_TERMINAL_MUTATION_DELEGATED,
): boolean => delegated !== false
