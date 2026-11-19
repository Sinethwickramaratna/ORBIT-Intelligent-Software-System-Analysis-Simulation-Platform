/**
 * Client of the ORBIT terminal helper - the small program on the user's computer that runs the terminals
 * (terminal-agent/, started by start.cmd / start.sh or scripts/orbit-terminal.*). The browser talks to it directly on
 * localhost; every terminal needs a one-time ticket from the backend (api.terminalTicket).
 */
export const TERMINAL_URL = (process.env.NEXT_PUBLIC_TERMINAL_URL ?? "http://localhost:8091").replace(/\/+$/, "");

/** One terminal type installed on this computer (like a VS Code terminal profile). */
export interface ShellInfo {
  id: string;
  name: string;
  path: string;
}

export interface ShellList {
  default: string;
  shells: ShellInfo[];
}

/** Thrown when the helper does not answer (not started, or a different port). */
export class HelperUnavailableError extends Error {}

export async function fetchShells(): Promise<ShellList> {
  let res: Response;
  try {
    res = await fetch(`${TERMINAL_URL}/shells`, { cache: "no-store" });
  } catch {
    throw new HelperUnavailableError(`The ORBIT terminal helper does not answer at ${TERMINAL_URL}.`);
  }
  if (!res.ok) throw new HelperUnavailableError(`The ORBIT terminal helper refused the request (${res.status}).`);
  const list = (await res.json()) as ShellList;
  return { default: list.default, shells: list.shells ?? [] };
}

export function terminalSocketUrl(ticket: string, shell: string, cols: number, rows: number): string {
  const url = new URL(`${TERMINAL_URL}/terminal`);
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.searchParams.set("ticket", ticket);
  url.searchParams.set("shell", shell);
  url.searchParams.set("cols", String(cols));
  url.searchParams.set("rows", String(rows));
  return url.toString();
}

/** How to start the helper, for the message shown when it does not answer. */
export function helperStartHint(): string {
  const windows = typeof navigator !== "undefined" && /Win/i.test(navigator.platform || navigator.userAgent);
  return windows ? "scripts\\orbit-terminal.cmd start" : "./scripts/orbit-terminal.sh start";
}
