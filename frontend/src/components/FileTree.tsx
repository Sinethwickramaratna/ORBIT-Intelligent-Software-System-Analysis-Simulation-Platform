"use client";

import { useCallback, useEffect, useRef, useState, type MouseEvent as ReactMouseEvent } from "react";
import { api, ApiError, type ProjectScan, type ScanProgress, type TreeEntry } from "@/lib/api";
import AnalysisPanel from "./AnalysisPanel";
import BuildSystemPanel from "./BuildSystemPanel";
import ConfigurationPanel from "./ConfigurationPanel";
import FrameworkPanel from "./FrameworkPanel";
import { usePanelResize } from "./usePanelResize";
import ConfirmDialog from "./ConfirmDialog";
import TreeContextMenu, { type MenuEntry } from "./TreeContextMenu";

interface NodeState {
  entries?: TreeEntry[];
  loading?: boolean;
  error?: string;
  truncated?: boolean;
}

const Chevron = ({ open }: { open: boolean }) => (
  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" style={{ transform: open ? "rotate(90deg)" : "none", transition: "transform .1s" }} aria-hidden="true">
    <path d="m9 6 6 6-6 6" />
  </svg>
);
const FolderGlyph = ({ open }: { open: boolean }) => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill={open ? "none" : "currentColor"} fillOpacity={open ? 0 : 0.18} stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
  </svg>
);
const FileGlyph = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
    <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z" />
    <path d="M14 3v5h5" />
  </svg>
);

/** VS Code-style "New File" / "New Folder" icons (outline with a small plus). */
const NewFileGlyph = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M13 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h5" />
    <path d="M13 3l6 6v3" />
    <path d="M13 3v6h6" />
    <path d="M18 15v6M15 18h6" />
  </svg>
);
const NewFolderGlyph = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 19H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v3" />
    <path d="M18 14v7M14.5 17.5h7" />
  </svg>
);
const RenameGlyph = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 20h4L19 9l-4-4L4 16Z" />
    <path d="m13.5 6.5 4 4" />
  </svg>
);
const TrashGlyph = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3" />
  </svg>
);

const parentOf = (path: string) => (path.includes("/") ? path.slice(0, path.lastIndexOf("/")) : "");
const nameOf = (path: string) => path.slice(path.lastIndexOf("/") + 1);
const under = (p: string, root: string) => p === root || p.startsWith(root + "/");

/** An entry whose name is being edited in place (right after New File / New Folder, or after Rename). */
interface Renaming {
  path: string;
  kind: TreeEntry["kind"];
  draft: string;
  error: string | null;
  busy: boolean;
  /** Just created: when the name is confirmed (or kept) a new file opens in the editor. */
  isNew: boolean;
}

const ScanGlyph = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2" />
    <path d="M7 12h10" />
  </svg>
);

/** Bottom windows of the Explorer: Analysis, Build System (shrinks to fit), Configuration and Framework. */
const PANEL_KEY = "orbit.analysis.height"; // the user drags the splitter above Analysis; the choice is remembered
const PANEL_OPEN_KEY = "orbit.analysis.open"; // "0" = hidden (only its title bar remains), like a VS Code panel
const BUILD_OPEN_KEY = "orbit.build.open"; // "0" = Build System window hidden
const CONFIG_KEY = "orbit.config.height";
const CONFIG_OPEN_KEY = "orbit.config.open"; // "0" = Configuration window hidden
const FRAMEWORK_KEY = "orbit.framework.height";
const FRAMEWORK_OPEN_KEY = "orbit.framework.open"; // "0" = Framework window hidden
const PANEL_DEFAULT = 220;
const PANEL_MIN = 96;
const CONFIG_DEFAULT = 130;
const CONFIG_MIN = 80;
const FRAMEWORK_DEFAULT = 150;
const FRAMEWORK_MIN = 80;
const TREE_MIN = 120; // the file tree always keeps at least this much room

/**
 * Visual-Studio-style solution explorer. Folders load one level at a time when expanded. Under the tree sits the
 * Analysis window with the language scan of the project; the Scan button runs the scan again after the folder changed.
 */
export default function FileTree({
  projectId,
  projectName,
  activeFile = null,
  onOpenFile,
  onRenamed,
  onDeleted,
}: {
  projectId: string;
  projectName: string;
  /** The file shown in the editor (highlighted in the tree). */
  activeFile?: string | null;
  onOpenFile?: (path: string) => void;
  onRenamed?: (from: string, to: string) => void;
  onDeleted?: (path: string) => void;
}) {
  const [nodes, setNodes] = useState<Record<string, NodeState>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<Renaming | null>(null);
  const renamingRef = useRef<Renaming | null>(null);
  renamingRef.current = renaming;
  const committing = useRef(false);
  const [menu, setMenu] = useState<{ x: number; y: number; entry: TreeEntry | null } | null>(null);
  const [deleting, setDeleting] = useState<{ entry: TreeEntry; busy: boolean; error: string | null } | null>(null);
  const [opError, setOpError] = useState<string | null>(null);
  const treeRef = useRef<HTMLDivElement>(null);

  const [scan, setScan] = useState<ProjectScan | null>(null);
  const [scanLoading, setScanLoading] = useState(true);
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [progress, setProgress] = useState<ScanProgress | null>(null);
  const [buildOpen, setBuildOpen] = useState(true);
  const activeProject = useRef(projectId);

  const [panelOpen, setPanelOpen] = useState(true);
  const [configOpen, setConfigOpen] = useState(true);
  const [frameworkOpen, setFrameworkOpen] = useState(true);
  const asideRef = useRef<HTMLElement>(null);

  const load = useCallback(
    async (path: string, silent = false) => {
      if (!silent) setNodes((n) => ({ ...n, [path]: { ...n[path], loading: true, error: undefined } }));
      try {
        const t = await api.projectTree(projectId, path);
        setNodes((n) => ({ ...n, [path]: { entries: t.entries, truncated: t.truncated } }));
      } catch (e) {
        setNodes((n) => ({ ...n, [path]: { error: e instanceof Error ? e.message : "Could not load folder." } }));
      }
    },
    [projectId],
  );

  useEffect(() => {
    setNodes({});
    setExpanded(new Set());
    setSelected(null);
    setRenaming(null);
    setMenu(null);
    setDeleting(null);
    setOpError(null);
    load("");
  }, [projectId, load]);

  // ---- file operations: New File / New Folder (then rename in place), Rename, Delete ----
  const entryAt = (path: string) => nodes[parentOf(path)]?.entries?.find((e) => e.path === path) ?? null;
  /** Where New File / New Folder goes: into a folder, next to a file, or the project root when nothing is chosen. */
  const folderFor = (entry: TreeEntry | null) => (entry ? (entry.kind === "FOLDER" ? entry.path : parentOf(entry.path)) : "");
  const errorText = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback);

  /** Visual Studio style: the item is created at once with a free default name and opens in rename mode. */
  async function createIn(folder: string, kind: TreeEntry["kind"]) {
    setOpError(null);
    setMenu(null);
    try {
      const listing = nodes[folder]?.entries ?? (await api.projectTree(projectId, folder)).entries;
      const taken = new Set(listing.map((e) => e.name.toLowerCase()));
      const [base, ext] = kind === "FOLDER" ? ["NewFolder", ""] : ["NewFile", ".txt"];
      let created: TreeEntry | null = null;
      for (let i = 1; !created && i < 1000; i++) {
        const name = `${base}${i}${ext}`;
        if (taken.has(name.toLowerCase())) continue;
        try {
          created = await api.createEntry(projectId, folder, name, kind);
        } catch (e) {
          if (!(e instanceof ApiError && e.code === "FILE_ALREADY_EXISTS")) throw e;
        }
      }
      if (!created) return;
      if (folder) setExpanded((s) => new Set(s).add(folder));
      await load(folder, true);
      setSelected(created.path);
      setRenaming({ path: created.path, kind, draft: created.name, error: null, busy: false, isNew: true });
    } catch (e) {
      setOpError(errorText(e, `The new ${kind === "FOLDER" ? "folder" : "file"} could not be created.`));
    }
  }

  function startRename(entry: TreeEntry) {
    setMenu(null);
    setOpError(null);
    setSelected(entry.path);
    setRenaming({ path: entry.path, kind: entry.kind, draft: entry.name, error: null, busy: false, isNew: false });
  }

  function endRename(r: Renaming, finalPath: string) {
    setRenaming(null);
    if (r.isNew && r.kind === "FILE") onOpenFile?.(finalPath);
    // give the keyboard back to the tree row
    requestAnimationFrame(() => treeRef.current?.querySelector<HTMLElement>(`[data-path="${CSS.escape(finalPath)}"]`)?.focus());
  }

  /** Moves the open/loaded state of a renamed folder (and everything under it) to the new path. */
  function remap(from: string, to: string) {
    const moved = [...expanded].filter((p) => under(p, from)).map((p) => to + p.slice(from.length));
    setExpanded((s) => new Set([...s].filter((p) => !under(p, from)).concat(moved)));
    setNodes((n) => Object.fromEntries(Object.entries(n).filter(([k]) => !under(k, from))));
    moved.forEach((p) => load(p));
  }

  async function commitRename() {
    const r = renamingRef.current;
    if (!r || r.busy || committing.current) return;
    const draft = r.draft.trim();
    const oldName = nameOf(r.path);
    if (!draft || draft === oldName) {
      endRename(r, r.path); // nothing typed / unchanged: keep the current name
      return;
    }
    committing.current = true;
    setRenaming({ ...r, busy: true, error: null });
    try {
      const entry = await api.renameEntry(projectId, r.path, draft);
      if (r.kind === "FOLDER") remap(r.path, entry.path);
      await load(parentOf(r.path), true);
      setSelected(entry.path);
      onRenamed?.(r.path, entry.path);
      endRename(r, entry.path);
    } catch (e) {
      setRenaming({ ...r, busy: false, error: errorText(e, "The name could not be changed.") });
    } finally {
      committing.current = false;
    }
  }

  function cancelRename() {
    const r = renamingRef.current;
    if (r && !r.busy) endRename(r, r.path);
  }

  async function confirmDelete() {
    if (!deleting || deleting.busy) return;
    const { entry } = deleting;
    setDeleting({ ...deleting, busy: true, error: null });
    try {
      await api.deleteEntry(projectId, entry.path);
    } catch (e) {
      if (!(e instanceof ApiError && e.code === "FILE_NOT_FOUND")) {
        setDeleting({ entry, busy: false, error: errorText(e, "It could not be deleted.") });
        return;
      }
    }
    setExpanded((s) => new Set([...s].filter((p) => !under(p, entry.path))));
    setNodes((n) => Object.fromEntries(Object.entries(n).filter(([k]) => !under(k, entry.path))));
    if (selected && under(selected, entry.path)) setSelected(null);
    await load(parentOf(entry.path), true);
    onDeleted?.(entry.path);
    setDeleting(null);
  }

  function openMenu(ev: ReactMouseEvent, entry: TreeEntry | null) {
    ev.preventDefault();
    ev.stopPropagation();
    if (renamingRef.current) return;
    setSelected(entry ? entry.path : null);
    setMenu({ x: ev.clientX, y: ev.clientY, entry });
  }

  function menuItems(entry: TreeEntry | null): MenuEntry[] {
    const folder = folderFor(entry);
    const items: MenuEntry[] = [
      { label: "New File...", icon: <NewFileGlyph />, onSelect: () => createIn(folder, "FILE") },
      { label: "New Folder...", icon: <NewFolderGlyph />, onSelect: () => createIn(folder, "FOLDER") },
    ];
    if (entry) {
      items.push(
        null,
        { label: "Rename...", icon: <RenameGlyph />, shortcut: "F2", onSelect: () => startRename(entry) },
        { label: "Delete", icon: <TrashGlyph />, shortcut: "Delete", danger: true, onSelect: () => setDeleting({ entry, busy: false, error: null }) },
      );
    }
    return items;
  }

  // the stored scan of the opened project (it was made automatically when the project was created or cloned)
  useEffect(() => {
    activeProject.current = projectId;
    setScan(null);
    setScanError(null);
    setScanning(false);
    setProgress(null);
    setScanLoading(true);
    api
      .latestScan(projectId)
      .then((s) => {
        if (activeProject.current === projectId) setScan(s ?? null);
      })
      .catch((e) => {
        if (activeProject.current === projectId) setScanError(e instanceof Error ? e.message : "Could not load the analysis.");
      })
      .finally(() => {
        if (activeProject.current === projectId) setScanLoading(false);
      });
  }, [projectId]);

  async function runScan() {
    if (scanning) return;
    const id = projectId;
    setScanning(true);
    setScanError(null);
    setProgress({ active: true, phase: "Starting…", percent: 0 });
    // the scan request stays open until it is finished: ask the server how far it is meanwhile
    let polling = true;
    const poll = window.setInterval(() => {
      api
        .scanProgress(id)
        .then((p) => {
          if (polling && activeProject.current === id && p.active) setProgress((old) => ({ ...p, percent: Math.max(p.percent, old?.percent ?? 0) }));
        })
        .catch(() => undefined);
    }, 150);
    try {
      const result = await api.scanProject(id);
      polling = false;
      window.clearInterval(poll);
      if (activeProject.current !== id) return;
      setProgress({ active: true, phase: "Done", percent: 100 });
      await new Promise((r) => setTimeout(r, 350)); // let the full bar be seen
      if (activeProject.current !== id) return;
      setScan(result);
      // the folder may have changed: show its current structure too (folders stay open, no flicker)
      Object.keys(nodes).forEach((p) => load(p, true));
    } catch (e) {
      if (activeProject.current === id) {
        setScanError(e instanceof ApiError || e instanceof Error ? e.message : "The scan failed.");
      }
    } finally {
      polling = false;
      window.clearInterval(poll);
      if (activeProject.current === id) {
        setScanning(false);
        setProgress(null);
      }
    }
  }

  // ---- window heights: restore, clamp to the room available, drag with mouse/touch/keyboard ----
  const measure = (sel: string) => asideRef.current?.querySelector<HTMLElement>(sel)?.offsetHeight ?? 0;
  /** Room for the bottom windows: everything except the header, progress bar, project name, splitters and tree. */
  const roomFor = (...otherWindowSels: string[]) => {
    const aside = asideRef.current;
    if (!aside) return 0;
    const used = [".explorer-head", ".scan-progress", ".explorer-root", ".explorer-split", ".config-split", ".framework-split", ...otherWindowSels].reduce(
      (sum, sel) => sum + measure(sel),
      0,
    );
    // the Build System window gives way (down to its title bar) when another window grows
    const buildBar = measure(".build-wrap .build-head") + 1;
    return aside.clientHeight - used - buildBar - TREE_MIN;
  };
  const hasRoom = () => !!asideRef.current?.clientHeight;
  const analysis = usePanelResize({
    storageKey: PANEL_KEY,
    defaultHeight: PANEL_DEFAULT,
    min: PANEL_MIN,
    getMax: () => roomFor(".config-wrap", ".framework-wrap"),
    hasRoom,
  });
  const config = usePanelResize({
    storageKey: CONFIG_KEY,
    defaultHeight: CONFIG_DEFAULT,
    min: CONFIG_MIN,
    getMax: () => roomFor(".analysis-wrap", ".framework-wrap"),
    hasRoom,
  });
  const framework = usePanelResize({
    storageKey: FRAMEWORK_KEY,
    defaultHeight: FRAMEWORK_DEFAULT,
    min: FRAMEWORK_MIN,
    getMax: () => roomFor(".analysis-wrap", ".config-wrap"),
    hasRoom,
  });
  const { restore: restoreAnalysis, reclamp: reclampAnalysis } = analysis;
  const { restore: restoreConfig, reclamp: reclampConfig } = config;
  const { restore: restoreFramework, reclamp: reclampFramework } = framework;

  useEffect(() => {
    restoreAnalysis();
    restoreConfig();
    restoreFramework();
    const el = asideRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      reclampAnalysis();
      reclampConfig();
      reclampFramework();
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [restoreAnalysis, restoreConfig, restoreFramework, reclampAnalysis, reclampConfig, reclampFramework]);

  // restore whether the Analysis window was hidden
  useEffect(() => {
    try {
      if (localStorage.getItem(PANEL_OPEN_KEY) === "0") setPanelOpen(false);
    } catch {
      /* storage unavailable: stays open */
    }
  }, []);

  useEffect(() => {
    try {
      if (localStorage.getItem(BUILD_OPEN_KEY) === "0") setBuildOpen(false);
    } catch {
      /* stays open */
    }
  }, []);

  const toggleBuild = useCallback(() => {
    const next = !buildOpen;
    setBuildOpen(next);
    try {
      localStorage.setItem(BUILD_OPEN_KEY, next ? "1" : "0");
    } catch {
      /* the choice just is not remembered */
    }
  }, [buildOpen]);

  const togglePanel = useCallback(() => {
    const next = !panelOpen;
    setPanelOpen(next);
    try {
      localStorage.setItem(PANEL_OPEN_KEY, next ? "1" : "0");
    } catch {
      /* the choice just is not remembered */
    }
  }, [panelOpen]);

  // Ctrl/Cmd+J shows or hides the Analysis window, like the bottom panel in VS Code
  useEffect(() => {
    function onKey(ev: KeyboardEvent) {
      if ((ev.ctrlKey || ev.metaKey) && !ev.altKey && !ev.shiftKey && ev.key.toLowerCase() === "j") {
        ev.preventDefault();
        togglePanel();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [togglePanel]);

  useEffect(() => {
    try {
      if (localStorage.getItem(CONFIG_OPEN_KEY) === "0") setConfigOpen(false);
    } catch {
      /* stays open */
    }
  }, []);

  const toggleConfig = useCallback(() => {
    const next = !configOpen;
    setConfigOpen(next);
    try {
      localStorage.setItem(CONFIG_OPEN_KEY, next ? "1" : "0");
    } catch {
      /* the choice just is not remembered */
    }
  }, [configOpen]);

  useEffect(() => {
    try {
      if (localStorage.getItem(FRAMEWORK_OPEN_KEY) === "0") setFrameworkOpen(false);
    } catch {
      /* stays open */
    }
  }, []);

  const toggleFramework = useCallback(() => {
    const next = !frameworkOpen;
    setFrameworkOpen(next);
    try {
      localStorage.setItem(FRAMEWORK_OPEN_KEY, next ? "1" : "0");
    } catch {
      /* the choice just is not remembered */
    }
  }, [frameworkOpen]);

  function toggle(entry: TreeEntry) {
    setSelected(entry.path);
    if (entry.kind !== "FOLDER") {
      onOpenFile?.(entry.path);
      return;
    }
    const next = new Set(expanded);
    if (next.has(entry.path)) next.delete(entry.path);
    else {
      next.add(entry.path);
      if (!nodes[entry.path]?.entries) load(entry.path);
    }
    setExpanded(next);
  }

  function renderLevel(path: string, depth: number) {
    const st = nodes[path];
    if (!st || st.loading) return <div className="tree-note" style={{ paddingLeft: 12 + depth * 16 }}>Loading…</div>;
    if (st.error) return <div className="tree-note tree-error" style={{ paddingLeft: 12 + depth * 16 }}>{st.error}</div>;
    if (!st.entries?.length) return <div className="tree-note" style={{ paddingLeft: 12 + depth * 16 }}>{depth === 0 ? "This folder is empty." : "(empty)"}</div>;
    return (
      <>
        {st.entries.map((e) => {
          const isOpen = e.kind === "FOLDER" && expanded.has(e.path);
          const edit = renaming?.path === e.path ? renaming : null;
          return (
            <div key={e.path} role="none">
              <div
                role="treeitem"
                aria-level={depth + 1}
                aria-expanded={e.kind === "FOLDER" ? isOpen : undefined}
                aria-selected={selected === e.path}
                tabIndex={0}
                data-path={e.path}
                className={`tree-row${selected === e.path ? " selected" : ""}${activeFile === e.path ? " active-file" : ""}${edit ? " renaming" : ""}`}
                style={{ paddingLeft: 8 + depth * 16 }}
                onClick={() => !edit && toggle(e)}
                onContextMenu={(ev) => openMenu(ev, e)}
                onKeyDown={(ev) => {
                  if (edit || ev.target !== ev.currentTarget) return;
                  if (ev.key === "Enter" || ev.key === " ") {
                    ev.preventDefault();
                    toggle(e);
                  } else if (ev.key === "F2") {
                    ev.preventDefault();
                    startRename(e);
                  } else if (ev.key === "Delete") {
                    ev.preventDefault();
                    setDeleting({ entry: e, busy: false, error: null });
                  } else if (ev.key === "ContextMenu" || (ev.shiftKey && ev.key === "F10")) {
                    ev.preventDefault();
                    const r = ev.currentTarget.getBoundingClientRect();
                    setSelected(e.path);
                    setMenu({ x: r.left + 24, y: r.bottom, entry: e });
                  }
                }}
                title={edit ? undefined : e.path}
              >
                <span className="tree-twist">{e.kind === "FOLDER" ? <Chevron open={isOpen} /> : null}</span>
                <span className={`tree-icon ${e.kind === "FOLDER" ? "folder" : "file"}`}>{e.kind === "FOLDER" ? <FolderGlyph open={isOpen} /> : <FileGlyph />}</span>
                {edit ? (
                  <span className="tree-rename">
                    <input
                      className={`tree-rename-input${edit.error ? " invalid" : ""}`}
                      aria-label={`${edit.isNew ? "Name the new" : "Rename"} ${e.kind === "FOLDER" ? "folder" : "file"}`}
                      aria-invalid={!!edit.error}
                      value={edit.draft}
                      disabled={edit.busy}
                      autoFocus
                      spellCheck={false}
                      onFocus={(ev) => {
                        // select the name without its extension, like VS Code
                        const dot = e.kind === "FILE" ? edit.draft.lastIndexOf(".") : -1;
                        ev.currentTarget.setSelectionRange(0, dot > 0 ? dot : edit.draft.length);
                      }}
                      onClick={(ev) => ev.stopPropagation()}
                      onChange={(ev) => setRenaming({ ...edit, draft: ev.target.value, error: null })}
                      onKeyDown={(ev) => {
                        ev.stopPropagation();
                        if (ev.key === "Enter") {
                          ev.preventDefault();
                          commitRename();
                        } else if (ev.key === "Escape") {
                          ev.preventDefault();
                          cancelRename();
                        }
                      }}
                      onBlur={() => {
                        const r = renamingRef.current;
                        if (r && !r.error) commitRename();
                        else cancelRename();
                      }}
                    />
                    {edit.error && <span className="tree-rename-error" role="alert">{edit.error}</span>}
                  </span>
                ) : (
                  <span className="tree-name">{e.name}</span>
                )}
              </div>
              {isOpen && <div role="group">{renderLevel(e.path, depth + 1)}</div>}
            </div>
          );
        })}
        {st.truncated && <div className="tree-note" style={{ paddingLeft: 12 + depth * 16 }}>… more items not shown</div>}
      </>
    );
  }

  return (
    <aside className="explorer" aria-label="Project files" ref={asideRef}>
      <div className="explorer-head">
        <span className="sidebar-title">EXPLORER</span>
        <button
          type="button"
          className="scan-btn"
          onClick={runScan}
          disabled={scanning}
          title="Scan the project folder again (use it after you changed files)"
        >
          <ScanGlyph />
          {scanning ? "Scanning…" : "Scan"}
        </button>
      </div>
      {progress && (
        <div className="scan-progress">
          <div className="scan-progress-label">
            <span>{progress.phase}</span>
            <span>{progress.percent}%</span>
          </div>
          <div className="scan-progress-track" role="progressbar" aria-label="Scan progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.percent} aria-valuetext={`${progress.phase} ${progress.percent}%`}>
            <div className="scan-progress-fill" style={{ width: `${progress.percent}%` }} />
          </div>
        </div>
      )}
      <div className="explorer-root" onContextMenu={(ev) => openMenu(ev, null)}>
        <span className="explorer-root-name" title={projectName}>{projectName}</span>
        <span className="explorer-actions">
          <button
            type="button"
            className="explorer-action"
            aria-label="New File"
            title={`New File${selected ? "" : " (in the project root)"}`}
            onClick={() => createIn(folderFor(selected ? entryAt(selected) : null), "FILE")}
          >
            <NewFileGlyph />
          </button>
          <button
            type="button"
            className="explorer-action"
            aria-label="New Folder"
            title={`New Folder${selected ? "" : " (in the project root)"}`}
            onClick={() => createIn(folderFor(selected ? entryAt(selected) : null), "FOLDER")}
          >
            <NewFolderGlyph />
          </button>
        </span>
      </div>
      {opError && (
        <div className="tree-note tree-error tree-op-error" role="alert">
          {opError}
          <button type="button" className="icon-btn small" aria-label="Dismiss" onClick={() => setOpError(null)}>×</button>
        </div>
      )}
      <div
        className="tree"
        role="tree"
        aria-label={`${projectName} files`}
        ref={treeRef}
        onContextMenu={(ev) => openMenu(ev, null)}
        onClick={(ev) => {
          // a click on empty space clears the selection, so New File / New Folder then go to the project root
          if (!(ev.target as HTMLElement).closest(".tree-row")) setSelected(null);
        }}
      >
        {renderLevel("", 0)}
      </div>
      {menu && <TreeContextMenu x={menu.x} y={menu.y} items={menuItems(menu.entry)} onClose={() => setMenu(null)} />}
      {deleting && (
        <ConfirmDialog
          title={deleting.entry.kind === "FOLDER" ? "Delete Folder" : "Delete File"}
          busy={deleting.busy}
          error={deleting.error}
          onCancel={() => !deleting.busy && setDeleting(null)}
          actions={[
            { label: "Cancel", onClick: () => setDeleting(null) },
            { label: deleting.busy ? "Deleting…" : "Delete", tone: "danger", onClick: confirmDelete },
          ]}
        >
          <p className="confirm-lead">
            Are you sure you want to delete <strong>{deleting.entry.name}</strong>
            {deleting.entry.kind === "FOLDER" ? " and everything in it" : ""}?
          </p>
          <p className="hint">
            It is removed from disk permanently (<code>{deleting.entry.path}</code>). This cannot be undone.
          </p>
        </ConfirmDialog>
      )}
      {panelOpen && <div className="explorer-split" {...analysis.splitterProps("Resize the analysis window")} />}
      <div className={`analysis-wrap${panelOpen ? "" : " collapsed"}`} style={panelOpen ? { height: analysis.height } : undefined}>
        <AnalysisPanel scan={scan} loading={scanLoading} scanning={scanning} error={scanError} open={panelOpen} onToggle={togglePanel} />
      </div>
      <div className={`build-wrap${buildOpen ? "" : " collapsed"}`}>
        <BuildSystemPanel scan={scan} loading={scanLoading} scanning={scanning} open={buildOpen} onToggle={toggleBuild} />
      </div>
      {configOpen && <div className="config-split" {...config.splitterProps("Resize the configuration window")} />}
      <div className={`config-wrap${configOpen ? "" : " collapsed"}`} style={configOpen ? { height: config.height } : undefined}>
        <ConfigurationPanel scan={scan} loading={scanLoading} scanning={scanning} open={configOpen} onToggle={toggleConfig} />
      </div>
      {frameworkOpen && <div className="framework-split" {...framework.splitterProps("Resize the framework window")} />}
      <div className={`framework-wrap${frameworkOpen ? "" : " collapsed"}`} style={frameworkOpen ? { height: framework.height } : undefined}>
        <FrameworkPanel scan={scan} loading={scanLoading} scanning={scanning} open={frameworkOpen} onToggle={toggleFramework} />
      </div>
    </aside>
  );
}
