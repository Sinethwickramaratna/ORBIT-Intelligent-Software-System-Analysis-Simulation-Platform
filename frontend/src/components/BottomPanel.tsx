"use client";

import { Fragment, useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { fetchShells, helperStartHint, HelperUnavailableError, TERMINAL_URL, type ShellInfo } from "@/lib/terminal";
import TerminalView, { type TerminalState } from "./TerminalView";
import TreeContextMenu, { type MenuEntry } from "./TreeContextMenu";

export type PanelTab = "problems" | "terminal";

export interface Problem {
  path: string;
  message: string;
  severity: "error" | "warning" | "info";
  line: number;
  column: number;
  source?: string;
}

interface Term {
  id: number;
  shellId: string;
  shellName: string;
  state: TerminalState;
}
interface Group {
  id: number;
  terms: Term[];
}

const MAX_SPLIT = 4;

// ---- icons (VS Code codicon look) ----
const Svg = ({ children, size = 16 }: { children: ReactNode; size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {children}
  </svg>
);
const PlusIcon = () => <Svg><path d="M12 5v14M5 12h14" /></Svg>;
const ChevronDown = () => <Svg size={12}><path d="m6 9 6 6 6-6" /></Svg>;
const SplitIcon = () => <Svg><rect x="3" y="4" width="18" height="16" rx="1.5" /><path d="M12 4v16" /></Svg>;
const TrashIcon = () => <Svg><path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" /></Svg>;
const CloseIcon = () => <Svg><path d="M6 6l12 12M18 6 6 18" /></Svg>;
const TermIcon = () => <Svg size={14}><path d="m5 8 4 4-4 4M12 17h7" /></Svg>;
export const ErrorIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" className="sev-icon error">
    <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
    <path d="M9 9l6 6M15 9l-6 6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
  </svg>
);
export const WarningIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" className="sev-icon warning">
    <path d="M12 3 2 20h20Z" fill="none" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
    <path d="M12 10v4M12 17v.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
  </svg>
);
const InfoIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" aria-hidden="true" className="sev-icon info">
    <circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" />
    <path d="M12 11v6M12 7.5v.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
  </svg>
);

interface Props {
  projectId: string;
  tab: PanelTab;
  onTab: (tab: PanelTab) => void;
  onClose: () => void;
  problems: Problem[];
  onOpenProblem: (p: Problem) => void;
  /** Goes up when the app asks for a new terminal (Ctrl+Shift+`, or opening the Terminal panel when none is open). */
  newTerminalRequest: number;
  /** Tells the app how many terminals are open. */
  onTerminalCount?: (count: number) => void;
  /** The panel is shown (it stays mounted while hidden, so its terminals keep running). */
  visible: boolean;
}

/** The bottom panel of the project view (like VS Code's): Problems and Terminal. */
export default function BottomPanel({ projectId, tab, onTab, onClose, problems, onOpenProblem, newTerminalRequest, onTerminalCount, visible }: Props) {
  const [shells, setShells] = useState<ShellInfo[] | null>(null);
  const [defaultShell, setDefaultShell] = useState<string | null>(null);
  const [helperError, setHelperError] = useState<string | null>(null);
  const [groups, setGroups] = useState<Group[]>([]);
  const [activeGroup, setActiveGroup] = useState<number | null>(null);
  const [activeTerm, setActiveTerm] = useState<number | null>(null);
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null);
  const nextId = useRef(0);
  const groupsRef = useRef(groups);
  groupsRef.current = groups;

  const loadShells = useCallback(async (): Promise<{ list: ShellInfo[]; def: string } | null> => {
    try {
      const res = await fetchShells();
      setShells(res.shells);
      setDefaultShell(res.default);
      setHelperError(res.shells.length ? null : "The terminal helper found no shell on this computer.");
      return { list: res.shells, def: res.default };
    } catch (e) {
      setShells(null);
      setHelperError(e instanceof HelperUnavailableError ? e.message : "The terminal helper could not be reached.");
      return null;
    }
  }, []);

  const newTerminal = useCallback(
    async (shellId?: string) => {
      let list = shells;
      let def = defaultShell;
      if (!list) {
        const loaded = await loadShells();
        if (!loaded) return;
        list = loaded.list;
        def = loaded.def;
      }
      const shell = list.find((s) => s.id === (shellId ?? def)) ?? list[0];
      if (!shell) return;
      const term: Term = { id: ++nextId.current, shellId: shell.id, shellName: shell.name, state: "connecting" };
      const group: Group = { id: ++nextId.current, terms: [term] };
      setGroups((gs) => [...gs, group]);
      setActiveGroup(group.id);
      setActiveTerm(term.id);
      onTab("terminal");
    },
    [shells, defaultShell, loadShells, onTab],
  );

  /** Adds a terminal next to the active one, in the same tab (VS Code: Split Terminal). */
  const split = useCallback(
    (groupId?: number) => {
      const gid = groupId ?? activeGroup;
      const group = groupsRef.current.find((g) => g.id === gid);
      if (!group) {
        void newTerminal();
        return;
      }
      if (group.terms.length >= MAX_SPLIT) return;
      const base = group.terms.find((t) => t.id === activeTerm) ?? group.terms[group.terms.length - 1];
      const term: Term = { id: ++nextId.current, shellId: base.shellId, shellName: base.shellName, state: "connecting" };
      setGroups((gs) => gs.map((g) => (g.id === group.id ? { ...g, terms: [...g.terms, term] } : g)));
      setActiveGroup(group.id);
      setActiveTerm(term.id);
    },
    [activeGroup, activeTerm, newTerminal],
  );

  /** Closes a terminal: the helper ends its shell and everything started in it. */
  const kill = useCallback(
    (termId: number) => {
      const gs = groupsRef.current;
      const group = gs.find((g) => g.terms.some((t) => t.id === termId));
      if (!group) return;
      const restTerms = group.terms.filter((t) => t.id !== termId);
      const nextGroups = restTerms.length ? gs.map((g) => (g.id === group.id ? { ...g, terms: restTerms } : g)) : gs.filter((g) => g.id !== group.id);
      setGroups(nextGroups);
      if (activeTerm === termId || !nextGroups.some((g) => g.id === activeGroup)) {
        const g = restTerms.length ? { ...group, terms: restTerms } : nextGroups[Math.max(0, gs.indexOf(group) - 1)] ?? nextGroups[0];
        setActiveGroup(g?.id ?? null);
        setActiveTerm(g ? g.terms[g.terms.length - 1].id : null);
      }
    },
    [activeTerm, activeGroup],
  );

  const setTermState = useCallback((termId: number, state: TerminalState) => {
    setGroups((gs) => gs.map((g) => ({ ...g, terms: g.terms.map((t) => (t.id === termId ? { ...t, state } : t)) })));
  }, []);

  // the app asked for a terminal
  const lastRequest = useRef(newTerminalRequest);
  useEffect(() => {
    if (newTerminalRequest === lastRequest.current) return;
    lastRequest.current = newTerminalRequest;
    void newTerminal();
  }, [newTerminalRequest, newTerminal]);

  // the shell list is fetched when the Terminal tab is first shown (not before: the helper may not even run)
  useEffect(() => {
    if (visible && tab === "terminal" && shells === null && !helperError) void loadShells();
  }, [visible, tab, shells, helperError, loadShells]);

  const total = groups.reduce((n, g) => n + g.terms.length, 0);
  useEffect(() => {
    onTerminalCount?.(total);
  }, [total, onTerminalCount]);
  const errors = problems.filter((p) => p.severity === "error").length;
  const warnings = problems.filter((p) => p.severity === "warning").length;

  const menuItems: MenuEntry[] = [
    ...(shells ?? []).map((s) => ({
      label: s.name + (s.id === defaultShell ? " (default)" : ""),
      icon: <TermIcon />,
      onSelect: () => void newTerminal(s.id),
    })),
    null,
    { label: "Split Terminal", icon: <SplitIcon />, onSelect: () => split() },
  ];

  // problems grouped by file
  const byFile = new Map<string, Problem[]>();
  for (const p of problems) byFile.set(p.path, [...(byFile.get(p.path) ?? []), p]);

  return (
    <section className="bottom-panel" aria-label="Panel">
      <div className="panel-head">
        <div className="panel-tabs" role="tablist" aria-label="Panel">
          <button type="button" role="tab" aria-selected={tab === "problems"} className={`panel-tab${tab === "problems" ? " active" : ""}`} onClick={() => onTab("problems")}>
            Problems
            {problems.length > 0 && <span className="panel-badge">{problems.length}</span>}
          </button>
          <button type="button" role="tab" aria-selected={tab === "terminal"} className={`panel-tab${tab === "terminal" ? " active" : ""}`} onClick={() => onTab("terminal")}>
            Terminal
          </button>
        </div>
        <div className="panel-actions">
          {tab === "terminal" && (
            <>
              <span className="panel-split-btn">
                <button type="button" className="panel-action" aria-label="New Terminal" title="New Terminal (Ctrl+Shift+`)" onClick={() => void newTerminal()}>
                  <PlusIcon />
                </button>
                <button
                  type="button"
                  className="panel-action narrow"
                  aria-label="Launch Profile..."
                  title="Choose the terminal type"
                  aria-haspopup="menu"
                  onClick={async (e) => {
                    const r = e.currentTarget.getBoundingClientRect();
                    if (!shells) await loadShells();
                    setMenu({ x: r.left, y: r.bottom + 2 });
                  }}
                >
                  <ChevronDown />
                </button>
              </span>
              <button type="button" className="panel-action" aria-label="Split Terminal" title="Split Terminal" disabled={!activeGroup} onClick={() => split()}>
                <SplitIcon />
              </button>
              <button type="button" className="panel-action" aria-label="Kill Terminal" title="Kill the active terminal" disabled={activeTerm === null} onClick={() => activeTerm !== null && kill(activeTerm)}>
                <TrashIcon />
              </button>
            </>
          )}
          <button type="button" className="panel-action" aria-label="Close Panel" title="Close Panel (Ctrl+`)" onClick={onClose}>
            <CloseIcon />
          </button>
        </div>
      </div>

      <div className="panel-body">
        {tab === "problems" && (
          <div className="problems" role="tree" aria-label="Problems">
            {problems.length === 0 ? (
              <div className="panel-empty">No problems have been detected in the open files.</div>
            ) : (
              [...byFile.entries()].map(([file, list]) => (
                <Fragment key={file}>
                  <div className="problem-file" role="treeitem" aria-expanded="true" aria-level={1}>
                    <span className="problem-file-name">{file.slice(file.lastIndexOf("/") + 1)}</span>
                    <span className="problem-file-dir">{file.includes("/") ? file.slice(0, file.lastIndexOf("/")) : ""}</span>
                    <span className="panel-badge">{list.length}</span>
                  </div>
                  {list.map((p, i) => (
                    <button
                      key={i}
                      type="button"
                      role="treeitem"
                      aria-level={2}
                      className="problem-row"
                      title={`${p.message} (${file} ${p.line}:${p.column})`}
                      onClick={() => onOpenProblem(p)}
                    >
                      {p.severity === "error" ? <ErrorIcon /> : p.severity === "warning" ? <WarningIcon /> : <InfoIcon />}
                      <span className="problem-msg">{p.message}</span>
                      {p.source && <span className="problem-src">{p.source}</span>}
                      <span className="problem-pos">[Ln {p.line}, Col {p.column}]</span>
                    </button>
                  ))}
                </Fragment>
              ))
            )}
            {problems.length > 0 && (
              <div className="problems-summary" aria-live="polite">
                {errors} error{errors === 1 ? "" : "s"}, {warnings} warning{warnings === 1 ? "" : "s"}
              </div>
            )}
          </div>
        )}

        <div className="terminal-area" hidden={tab !== "terminal"}>
          <div className="terminal-groups">
            {groups.map((g) => (
              <div key={g.id} className="terminal-group" hidden={g.id !== activeGroup}>
                {g.terms.map((t, i) => (
                  <Fragment key={t.id}>
                    {i > 0 && <div className="terminal-divider" aria-hidden="true" />}
                    <TerminalView
                      projectId={projectId}
                      shellId={t.shellId}
                      visible={visible && tab === "terminal" && g.id === activeGroup}
                      focused={t.id === activeTerm}
                      onFocus={() => {
                        setActiveGroup(g.id);
                        setActiveTerm(t.id);
                      }}
                      onState={(s) => setTermState(t.id, s)}
                    />
                  </Fragment>
                ))}
              </div>
            ))}
            {groups.length === 0 && (
              <div className="panel-empty terminal-empty">
                {helperError ? (
                  <>
                    <strong>The terminal helper is not running.</strong>
                    <span>
                      Terminals run on your computer through the ORBIT terminal helper ({TERMINAL_URL}). start.cmd / start.sh start it;
                      or run <code>{helperStartHint()}</code> in the ORBIT folder.
                    </span>
                    <button type="button" className="btn small" onClick={() => void loadShells()}>
                      Try Again
                    </button>
                  </>
                ) : (
                  <>
                    <span>No terminal is open.</span>
                    <span className="terminal-empty-shells">
                      {(shells ?? []).map((s) => (
                        <button key={s.id} type="button" className="btn ghost small" onClick={() => void newTerminal(s.id)}>
                          {s.name}
                        </button>
                      ))}
                    </span>
                  </>
                )}
              </div>
            )}
          </div>

          {total > 1 && (
            <ul className="terminal-list" role="listbox" aria-label="Terminals">
              {groups.map((g) =>
                g.terms.map((t, i) => (
                  <li
                    key={t.id}
                    role="option"
                    aria-selected={t.id === activeTerm}
                    className={`terminal-item${t.id === activeTerm ? " active" : ""}${g.terms.length > 1 ? " in-split" : ""}`}
                    onClick={() => {
                      setActiveGroup(g.id);
                      setActiveTerm(t.id);
                    }}
                    title={`${t.shellName}${t.state === "exited" ? " (exited)" : ""}`}
                  >
                    {g.terms.length > 1 && <span className="split-line" aria-hidden="true">{i === 0 ? "┌" : i === g.terms.length - 1 ? "└" : "├"}</span>}
                    <TermIcon />
                    <span className="terminal-item-name">{t.shellName}</span>
                    {t.state === "exited" && <span className="terminal-item-exited">exited</span>}
                    <span className="terminal-item-actions">
                      <button type="button" aria-label={`Split ${t.shellName}`} title="Split Terminal" onClick={(e) => { e.stopPropagation(); split(g.id); }}>
                        <SplitIcon />
                      </button>
                      <button type="button" aria-label={`Kill ${t.shellName}`} title="Kill Terminal" onClick={(e) => { e.stopPropagation(); kill(t.id); }}>
                        <TrashIcon />
                      </button>
                    </span>
                  </li>
                )),
              )}
            </ul>
          )}
        </div>
      </div>

      {menu && <TreeContextMenu x={menu.x} y={menu.y} items={menuItems} onClose={() => setMenu(null)} />}
    </section>
  );
}
