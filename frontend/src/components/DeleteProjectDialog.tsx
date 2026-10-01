"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { api, ApiError, type Project } from "@/lib/api";

interface Props {
  project: Project;
  onClose: () => void;
  onDeleted: (projectId: string) => void;
}

/** Asks the user to type the project name before the project is removed from ORBIT. */
export default function DeleteProjectDialog({ project, onClose, onDeleted }: Props) {
  const [typed, setTyped] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const matches = typed === project.projectName;

  useEffect(() => {
    inputRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !busy) onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onClose]);

  async function submit(e?: FormEvent) {
    e?.preventDefault();
    if (!matches || busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.deleteProject(project.projectId, typed);
      onDeleted(project.projectId);
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) {
        onDeleted(project.projectId); // already gone: just refresh the list
        return;
      }
      setError(err instanceof ApiError ? err.message : "The project could not be deleted.");
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <form
        className="modal jb-modal delete-modal"
        role="alertdialog"
        aria-modal="true"
        aria-label="Delete project"
        onSubmit={submit}
      >
        <div className="jb-titlebar">
          <span className="jb-title">Delete Project</span>
          <button type="button" className="jb-close" onClick={onClose} aria-label="Close" disabled={busy}>×</button>
        </div>

        <div className="jb-body">
          <p className="delete-lead">
            You are about to delete <strong>{project.projectName}</strong> from ORBIT.
          </p>
          <p className="hint">
            The project and its saved details are removed from ORBIT. The folder{" "}
            <code>{project.location}</code> and your files in it stay on your computer and are not deleted.
          </p>

          <label className="delete-label" htmlFor="delete-confirm">
            Please type <strong>{project.projectName}</strong> to confirm.
          </label>
          <input
            id="delete-confirm"
            ref={inputRef}
            className="delete-input"
            type="text"
            value={typed}
            onChange={(e) => setTyped(e.target.value)}
            autoComplete="off"
            spellCheck={false}
            disabled={busy}
          />
          {error && <div className="alert" role="alert">{error}</div>}
        </div>

        <div className="jb-footer">
          <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn danger" disabled={!matches || busy}>
            {busy ? "Deleting…" : "Delete Project"}
          </button>
        </div>
      </form>
    </div>
  );
}
