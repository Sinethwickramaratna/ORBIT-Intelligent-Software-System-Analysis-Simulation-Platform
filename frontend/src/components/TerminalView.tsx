"use client";

import { useEffect, useRef, useState } from "react";
import type { Terminal as XTerm } from "@xterm/xterm";
import type { FitAddon } from "@xterm/addon-fit";
import { api, ApiError } from "@/lib/api";
import { helperStartHint, terminalSocketUrl, TERMINAL_URL } from "@/lib/terminal";

/** Terminal colours from the app theme (CSS variables), so the terminal matches the rest of ORBIT. */
function themeFromCss() {
  const css = getComputedStyle(document.documentElement);
  const v = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
  const bg = v("--bg", "#14101a");
  return {
    background: bg,
    foreground: v("--text", "#e8e0f0"),
    cursor: v("--accent", "#ffaf39"),
    cursorAccent: bg,
    selectionBackground: "#ffaf3955",
  };
}

export type TerminalState = "connecting" | "ready" | "exited" | "error";

interface Props {
  projectId: string;
  shellId: string;
  /** Shown while the panel tab / group is visible; hidden terminals keep running. */
  visible: boolean;
  focused: boolean;
  onFocus: () => void;
  onState?: (state: TerminalState, detail?: string) => void;
}

/**
 * One terminal of the bottom panel: xterm.js (the terminal component of VS Code) connected over a WebSocket to a
 * shell that the ORBIT terminal helper runs on this computer, in the project folder.
 */
export default function TerminalView({ projectId, shellId, visible, focused, onFocus, onState }: Props) {
  const hostRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<XTerm | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const [error, setError] = useState<string | null>(null);
  const stateRef = useRef(onState);
  stateRef.current = onState;

  useEffect(() => {
    let disposed = false;
    let ro: ResizeObserver | null = null;
    let mo: MutationObserver | null = null;
    const fail = (message: string) => {
      if (disposed) return;
      setError(message);
      stateRef.current?.("error", message);
    };

    (async () => {
      // xterm touches `window` when imported, so it is loaded in the browser only
      const [{ Terminal }, { FitAddon }] = await Promise.all([import("@xterm/xterm"), import("@xterm/addon-fit")]);
      if (disposed || !hostRef.current) return;
      const term = new Terminal({
        fontFamily: '"Source Code Pro", ui-monospace, "Cascadia Code", Consolas, monospace',
        fontSize: 13,
        lineHeight: 1.15,
        cursorBlink: true,
        scrollback: 5000,
        theme: themeFromCss(),
        allowProposedApi: false,
      });
      const fit = new FitAddon();
      term.loadAddon(fit);
      term.open(hostRef.current);
      termRef.current = term;
      fitRef.current = fit;
      try {
        fit.fit();
      } catch {
        /* not laid out yet */
      }
      // keys the app needs even while the terminal has the focus: Ctrl+` (panel), Ctrl+Shift+` (new terminal)
      term.attachCustomKeyEventHandler((e) => !((e.ctrlKey || e.metaKey) && (e.key === "`" || e.code === "Backquote")));

      mo = new MutationObserver(() => (term.options.theme = themeFromCss()));
      mo.observe(document.documentElement, { attributes: true, attributeFilter: ["data-theme"] });

      let ticket: string;
      try {
        ticket = (await api.terminalTicket(projectId)).ticket;
      } catch (e) {
        fail(e instanceof ApiError ? e.message : "No terminal ticket could be obtained from the ORBIT backend.");
        return;
      }
      if (disposed) return;

      const ws = new WebSocket(terminalSocketUrl(ticket, shellId, term.cols, term.rows));
      ws.binaryType = "arraybuffer";
      wsRef.current = ws;
      let opened = false;
      let exited = false;
      const send = (msg: object) => ws.readyState === WebSocket.OPEN && ws.send(JSON.stringify(msg));
      ws.onopen = () => {
        opened = true;
      };
      ws.onmessage = (ev) => {
        if (typeof ev.data !== "string") {
          term.write(new Uint8Array(ev.data as ArrayBuffer));
          return;
        }
        try {
          const msg = JSON.parse(ev.data) as { type: string; code?: number; cwd?: string };
          if (msg.type === "ready") stateRef.current?.("ready");
          if (msg.type === "exit") {
            exited = true;
            term.write(`\r\n\x1b[90m[The process exited with code ${msg.code ?? 0}. Close this terminal or open a new one.]\x1b[0m\r\n`);
            stateRef.current?.("exited");
          }
        } catch {
          /* not a control message */
        }
      };
      ws.onclose = (ev) => {
        if (disposed || exited) return;
        if (!opened) {
          fail(`The ORBIT terminal helper does not answer at ${TERMINAL_URL}. Start it with ${helperStartHint()} (start.cmd / start.sh do this for you), then open a new terminal.`);
        } else {
          term.write(`\r\n\x1b[31m[Connection to the terminal helper closed${ev.reason ? ": " + ev.reason : ""}.]\x1b[0m\r\n`);
          stateRef.current?.("exited");
        }
      };
      term.onData((data) => send({ type: "input", data }));
      term.onResize(({ cols, rows }) => send({ type: "resize", cols, rows }));

      ro = new ResizeObserver(() => {
        if (!hostRef.current || hostRef.current.offsetWidth === 0) return; // hidden
        try {
          fit.fit();
        } catch {
          /* ignore */
        }
      });
      ro.observe(hostRef.current);
    })().catch((e: unknown) => fail(e instanceof Error ? e.message : "The terminal could not be started."));

    return () => {
      disposed = true;
      ro?.disconnect();
      mo?.disconnect();
      wsRef.current?.close(); // the helper then ends the shell and everything started in it
      wsRef.current = null;
      termRef.current?.dispose();
      termRef.current = null;
    };
  }, [projectId, shellId]);

  // shown again (panel tab / group switched): size it to its box
  useEffect(() => {
    if (!visible) return;
    const id = requestAnimationFrame(() => {
      try {
        fitRef.current?.fit();
      } catch {
        /* ignore */
      }
    });
    return () => cancelAnimationFrame(id);
  }, [visible]);

  useEffect(() => {
    if (focused && visible) termRef.current?.focus();
  }, [focused, visible]);

  return (
    <div className={`terminal-view${focused ? " focused" : ""}`} onMouseDown={onFocus} onFocusCapture={onFocus}>
      {error ? (
        <div className="terminal-error" role="alert">
          {error}
        </div>
      ) : null}
      <div ref={hostRef} className="terminal-host" hidden={!!error} />
    </div>
  );
}
