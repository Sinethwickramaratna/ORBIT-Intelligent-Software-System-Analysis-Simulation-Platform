"use client";

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { api, ApiError } from "@/lib/api";
import type { Dependencies } from "@/lib/useDependencies";
import ArchitecturePanel from "./ArchitecturePanel";
import { languageForPath, languageName } from "@/lib/languages";
import CodeEditor, { disposeModel, modelPath, readModelText, takeModelText } from "./CodeEditor";
import ConfirmDialog from "./ConfirmDialog";
import FileTree from "./FileTree";
import BottomPanel, { ErrorIcon, WarningIcon, type PanelTab, type Problem } from "./BottomPanel";
import { loader } from "@monaco-editor/react";
import { installSyntaxChecker } from "@/lib/diagnostics";

export interface WorkspaceProject {
  projectId: string;
  projectName: string;
  location: string;
  createdAt: string;
}

/** One open editor tab. `original` is the text as last loaded/saved (line endings normalised to \n). */
interface OpenFile {
  /** Stable for the life of the tab (the path changes when the file or a parent folder is renamed). */
  id: number;
  path: string;
  name: string;
  value: string;
  original: string;
  lastModified: number;
  bom: boolean;
  lineEnding: "LF" | "CRLF";
  loading: boolean;
  saving: boolean;
  error: string | null;
  saveError: string | null;
  /** Goes up every time the text is (re)loaded from disk; tells the editor to take the new text. */
  revision: number;
}

type Pending =
  | { kind: "close"; path: string }
  | { kind: "conflict"; path: string }
  | null;

const nameOf = (path: string) => path.slice(path.lastIndexOf("/") + 1);
const isDirty = (f: OpenFile) => !f.loading && !f.error && f.value !== f.original;
const normalise = (text: string) => text.replace(/\r\n?/g, "\n");

const CloseGlyph = () => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true">
    <path d="M6 6l12 12M18 6 6 18" />
  </svg>
);
const TabFileGlyph = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
    <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z" />
    <path d="M14 3v5h5" />
  </svg>
);

const PANEL_OPEN_KEY = "orbit.panel.open";
const PANEL_HEIGHT_KEY = "orbit.panel.height";
const PANEL_MIN = 110;

const TerminalGlyph = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="m7 9 3 3-3 3M12 15h5" />
  </svg>
);

/**
 * Problems of the open files of this project: the markers on their editor models - syntax errors from tree-sitter
 * (lib/diagnostics.ts) and from Monaco's TypeScript / JSON / CSS services. Starts once the editor has been loaded.
 */
function useProblems(projectId: string, editorUsed: boolean): Problem[] {
  const [problems, setProblems] = useState<Problem[]>([]);
  useEffect(() => {
    if (!editorUsed) return;
    let alive = true;
    let sub: { dispose(): void } | null = null;
    loader.init().then((monaco) => {
      if (!alive) return;
      installSyntaxChecker(monaco);
      const prefix = `/${projectId}/`;
      const severity = (s: number): Problem["severity"] | null =>
        s === monaco.MarkerSeverity.Error ? "error" : s === monaco.MarkerSeverity.Warning ? "warning" : s === monaco.MarkerSeverity.Info ? "info" : null;
      const collect = () => {
        const list: Problem[] = [];
        for (const m of monaco.editor.getModelMarkers({})) {
          const sev = severity(m.severity);
          if (!sev || !m.resource.path.startsWith(prefix) || !monaco.editor.getModel(m.resource)) continue;
          list.push({
            path: m.resource.path.slice(prefix.length),
            message: m.message,
            severity: sev,
            line: m.startLineNumber,
            column: m.startColumn,
            source: m.source || (m.owner === "orbit-syntax" ? "syntax" : m.owner),
          });
        }
        const rank = { error: 0, warning: 1, info: 2 };
        list.sort((a, b) => a.path.localeCompare(b.path) || rank[a.severity] - rank[b.severity] || a.line - b.line || a.column - b.column);
        setProblems(list);
      };
      sub = monaco.editor.onDidChangeMarkers(collect);
      collect();
    });
    return () => {
      alive = false;
      sub?.dispose();
    };
  }, [projectId, editorUsed]);
  return problems;
}

/**
 * The open project: tabs + editor (or the Architecture view) in the middle, the Explorer on the right. Files open in
 * tabs when clicked in the Explorer; Ctrl+S (Cmd+S) saves the active file to disk.
 */
export default function ProjectWorkspace({
  project,
  dependencies,
  onCloseProject,
  onDirtyChange,
}: {
  project: WorkspaceProject;
  dependencies: Dependencies;
  onCloseProject: () => void;
  /** Tells the parent whether any tab has unsaved changes (it asks before switching projects). */
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const projectId = project.projectId;
  const [files, setFiles] = useState<OpenFile[]>([]);
  const [activePath, setActivePath] = useState<string | null>(null); // null = the project (Architecture) tab
  const [cursor, setCursor] = useState({ line: 1, column: 1 });
  const [pending, setPending] = useState<Pending>(null);
  const filesRef = useRef(files);
  filesRef.current = files;
  const nextId = useRef(0);
  const problems = useProblems(projectId, files.length > 0);
  const [reveal, setReveal] = useState<{ path: string; line: number; column: number; nonce: number } | null>(null);

  // ---- bottom panel (Problems + Terminal), like VS Code's: open state and height are remembered ----
  const [panelOpen, setPanelOpen] = useState(false);
  const [panelTab, setPanelTab] = useState<PanelTab>("terminal");
  const [panelHeight, setPanelHeight] = useState(260);
  const [newTerminalRequest, setNewTerminalRequest] = useState(0);
  const middleRef = useRef<HTMLElement>(null);
  useEffect(() => {
    try {
      if (localStorage.getItem(PANEL_OPEN_KEY) === "1") setPanelOpen(true);
      const h = Number(localStorage.getItem(PANEL_HEIGHT_KEY));
      if (h >= PANEL_MIN) setPanelHeight(h);
    } catch {
      /* storage unavailable */
    }
  }, []);
  const showPanel = useCallback((open: boolean, tab?: PanelTab) => {
    setPanelOpen(open);
    if (tab) setPanelTab(tab);
    try {
      localStorage.setItem(PANEL_OPEN_KEY, open ? "1" : "0");
    } catch {
      /* not remembered */
    }
  }, []);
  const terminalCount = useRef(0);
  /** Ctrl+` / the terminal button: show or hide the panel; opening it with no terminal starts one (VS Code). */
  const toggleTerminal = useCallback(() => {
    if (panelOpen && panelTab === "terminal") {
      showPanel(false);
      return;
    }
    showPanel(true, "terminal");
    if (terminalCount.current === 0) setNewTerminalRequest((n) => n + 1);
  }, [panelOpen, panelTab, showPanel]);
  const newTerminal = useCallback(() => {
    showPanel(true, "terminal");
    setNewTerminalRequest((n) => n + 1);
  }, [showPanel]);

  function startPanelResize(e: ReactPointerEvent<HTMLDivElement>) {
    e.preventDefault();
    const startY = e.clientY;
    const startH = panelHeight;
    const max = (middleRef.current?.clientHeight ?? 600) - 140;
    const move = (ev: PointerEvent) => setPanelHeight(Math.max(PANEL_MIN, Math.min(max, startH - (ev.clientY - startY))));
    const up = (ev: PointerEvent) => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
      const h = Math.max(PANEL_MIN, Math.min(max, startH - (ev.clientY - startY)));
      try {
        localStorage.setItem(PANEL_HEIGHT_KEY, String(Math.round(h)));
      } catch {
        /* not remembered */
      }
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  }

  // Ctrl+` toggles the terminal panel, Ctrl+Shift+` opens a new terminal (VS Code keys)
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (!(e.ctrlKey || e.metaKey) || e.altKey || !(e.key === "`" || e.code === "Backquote" || e.key === "~")) return;
      e.preventDefault();
      if (e.shiftKey) newTerminal();
      else toggleTerminal();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [toggleTerminal, newTerminal]);

  /** Updates a tab by its id, so results that arrive after a rename (typing, save, load) still land on it. */
  const patch = useCallback((id: number, change: Partial<OpenFile>) => {
    setFiles((fs) => fs.map((f) => (f.id === id ? { ...f, ...change } : f)));
  }, []);

  const anyDirty = files.some(isDirty);
  useEffect(() => {
    onDirtyChange?.(anyDirty);
  }, [anyDirty, onDirtyChange]);
  useEffect(() => () => onDirtyChange?.(false), [onDirtyChange]);

  // leaving the page with unsaved changes asks first (the browser shows its own message)
  useEffect(() => {
    if (!anyDirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, [anyDirty]);

  const load = useCallback(
    async (id: number, path: string) => {
      try {
        const f = await api.readFile(projectId, path);
        const text = normalise(f.content ?? "");
        setFiles((fs) =>
          fs.map((x) =>
            x.id === id
              ? { ...x, value: text, original: text, lastModified: f.lastModified, bom: f.bom, lineEnding: f.lineEnding, loading: false, error: null, saveError: null, revision: x.revision + 1 }
              : x,
          ),
        );
      } catch (e) {
        patch(id, { loading: false, error: e instanceof Error ? e.message : "The file could not be opened." });
      }
    },
    [projectId, patch],
  );

  const openFile = useCallback(
    (path: string) => {
      setActivePath(path);
      setCursor({ line: 1, column: 1 });
      if (filesRef.current.some((f) => f.path === path)) return;
      const id = ++nextId.current;
      setFiles((fs) => [
        ...fs,
        { id, path, name: nameOf(path), value: "", original: "", lastModified: 0, bom: false, lineEnding: "LF", loading: true, saving: false, error: null, saveError: null, revision: 0 },
      ]);
      load(id, path);
    },
    [load],
  );

  const save = useCallback(
    async (path: string, overwrite = false): Promise<boolean> => {
      const f = filesRef.current.find((x) => x.path === path);
      if (!f || f.loading || f.error || f.saving) return false;
      const { id } = f;
      // the editor's own text: it already has keystrokes that React state may not have yet
      const sent = readModelText(modelPath(projectId, f.path)) ?? f.value;
      patch(id, { saving: true, saveError: null, value: sent });
      try {
        const res = await api.saveFile(projectId, {
          path,
          content: f.lineEnding === "CRLF" ? sent.replace(/\n/g, "\r\n") : sent,
          bom: f.bom,
          expectedLastModified: overwrite ? null : f.lastModified,
        });
        // keep whatever was typed while the save was running: only `sent` is now on disk
        patch(id, { saving: false, original: sent, lastModified: res.lastModified });
        return true;
      } catch (e) {
        if (e instanceof ApiError && e.code === "FILE_CHANGED_ON_DISK") {
          patch(id, { saving: false });
          setPending({ kind: "conflict", path });
        } else {
          patch(id, { saving: false, saveError: e instanceof Error ? e.message : "The file could not be saved." });
        }
        return false;
      }
    },
    [projectId, patch],
  );

  const dropTab = useCallback((path: string) => {
    const list = filesRef.current;
    const i = list.findIndex((f) => f.path === path);
    if (i === -1) return;
    const rest = list.filter((f) => f.path !== path);
    setFiles(rest);
    setActivePath((cur) => (cur === path ? (rest[Math.min(i, rest.length - 1)]?.path ?? null) : cur));
    disposeModel(modelPath(projectId, path));
  }, [projectId]);

  const requestClose = useCallback(
    (path: string) => {
      const f = filesRef.current.find((x) => x.path === path);
      if (f && isDirty(f)) setPending({ kind: "close", path });
      else dropTab(path);
    },
    [dropTab],
  );

  // Ctrl/Cmd+S saves the active file wherever the focus is (and never opens the browser's "Save page" dialog)
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "s") {
        if ((e.target as HTMLElement | null)?.closest?.(".xterm")) return; // Ctrl+S belongs to the shell there
        const handledByEditor = e.defaultPrevented && !!(e.target as HTMLElement | null)?.closest?.(".monaco-editor");
        e.preventDefault();
        if (handledByEditor) return; // Monaco's own Ctrl+S command already saved
        if (activePath) save(activePath);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [activePath, save]);

  // the Explorer renamed or deleted something: open tabs follow it (their old editor models are freed)
  const onRenamed = useCallback(
    (from: string, to: string) => {
      const move = (p: string) => (p === from ? to : p.startsWith(from + "/") ? to + p.slice(from.length) : p);
      // the editor's own text is the newest (it may hold keystrokes React has not seen yet): carry it to the new path
      const latest = new Map<number, string>();
      filesRef.current
        .filter((f) => move(f.path) !== f.path)
        .forEach((f) => {
          const text = takeModelText(modelPath(projectId, f.path));
          if (text !== null) latest.set(f.id, text);
        });
      setFiles((fs) =>
        fs.map((f) => ({ ...f, path: move(f.path), name: nameOf(move(f.path)), value: latest.get(f.id) ?? f.value })),
      );
      setActivePath((cur) => (cur ? move(cur) : cur));
    },
    [projectId],
  );
  const onDeleted = useCallback(
    (path: string) => {
      const gone = (p: string) => p === path || p.startsWith(path + "/");
      filesRef.current.filter((f) => gone(f.path)).forEach((f) => disposeModel(modelPath(projectId, f.path)));
      const rest = filesRef.current.filter((f) => !gone(f.path));
      setFiles(rest);
      setActivePath((cur) => (cur && gone(cur) ? (rest[rest.length - 1]?.path ?? null) : cur));
    },
    [projectId],
  );

  // leaving the project frees the editor models of its open files
  useEffect(() => () => filesRef.current.forEach((f) => disposeModel(modelPath(projectId, f.path))), [projectId]);

  const active = activePath ? files.find((f) => f.path === activePath) ?? null : null;
  const pendingFile = pending ? files.find((f) => f.path === pending.path) : undefined;

  return (
    <div className="project-view">
      <main className="project-middle" aria-label={`${project.projectName} workspace`} ref={middleRef}>
        <div className="editor-tabs" role="tablist" aria-label="Open editors">
          <div
            className={`project-tab editor-tab${activePath === null ? " active" : ""}`}
            role="tab"
            aria-selected={activePath === null}
            tabIndex={0}
            onClick={() => setActivePath(null)}
            onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && setActivePath(null)}
          >
            <span className="project-tab-name" title={project.createdAt ? `${project.location}\nCreated ${new Date(project.createdAt).toLocaleString()}` : project.location}>
              {project.projectName}
            </span>
            <button
              type="button"
              className="icon-btn small"
              aria-label="Close project"
              title="Close project"
              onClick={(e) => {
                e.stopPropagation();
                onCloseProject();
              }}
            >
              <CloseGlyph />
            </button>
          </div>
          {files.map((f) => {
            const dirty = isDirty(f);
            return (
              <div
                key={f.path}
                className={`editor-tab file${activePath === f.path ? " active" : ""}${dirty ? " dirty" : ""}`}
                role="tab"
                aria-selected={activePath === f.path}
                tabIndex={0}
                title={f.path}
                onClick={() => setActivePath(f.path)}
                onAuxClick={(e) => e.button === 1 && requestClose(f.path)}
                onKeyDown={(e) => (e.key === "Enter" || e.key === " ") && setActivePath(f.path)}
              >
                <span className="editor-tab-icon"><TabFileGlyph /></span>
                <span className="editor-tab-name">{f.name}</span>
                <button
                  type="button"
                  className="editor-tab-close"
                  aria-label={dirty ? `Close ${f.name} (unsaved changes)` : `Close ${f.name}`}
                  title={dirty ? "Unsaved changes - close" : "Close"}
                  onClick={(e) => {
                    e.stopPropagation();
                    requestClose(f.path);
                  }}
                >
                  <span className="dirty-dot" aria-hidden="true" />
                  <CloseGlyph />
                </button>
              </div>
            );
          })}
          <div className="editor-tabs-tools">
            <button
              type="button"
              className={`problems-indicator${problems.some((p) => p.severity === "error") ? " has-errors" : ""}`}
              title="Problems"
              aria-label={`Problems: ${problems.filter((p) => p.severity === "error").length} errors, ${problems.filter((p) => p.severity === "warning").length} warnings`}
              onClick={() => (panelOpen && panelTab === "problems" ? showPanel(false) : showPanel(true, "problems"))}
            >
              <ErrorIcon /> {problems.filter((p) => p.severity === "error").length}
              <WarningIcon /> {problems.filter((p) => p.severity === "warning").length}
            </button>
            <button
              type="button"
              className={`tools-btn${panelOpen && panelTab === "terminal" ? " active" : ""}`}
              aria-label="Toggle Terminal"
              title="Toggle Terminal (Ctrl+`)"
              onClick={toggleTerminal}
            >
              <TerminalGlyph />
            </button>
          </div>
        </div>

        <div className="middle-content">
        {active ? (
          <section className="editor-pane" aria-label={`Editor: ${active.path}`}>
            <div className="editor-crumbs" title={active.path}>
              {active.path.split("/").map((part, i, all) => (
                <span key={i} className={i === all.length - 1 ? "crumb last" : "crumb"}>{part}</span>
              ))}
            </div>
            {active.loading ? (
              <div className="editor-note">Opening {active.name}…</div>
            ) : active.error ? (
              <div className="editor-note error" role="alert">
                <strong>{active.name}</strong> cannot be opened in the editor.
                <span>{active.error}</span>
              </div>
            ) : (
              <CodeEditor
                filePath={active.path}
                path={modelPath(projectId, active.path)}
                label={`Contents of ${active.path}`}
                value={active.value}
                revision={active.revision}
                onChange={(v) => patch(active.id, { value: v })}
                onCursor={(line, column) => setCursor({ line, column })}
                onSave={() => save(active.path)}
                reveal={reveal && reveal.path === active.path ? reveal : null}
                autoFocus
              />
            )}
            <div className="editor-status" aria-live="polite">
              {active.saveError ? (
                <span className="editor-status-error">Save failed: {active.saveError}</span>
              ) : (
                <span>{active.saving ? "Saving…" : isDirty(active) ? "Modified - Ctrl+S to save" : active.loading || active.error ? "" : "Saved"}</span>
              )}
              <span className="editor-status-right">
                {!active.loading && !active.error && (
                  <>
                    {(() => {
                      const mine = problems.filter((p) => p.path === active.path);
                      const errs = mine.filter((p) => p.severity === "error").length;
                      const warns = mine.filter((p) => p.severity === "warning").length;
                      return errs + warns > 0 ? (
                        <button type="button" className={`status-problems${errs ? " has-errors" : ""}`} title="Show Problems" onClick={() => showPanel(true, "problems")}>
                          <ErrorIcon /> {errs} <WarningIcon /> {warns}
                        </button>
                      ) : null;
                    })()}
                    <span>Ln {cursor.line}, Col {cursor.column}</span>
                    <span className="editor-status-lang">{languageName(languageForPath(active.path))}</span>
                    <span>{active.lineEnding}</span>
                    <span>{active.bom ? "UTF-8 with BOM" : "UTF-8"}</span>
                  </>
                )}
              </span>
            </div>
          </section>
        ) : (
          <ArchitecturePanel dep={dependencies} />
        )}
        </div>

        {panelOpen && <div className="panel-split" role="separator" aria-orientation="horizontal" aria-label="Resize the panel" onPointerDown={startPanelResize} />}
        <div className="panel-wrap" style={{ height: panelHeight }} hidden={!panelOpen}>
          <BottomPanel
            projectId={projectId}
            tab={panelTab}
            onTab={(t) => showPanel(true, t)}
            onClose={() => showPanel(false)}
            problems={problems}
            newTerminalRequest={newTerminalRequest}
            onTerminalCount={(n) => (terminalCount.current = n)}
            visible={panelOpen}
            onOpenProblem={(p) => {
              openFile(p.path);
              setReveal({ path: p.path, line: p.line, column: p.column, nonce: Date.now() });
            }}
          />
        </div>
      </main>

      <FileTree
        projectId={projectId}
        projectName={project.projectName}
        activeFile={activePath}
        onOpenFile={openFile}
        onRenamed={onRenamed}
        onDeleted={onDeleted}
      />

      {pending?.kind === "close" && pendingFile && (
        <ConfirmDialog
          title="Unsaved Changes"
          onCancel={() => setPending(null)}
          busy={pendingFile.saving}
          error={pendingFile.saveError}
          actions={[
            { label: "Cancel", onClick: () => setPending(null) },
            {
              label: "Don't Save",
              tone: "ghost",
              onClick: () => {
                setPending(null);
                dropTab(pendingFile.path);
              },
            },
            {
              label: "Save",
              tone: "primary",
              onClick: async () => {
                if (await save(pendingFile.path)) {
                  setPending(null);
                  dropTab(pendingFile.path);
                }
              },
            },
          ]}
        >
          <p className="confirm-lead">
            Do you want to save the changes you made to <strong>{pendingFile.name}</strong>?
          </p>
          <p className="hint">Your changes will be lost if you don&apos;t save them.</p>
        </ConfirmDialog>
      )}

      {pending?.kind === "conflict" && pendingFile && (
        <ConfirmDialog
          title="File Changed on Disk"
          onCancel={() => setPending(null)}
          actions={[
            { label: "Cancel", onClick: () => setPending(null) },
            {
              label: "Reload from Disk",
              tone: "ghost",
              onClick: () => {
                setPending(null);
                patch(pendingFile.id, { loading: true });
                load(pendingFile.id, pendingFile.path);
              },
            },
            {
              label: "Overwrite",
              tone: "danger",
              onClick: () => {
                setPending(null);
                save(pendingFile.path, true);
              },
            },
          ]}
        >
          <p className="confirm-lead">
            <strong>{pendingFile.name}</strong> was changed by another program after you opened it.
          </p>
          <p className="hint">Overwrite it with your version, or reload it from disk and lose your changes.</p>
        </ConfirmDialog>
      )}
    </div>
  );
}
