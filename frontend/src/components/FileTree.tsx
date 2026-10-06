"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, type ProjectScan, type ScanProgress, type TreeEntry } from "@/lib/api";
import AnalysisPanel from "./AnalysisPanel";
import BuildSystemPanel from "./BuildSystemPanel";

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

const ScanGlyph = () => (
  <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2" />
    <path d="M7 12h10" />
  </svg>
);

type Pane = "analysis" | "build";

/** Height of the Analysis window (px): the user drags the splitter above it; the choice is remembered. */
const PANEL_KEY = "orbit.analysis.height";
const PANEL_OPEN_KEY = "orbit.analysis.open"; // "0" = hidden (only its title bar remains), like a VS Code panel
const BUILD_OPEN_KEY = "orbit.build.open"; // "0" = Build System window hidden
const BUILD_KEY = "orbit.build.height";
const PANEL_DEFAULT = 300;
const PANEL_MIN = 96;
const BUILD_DEFAULT = 190;
const BUILD_MIN = 72;
const TREE_MIN = 120; // the file tree always keeps at least this much room

function storedHeight(key: string, def: number, min: number): number {
  try {
    const n = Number(localStorage.getItem(key));
    return Number.isFinite(n) && n >= min ? n : def;
  } catch {
    return def;
  }
}

/**
 * Visual-Studio-style solution explorer. Folders load one level at a time when expanded. Under the tree sits the
 * Analysis window with the language scan of the project; the Scan button runs the scan again after the folder changed.
 */
export default function FileTree({ projectId, projectName }: { projectId: string; projectName: string }) {
  const [nodes, setNodes] = useState<Record<string, NodeState>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);

  const [scan, setScan] = useState<ProjectScan | null>(null);
  const [scanLoading, setScanLoading] = useState(true);
  const [scanning, setScanning] = useState(false);
  const [scanError, setScanError] = useState<string | null>(null);
  const [progress, setProgress] = useState<ScanProgress | null>(null);
  const [buildOpen, setBuildOpen] = useState(true);
  const activeProject = useRef(projectId);

  const [panelHeight, setPanelHeight] = useState(PANEL_DEFAULT);
  const [panelOpen, setPanelOpen] = useState(true);
  const [buildHeight, setBuildHeight] = useState(BUILD_DEFAULT);
  // latest heights/open flags for the resize math (also used inside the ResizeObserver)
  const live = useRef({ panelHeight: PANEL_DEFAULT, buildHeight: BUILD_DEFAULT, panelOpen: true, buildOpen: true });
  live.current = { panelHeight, buildHeight, panelOpen, buildOpen };
  const asideRef = useRef<HTMLElement>(null);
  const drag = useRef<{ pane: Pane; startY: number; startHeight: number } | null>(null);

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
    load("");
  }, [projectId, load]);

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

  // ---- Analysis + Build System window heights: restore, clamp to the room available, drag with mouse/touch/keyboard ----
  // Room a window may use: the explorer minus its fixed parts (header, project name, splitters), the other window and the tree minimum.
  const maxFor = useCallback((pane: Pane) => {
    const aside = asideRef.current;
    const min = pane === "analysis" ? PANEL_MIN : BUILD_MIN;
    if (!aside) return min;
    const h = (sel: string) => aside.querySelector<HTMLElement>(sel)?.offsetHeight ?? 0;
    let used = [".explorer-head", ".scan-progress", ".explorer-root"].reduce((sum, sel) => sum + h(sel), 0);
    aside.querySelectorAll<HTMLElement>(".explorer-split").forEach((el) => (used += el.offsetHeight));
    const l = live.current;
    // the other window: its set height when open, its title bar when hidden (measured, not guessed)
    const other = pane === "analysis" ? (l.buildOpen ? l.buildHeight : h(".build-wrap")) : l.panelOpen ? l.panelHeight : h(".analysis-wrap");
    return Math.max(min, aside.clientHeight - used - other - TREE_MIN);
  }, []);
  const clampFor = useCallback(
    (pane: Pane, v: number) => {
      const min = pane === "analysis" ? PANEL_MIN : BUILD_MIN;
      return asideRef.current?.clientHeight ? Math.min(Math.max(v, min), maxFor(pane)) : Math.max(v, min);
    },
    [maxFor],
  );

  // restore the stored heights once
  useEffect(() => {
    setPanelHeight(clampFor("analysis", storedHeight(PANEL_KEY, PANEL_DEFAULT, PANEL_MIN)));
    setBuildHeight(clampFor("build", storedHeight(BUILD_KEY, BUILD_DEFAULT, BUILD_MIN)));
  }, [clampFor]);

  // keep both windows inside the explorer when it is resized or a window is shown/hidden
  useEffect(() => {
    const el = asideRef.current;
    if (!el) return;
    const fit = () => {
      setPanelHeight((v) => {
        const n = clampFor("analysis", v);
        live.current.panelHeight = n;
        return n;
      });
      setBuildHeight((v) => clampFor("build", v));
    };
    fit();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(fit);
    ro.observe(el);
    return () => ro.disconnect();
  }, [clampFor, panelOpen, buildOpen]);

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

  const panes = {
    analysis: { height: panelHeight, set: setPanelHeight, key: PANEL_KEY, def: PANEL_DEFAULT, min: PANEL_MIN },
    build: { height: buildHeight, set: setBuildHeight, key: BUILD_KEY, def: BUILD_DEFAULT, min: BUILD_MIN },
  } as const;

  function saveHeight(pane: Pane, h: number) {
    try {
      localStorage.setItem(panes[pane].key, String(Math.round(h)));
    } catch {
      /* the height just is not remembered */
    }
  }

  // one set of splitter handlers for both windows: dragging up makes the window below the splitter taller
  function splitProps(pane: Pane, label: string) {
    const cfg = panes[pane];
    return {
      className: "explorer-split",
      role: "separator" as const,
      "aria-orientation": "horizontal" as const,
      "aria-label": label,
      "aria-valuemin": cfg.min,
      "aria-valuemax": Math.round(maxFor(pane)),
      "aria-valuenow": Math.round(cfg.height),
      tabIndex: 0,
      title: "Drag to resize (double-click to reset)",
      onPointerDown: (ev: React.PointerEvent<HTMLDivElement>) => {
        ev.preventDefault();
        ev.currentTarget.setPointerCapture(ev.pointerId);
        drag.current = { pane, startY: ev.clientY, startHeight: cfg.height };
      },
      onPointerMove: (ev: React.PointerEvent<HTMLDivElement>) => {
        const d = drag.current;
        if (!d || d.pane !== pane) return;
        const h = clampFor(pane, d.startHeight + (d.startY - ev.clientY));
        live.current[pane === "analysis" ? "panelHeight" : "buildHeight"] = h;
        cfg.set(h);
      },
      onPointerUp: (ev: React.PointerEvent<HTMLDivElement>) => {
        if (drag.current?.pane !== pane) return;
        drag.current = null;
        if (ev.currentTarget.hasPointerCapture(ev.pointerId)) ev.currentTarget.releasePointerCapture(ev.pointerId);
        saveHeight(pane, live.current[pane === "analysis" ? "panelHeight" : "buildHeight"]);
      },
      onKeyDown: (ev: React.KeyboardEvent<HTMLDivElement>) => {
        const step = ev.shiftKey ? 64 : 16;
        let next: number | null = null;
        if (ev.key === "ArrowUp") next = cfg.height + step;
        else if (ev.key === "ArrowDown") next = cfg.height - step;
        else if (ev.key === "Home") next = cfg.min;
        else if (ev.key === "End") next = maxFor(pane);
        if (next === null) return;
        ev.preventDefault();
        const h = clampFor(pane, next);
        cfg.set(h);
        saveHeight(pane, h);
      },
      onDoubleClick: () => {
        const h = clampFor(pane, cfg.def);
        cfg.set(h);
        saveHeight(pane, h);
      },
    };
  }

  function toggle(entry: TreeEntry) {
    setSelected(entry.path);
    if (entry.kind !== "FOLDER") return;
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
          return (
            <div key={e.path} role="none">
              <div
                role="treeitem"
                aria-level={depth + 1}
                aria-expanded={e.kind === "FOLDER" ? isOpen : undefined}
                aria-selected={selected === e.path}
                tabIndex={0}
                className={`tree-row${selected === e.path ? " selected" : ""}`}
                style={{ paddingLeft: 8 + depth * 16 }}
                onClick={() => toggle(e)}
                onKeyDown={(ev) => {
                  if (ev.key === "Enter" || ev.key === " ") {
                    ev.preventDefault();
                    toggle(e);
                  }
                }}
                title={e.path}
              >
                <span className="tree-twist">{e.kind === "FOLDER" ? <Chevron open={isOpen} /> : null}</span>
                <span className={`tree-icon ${e.kind === "FOLDER" ? "folder" : "file"}`}>{e.kind === "FOLDER" ? <FolderGlyph open={isOpen} /> : <FileGlyph />}</span>
                <span className="tree-name">{e.name}</span>
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
      <div className="explorer-root" title={projectName}>{projectName}</div>
      <div className="tree" role="tree" aria-label={`${projectName} files`}>{renderLevel("", 0)}</div>
      {panelOpen && <div {...splitProps("analysis", "Resize the analysis window")} onPointerCancel={splitProps("analysis", "").onPointerUp} />}
      <div className={`analysis-wrap${panelOpen ? "" : " collapsed"}`} style={panelOpen ? { height: panelHeight } : undefined}>
        <AnalysisPanel scan={scan} loading={scanLoading} scanning={scanning} error={scanError} open={panelOpen} onToggle={togglePanel} />
      </div>
      {buildOpen && <div {...splitProps("build", "Resize the build system window")} onPointerCancel={splitProps("build", "").onPointerUp} />}
      <div className={`build-wrap${buildOpen ? "" : " collapsed"}`} style={buildOpen ? { height: buildHeight } : undefined}>
        <BuildSystemPanel scan={scan} loading={scanLoading} scanning={scanning} open={buildOpen} onToggle={toggleBuild} />
      </div>
    </aside>
  );
}
