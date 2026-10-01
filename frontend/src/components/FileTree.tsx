"use client";

import { useCallback, useEffect, useState } from "react";
import { api, type TreeEntry } from "@/lib/api";

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

/** Visual-Studio-style solution explorer. Folders load one level at a time when expanded. */
export default function FileTree({ projectId, projectName }: { projectId: string; projectName: string }) {
  const [nodes, setNodes] = useState<Record<string, NodeState>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selected, setSelected] = useState<string | null>(null);

  const load = useCallback(
    async (path: string) => {
      setNodes((n) => ({ ...n, [path]: { ...n[path], loading: true, error: undefined } }));
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
    <aside className="explorer" aria-label="Project files">
      <div className="explorer-head">
        <span className="sidebar-title">EXPLORER</span>
      </div>
      <div className="explorer-root" title={projectName}>{projectName}</div>
      <div className="tree" role="tree" aria-label={`${projectName} files`}>{renderLevel("", 0)}</div>
    </aside>
  );
}
