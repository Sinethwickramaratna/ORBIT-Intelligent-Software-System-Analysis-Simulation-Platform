"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError, type FolderBrowse } from "@/lib/api";

interface Props {
  /** Folder to start in (the text currently in the Location field); falls back to the server's start folder. */
  initialPath?: string;
  onSelect: (path: string) => void;
  onClose: () => void;
}

const FolderIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" fillOpacity=".2" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
    <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
  </svg>
);

/** JetBrains-style "Select Location" window: browse the folders ORBIT can see, optionally make a new one. */
export default function FolderPicker({ initialPath, onSelect, onClose }: Props) {
  const [view, setView] = useState<FolderBrowse | null>(null);
  const [picked, setPicked] = useState<string | null>(null);
  const [typed, setTyped] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [naming, setNaming] = useState(false);
  const [newName, setNewName] = useState("");
  const [search, setSearch] = useState("");
  const nameRef = useRef<HTMLInputElement>(null);

  const go = useCallback(async (path?: string, fallbackToStart = false) => {
    setLoading(true);
    setError(null);
    try {
      const v = await api.browseFolders(path);
      setView(v);
      setTyped(v.path);
      setPicked(null);
      setNaming(false);
      setSearch("");
    } catch (e) {
      if (fallbackToStart && path) {
        // the typed location does not exist yet / is not reachable: start from the default folder instead
        try {
          const v = await api.browseFolders();
          setView(v);
          setTyped(v.path);
          setPicked(null);
        } catch (e2) {
          setError(e2 instanceof ApiError ? e2.message : "Could not load folders.");
        }
      } else {
        setError(e instanceof ApiError ? e.message : "Could not load folders.");
      }
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    go(initialPath?.trim() || undefined, true);
  }, [go, initialPath]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.stopPropagation();
        if (naming) setNaming(false);
        else onClose();
      }
    }
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [naming, onClose]);

  useEffect(() => {
    if (naming) nameRef.current?.focus();
  }, [naming]);

  async function makeFolder(e: FormEvent) {
    e.preventDefault();
    if (!view || !newName.trim()) return;
    try {
      const v = await api.createFolder(view.path, newName.trim());
      setView(v);
      setTyped(v.path);
      setPicked(null);
      setNaming(false);
      setNewName("");
      setError(null);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not create the folder.");
    }
  }

  const chosen = picked ?? view?.path ?? "";
  const needle = search.trim().toLowerCase();
  const filtered = view ? (needle ? view.folders.filter((f) => f.name.toLowerCase().includes(needle)) : view.folders) : [];

  return (
    <div className="modal-backdrop picker-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal picker" role="dialog" aria-modal="true" aria-label="Select Location">
        <div className="jb-titlebar">
          <span className="jb-title">Select Location</span>
          <button type="button" className="jb-close" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="picker-body">
          <form className="picker-path" onSubmit={(e) => { e.preventDefault(); go(typed.trim() || undefined); }}>
            <button type="button" className="tool-btn" title="Parent folder" aria-label="Parent folder" disabled={view?.parent == null || loading} onClick={() => view?.parent != null && go(view.parent)}>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M12 19V5M5 12l7-7 7 7" /></svg>
            </button>
            <input value={typed} onChange={(e) => setTyped(e.target.value)} aria-label="Folder path" placeholder="Drives and folders ORBIT can open" spellCheck={false} />
            <button type="button" className="tool-btn wide" onClick={() => setNaming(true)} disabled={!view || loading || view.path === ""}>New Folder</button>
          </form>

          {view && view.shortcuts.length > 0 && (
            <div className="picker-shortcuts">
              {view.shortcuts.map((s) => (
                <button key={s.path} type="button" className="chip" onClick={() => go(s.path)}>{s.name}</button>
              ))}
            </div>
          )}

          <input type="search" className="picker-search" value={search} onChange={(e) => setSearch(e.target.value)} aria-label="Search folders" placeholder="Search folders…" spellCheck={false} disabled={!view || loading} />

          <div className="picker-list" role="listbox" aria-label="Folders">
            {naming && (
              <form className="picker-row naming" onSubmit={makeFolder}>
                <FolderIcon />
                <input ref={nameRef} value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="New folder name" maxLength={100} aria-label="New folder name" />
                <button type="submit" className="tool-btn wide" disabled={!newName.trim()}>Create</button>
              </form>
            )}
            {loading && <div className="tree-note">Loading…</div>}
            {!loading && view && view.path === "" && view.folders.length > 0 && <div className="tree-note">Choose a drive or folder (double-click to open).</div>}
            {!loading && view && view.folders.length === 0 && !naming && <div className="tree-note">No sub-folders here.</div>}
            {!loading && view && view.folders.length > 0 && filtered.length === 0 && <div className="tree-note">No folders match “{search}”.</div>}
            {!loading &&
              filtered.map((f) => (
                <div
                  key={f.path}
                  role="option"
                  aria-selected={picked === f.path}
                  tabIndex={0}
                  className={`picker-row${picked === f.path ? " selected" : ""}`}
                  onClick={() => setPicked(f.path)}
                  onDoubleClick={() => go(f.path)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") go(f.path);
                    if (e.key === " ") { e.preventDefault(); setPicked(f.path); }
                  }}
                  title="Double-click to open"
                >
                  <FolderIcon />
                  <span className="tree-name">{f.name}</span>
                </div>
              ))}
            {view?.truncated && <div className="tree-note">… more folders not shown</div>}
          </div>

          {error && <div className="alert" role="alert">{error}</div>}
          <div className="picker-chosen" title={chosen}>
            <span>Selected:</span> <strong>{chosen}</strong>
          </div>
        </div>

        <div className="jb-footer">
          <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
          <button type="button" className="btn" disabled={!chosen} onClick={() => onSelect(chosen)}>OK</button>
        </div>
      </div>
    </div>
  );
}
