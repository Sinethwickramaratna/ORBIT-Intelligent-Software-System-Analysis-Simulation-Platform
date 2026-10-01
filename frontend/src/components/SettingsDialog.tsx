"use client";

import { useEffect, useState } from "react";
import { api, ApiError, type FolderSettings } from "@/lib/api";
import { useFolderApply } from "@/lib/useFolderApply";
import ApplyFallback from "./ApplyFallback";
import FoldersEditor, { validateFolders } from "./FoldersEditor";

interface Props {
  onClose: () => void;
  onSaved: (settings: FolderSettings) => void;
}

/** Settings window (JetBrains-style). For now: the drives/folders ORBIT may open. */
export default function SettingsDialog({ onClose, onSaved }: Props) {
  const [settings, setSettings] = useState<FolderSettings | null>(null);
  const [folders, setFolders] = useState<string[]>([""]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<FolderSettings | null>(null);
  const gaveUp = useFolderApply(saved, (s) => {
    setSaved(s);
    onSaved(s);
  });

  useEffect(() => {
    api
      .getFolderSettings()
      .then((s) => {
        setSettings(s);
        setFolders(s.folders.length ? s.folders : [""]);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Could not load the settings."));
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !saving) onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [saving, onClose]);

  async function save() {
    setError(null);
    const problem = validateFolders(folders);
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    try {
      const next = await api.saveFolderSettings(folders.map((f) => f.trim()).filter(Boolean));
      setSettings(next);
      setFolders(next.folders);
      setSaved(next);
      onSaved(next);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save the settings.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !saving && onClose()}>
      <div className="modal jb-modal settings-modal" role="dialog" aria-modal="true" aria-label="Settings">
        <div className="jb-titlebar">
          <span className="jb-title">Settings</span>
          <button type="button" className="jb-close" onClick={onClose} aria-label="Close">×</button>
        </div>

        <div className="jb-body">
          <h3 className="settings-heading">Folders ORBIT can open</h3>
          {!settings && !error && <div className="tree-note">Loading…</div>}
          {settings && !settings.editable && (
            <p className="hint">ORBIT is running directly on this computer, so it can already open every folder. Nothing to configure.</p>
          )}
          {settings?.editable && (
            <>
              <FoldersEditor value={folders} onChange={setFolders} max={settings.max} idPrefix="set-folder" />
              {saved?.restartRequired && saved.autoApply && !gaveUp && (
                <div className="notice" role="status">
                  Saved. Applying it now - ORBIT restarts for a moment. Your data is kept.
                </div>
              )}
              {saved?.restartRequired && (!saved.autoApply || gaveUp) && (
                <div className="notice" role="status">
                  <ApplyFallback onSettings={(s) => { setSaved(s); onSaved(s); }} />
                </div>
              )}
              {saved && !saved.restartRequired && (
                <div className="notice" role="status">Saved and applied.</div>
              )}
            </>
          )}
          {error && <div className="alert" role="alert">{error}</div>}
        </div>

        <div className="jb-footer">
          <button type="button" className="btn ghost" onClick={onClose} disabled={saving}>{saved ? "Close" : "Cancel"}</button>
          {settings?.editable && (
            <button type="button" className="btn" onClick={save} disabled={saving}>{saving ? "Saving…" : "Save"}</button>
          )}
        </div>
      </div>
    </div>
  );
}
