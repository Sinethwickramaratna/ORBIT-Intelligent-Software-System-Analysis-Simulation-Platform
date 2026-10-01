"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import FolderPicker from "./FolderPicker";
import { api, ApiError, PROJECT_TYPES, type LocationInspection, type Project, type ProjectType } from "@/lib/api";

interface Props {
  onClose: () => void;
  onCreated: (project: Project) => void;
}

/** "Create New Project" window: name, location, type, description, optional git init. */
export default function ProjectDialog({ onClose, onCreated }: Props) {
  const uid = useId();
  const [name, setName] = useState("");
  const [location, setLocation] = useState("");
  const [type, setType] = useState<ProjectType>("WEB_APPLICATION");
  const [description, setDescription] = useState("");
  const [initGit, setInitGit] = useState(true);
  const [root, setRoot] = useState<string | null>(null);
  const [inspection, setInspection] = useState<LocationInspection | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [picking, setPicking] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    nameRef.current?.focus();
    api.projectConfig().then((c) => setRoot(c.root)).catch(() => {});
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !busy && !picking) onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, picking, onClose]);

  // Live feedback about the typed location (outside the mounted root? already a git repo?).
  useEffect(() => {
    const value = location.trim();
    if (!value) {
      setInspection(null);
      return;
    }
    let cancelled = false;
    const t = setTimeout(() => {
      api
        .inspectLocation(value)
        .then((r) => !cancelled && setInspection(r))
        .catch(() => !cancelled && setInspection(null));
    }, 350);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [location]);

  const hasRepo = inspection?.valid === true && inspection.gitRepository;
  const locationProblem = inspection && !inspection.valid ? inspection.message : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!name.trim()) next.projectName = "Project name is required.";
    if (!location.trim()) next.location = "Location is required.";
    setErrors(next);
    setServerError(null);
    if (Object.keys(next).length) return;

    setBusy(true);
    try {
      const project = await api.createProject({
        projectName: name.trim(),
        location: location.trim(),
        projectType: type,
        description: description.trim() || undefined,
        initGit: hasRepo ? false : initGit,
      });
      onCreated(project);
    } catch (err) {
      if (err instanceof ApiError) {
        setErrors(err.fieldErrors ?? {});
        setServerError(err.message);
      } else {
        setServerError("Could not reach the server.");
      }
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <form className="modal jb-modal" role="dialog" aria-modal="true" aria-labelledby={`${uid}-title`} onSubmit={submit} noValidate>
        <div className="jb-titlebar">
          <span className="jb-title" id={`${uid}-title`}>New Project</span>
          <button type="button" className="jb-close" onClick={onClose} disabled={busy} aria-label="Close">×</button>
        </div>

        <div className="jb-body">
          <div className="jb-row">
            <label htmlFor={`${uid}-name`}>Name:</label>
            <div className="jb-control">
              <input id={`${uid}-name`} ref={nameRef} value={name} maxLength={150} placeholder="my-project" onChange={(e) => setName(e.target.value)} />
              {errors.projectName && <span className="field-error">{errors.projectName}</span>}
            </div>
          </div>

          <div className="jb-row">
            <label htmlFor={`${uid}-loc`}>Location:</label>
            <div className="jb-control">
              <div className="input-with-button">
                <input
                  id={`${uid}-loc`}
                  value={location}
                  maxLength={1024}
                  placeholder={root ? `${root}/MyProject` : "Folder of the project"}
                  onChange={(e) => setLocation(e.target.value)}
                />
                <button type="button" className="folder-btn" onClick={() => setPicking(true)} aria-label="Browse for a folder" title="Browse…">
                  <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" fillOpacity=".2" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
                    <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
                  </svg>
                </button>
              </div>
              {errors.location && <span className="field-error">{errors.location}</span>}
              {!errors.location && locationProblem && <span className="field-error">{locationProblem}</span>}
              {!errors.location && !locationProblem && (
                <span className="hint">
                  {root ? `Must be inside ${root}. ` : ""}
                  {inspection?.valid ? inspection.message : "A missing folder is created."}
                </span>
              )}
            </div>
          </div>

          <div className="jb-row">
            <label htmlFor={`${uid}-type`}>Type:</label>
            <div className="jb-control">
              <select id={`${uid}-type`} className="jb-select" value={type} onChange={(e) => setType(e.target.value as ProjectType)}>
                {PROJECT_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
          </div>

          <div className="jb-row top">
            <label htmlFor={`${uid}-desc`}>Description:</label>
            <div className="jb-control">
              <textarea id={`${uid}-desc`} rows={3} maxLength={2000} placeholder="Optional" value={description} onChange={(e) => setDescription(e.target.value)} />
            </div>
          </div>

          <div className="jb-row">
            <span className="jb-label-spacer" />
            <div className="jb-control">
              <label className={`check${hasRepo ? " disabled" : ""}`}>
                <input type="checkbox" checked={hasRepo ? false : initGit} disabled={hasRepo} onChange={(e) => setInitGit(e.target.checked)} />
                <span>Initialize Git repository</span>
              </label>
              {hasRepo && <span className="hint">This folder already has a Git repository, so none will be created.</span>}
            </div>
          </div>

          {serverError && <div className="alert" role="alert">{serverError}</div>}
        </div>

        <div className="jb-footer">
          <button type="button" className="btn ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn" disabled={busy}>{busy ? "Creating…" : "Create Project"}</button>
        </div>
      </form>
      {picking && (
        <FolderPicker
          initialPath={location}
          onClose={() => setPicking(false)}
          onSelect={(p) => {
            setLocation(p);
            setErrors((e) => ({ ...e, location: "" }));
            setPicking(false);
          }}
        />
      )}
    </div>
  );
}
