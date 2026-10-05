"use client";

import { useEffect, useId, useRef, useState, type FormEvent } from "react";
import FolderPicker from "./FolderPicker";
import { api, ApiError, PROJECT_TYPES, type Project, type ProjectType, type RepositoryInfo } from "@/lib/api";

interface Props {
  onClose: () => void;
  onCreated: (project: Project) => void;
}

function formatElapsed(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/**
 * "Clone Git Repository" window. Works for public http(s) repositories: check the repository, pick a branch from the
 * ones that really exist, choose where to clone, then clone and register the project.
 * The Authentication and Advanced Options sections are shown but inactive for now.
 */
export default function CloneDialog({ onClose, onCreated }: Props) {
  const uid = useId();
  const [url, setUrl] = useState("");
  const [info, setInfo] = useState<RepositoryInfo | null>(null);
  const [checking, setChecking] = useState(false);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [branch, setBranch] = useState("");
  const [location, setLocation] = useState("");
  const [name, setName] = useState("");
  const [nameTouched, setNameTouched] = useState(false);
  const [type, setType] = useState<ProjectType>("WEB_APPLICATION");
  const [description, setDescription] = useState("");
  const [analyze, setAnalyze] = useState(true);
  const [authOpen, setAuthOpen] = useState(false);
  const [advOpen, setAdvOpen] = useState(false);
  const [roots, setRoots] = useState<string[]>([]);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [elapsed, setElapsed] = useState(0);
  const [picking, setPicking] = useState(false);
  const checkSeq = useRef(0);
  const urlRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    urlRef.current?.focus();
    api.projectConfig().then((c) => setRoots(c.roots ?? (c.root ? [c.root] : []))).catch(() => {});
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !busy && !picking) onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, picking, onClose]);

  // Elapsed-time counter for the waiting screen.
  useEffect(() => {
    if (!busy) return;
    setElapsed(0);
    const t = setInterval(() => setElapsed((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, [busy]);

  // Leaving a running clone by closing the tab would not stop the server; at least warn.
  useEffect(() => {
    if (!busy) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy]);

  function changeUrl(value: string) {
    setUrl(value);
    // The check result belongs to the URL it was made for.
    checkSeq.current++;
    setInfo(null);
    setBranch("");
    setCheckError(null);
    setChecking(false);
    setErrors((e) => ({ ...e, repositoryUrl: "" }));
  }

  async function checkRepository() {
    const value = url.trim();
    if (!value) {
      setErrors((e) => ({ ...e, repositoryUrl: "Repository URL is required." }));
      return;
    }
    const seq = ++checkSeq.current;
    setChecking(true);
    setCheckError(null);
    setInfo(null);
    setServerError(null);
    try {
      const result = await api.inspectRepository(value);
      if (seq !== checkSeq.current) return; // the URL changed meanwhile
      if (result.found) {
        setInfo(result);
        setBranch(result.defaultBranch ?? result.branches[0] ?? "");
        if (!nameTouched && result.repository) setName(result.repository);
      } else {
        setCheckError(result.message ?? "Repository not found.");
      }
    } catch (err) {
      if (seq !== checkSeq.current) return;
      setCheckError(err instanceof ApiError ? err.message : "Could not reach the server.");
    } finally {
      if (seq === checkSeq.current) setChecking(false);
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (busy) return;
    const next: Record<string, string> = {};
    if (!url.trim()) next.repositoryUrl = "Repository URL is required.";
    else if (!info) next.repositoryUrl = "Check the repository first.";
    if (!location.trim()) next.location = "Clone location is required.";
    if (!name.trim()) next.projectName = "Project name is required.";
    setErrors(next);
    setServerError(null);
    if (Object.keys(next).length || !info) return;

    setBusy(true);
    try {
      const project = await api.cloneProject({
        repositoryUrl: url.trim(),
        branch,
        location: location.trim(),
        projectName: name.trim(),
        projectType: type,
        description: description.trim() || undefined,
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

  const cloneTarget = info && location.trim() ? `${location.trim().replace(/[\\/]+$/, "")}/${info.repository}` : null;
  const canClone = !!info && !!branch && !!location.trim() && !!name.trim() && !busy;

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onClose()}>
      <form className="modal jb-modal clone-modal" role="dialog" aria-modal="true" aria-labelledby={`${uid}-title`} onSubmit={submit} noValidate>
        <div className="jb-titlebar">
          <span className="jb-title" id={`${uid}-title`}>Clone Git Repository</span>
          <button type="button" className="jb-close" onClick={onClose} disabled={busy} aria-label="Close">×</button>
        </div>

        {busy ? (
          <div className="clone-progress" role="status" aria-live="polite">
            <div className="orbit-anim" aria-hidden="true">
              <span className="orbit-ring r1" />
              <span className="orbit-ring r2" />
              <span className="orbit-core" />
              <span className="orbit-dot d1" />
              <span className="orbit-dot d2" />
            </div>
            <h3>Cloning {info?.repository}…</h3>
            <p className="clone-progress-sub">
              Branch <strong>{branch}</strong> → <code>{cloneTarget}</code>
            </p>
            <div className="clone-progress-bar"><span /></div>
            <p className="clone-progress-time">Elapsed {formatElapsed(elapsed)}</p>
            <p className="hint">Large repositories can take a few minutes. Keep this window open - the project is added when the clone is finished.</p>
          </div>
        ) : (
          <>
            <div className="jb-body clone-grid">
              <div className="clone-form">
                <div className="jb-row">
                  <label htmlFor={`${uid}-url`}>Repository URL</label>
                  <div className="jb-control">
                    <div className="url-row">
                      <input
                        id={`${uid}-url`}
                        ref={urlRef}
                        value={url}
                        maxLength={2048}
                        placeholder="https://github.com/user/project.git"
                        onChange={(e) => changeUrl(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") {
                            e.preventDefault();
                            void checkRepository();
                          }
                        }}
                      />
                      <button type="button" className="btn ghost small-btn check-btn" onClick={() => void checkRepository()} disabled={checking || !url.trim()}>
                        {checking ? "Checking…" : "Check Repository"}
                      </button>
                    </div>
                    {errors.repositoryUrl && <span className="field-error">{errors.repositoryUrl}</span>}
                    {errors.repositoryUrl ? null : <span className="hint">Public repositories only for now (http/https).</span>}
                  </div>
                </div>

                <div className="jb-row">
                  <label htmlFor={`${uid}-branch`}>Branch</label>
                  <div className="jb-control">
                    <select
                      id={`${uid}-branch`}
                      className="jb-select"
                      value={branch}
                      disabled={!info}
                      onChange={(e) => setBranch(e.target.value)}
                    >
                      {!info && <option value="">Check the repository to list its branches</option>}
                      {info?.branches.map((b) => (
                        <option key={b} value={b}>
                          {b}{b === info.defaultBranch ? " (default)" : ""}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                <div className="jb-row">
                  <label htmlFor={`${uid}-loc`}>Clone Location</label>
                  <div className="jb-control">
                    <div className="input-with-button">
                      <input
                        id={`${uid}-loc`}
                        value={location}
                        maxLength={1024}
                        placeholder={roots[0] ? `${roots[0].replace(/\/$/, "")}/Projects` : "Folder to clone into"}
                        onChange={(e) => {
                          setLocation(e.target.value);
                          setErrors((er) => ({ ...er, location: "" }));
                        }}
                      />
                      <button type="button" className="folder-btn" onClick={() => setPicking(true)} aria-label="Browse for a folder" title="Browse…">
                        <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" fillOpacity=".2" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" aria-hidden="true">
                          <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z" />
                        </svg>
                      </button>
                    </div>
                    {errors.location && <span className="field-error">{errors.location}</span>}
                    {!errors.location && (
                      <span className="hint">
                        {cloneTarget ? <>Will be cloned to <code>{cloneTarget}</code></> : "The repository gets its own folder inside this location."}
                      </span>
                    )}
                  </div>
                </div>

                <div className="jb-row">
                  <label htmlFor={`${uid}-name`}>Project Name</label>
                  <div className="jb-control">
                    <input
                      id={`${uid}-name`}
                      value={name}
                      maxLength={150}
                      placeholder="my-project"
                      onChange={(e) => {
                        setName(e.target.value);
                        setNameTouched(true);
                      }}
                    />
                    {errors.projectName && <span className="field-error">{errors.projectName}</span>}
                  </div>
                </div>

                <div className="jb-row">
                  <label htmlFor={`${uid}-type`}>Type</label>
                  <div className="jb-control">
                    <select id={`${uid}-type`} className="jb-select" value={type} onChange={(e) => setType(e.target.value as ProjectType)}>
                      {PROJECT_TYPES.map((t) => (
                        <option key={t.value} value={t.value}>{t.label}</option>
                      ))}
                    </select>
                  </div>
                </div>

                <div className="jb-row top">
                  <label htmlFor={`${uid}-desc`}>Description</label>
                  <div className="jb-control">
                    <textarea id={`${uid}-desc`} rows={3} maxLength={2000} placeholder="Optional" value={description} onChange={(e) => setDescription(e.target.value)} />
                  </div>
                </div>

                <div className="jb-row">
                  <span className="jb-label-spacer" />
                  <div className="jb-control">
                    <label className="check">
                      <input type="checkbox" checked={analyze} onChange={(e) => setAnalyze(e.target.checked)} />
                      <span>Analyze repository after cloning</span>
                    </label>
                  </div>
                </div>

                <div className="clone-section">
                  <button type="button" className="clone-section-head" aria-expanded={authOpen} onClick={() => setAuthOpen((o) => !o)}>
                    <span className={`caret${authOpen ? " open" : ""}`} aria-hidden="true">▸</span> Authentication
                  </button>
                  {authOpen && (
                    <div className="clone-section-body">
                      <fieldset disabled>
                        <legend>Authentication Method</legend>
                        <label className="check"><input type="radio" name={`${uid}-auth`} defaultChecked /><span>None</span></label>
                        <label className="check"><input type="radio" name={`${uid}-auth`} /><span>SSH Key</span></label>
                        <label className="check"><input type="radio" name={`${uid}-auth`} /><span>Personal Access Token</span></label>
                        <div className="input-with-button static">
                          <input aria-label="SSH key path" placeholder="~/.ssh/id_ed25519" />
                          <button type="button" className="btn ghost small-btn">…</button>
                        </div>
                        <button type="button" className="btn ghost small-btn test-conn">Test Connection</button>
                      </fieldset>
                      <span className="hint">Not available yet - only public repositories can be cloned.</span>
                    </div>
                  )}
                </div>

                <div className="clone-section">
                  <button type="button" className="clone-section-head" aria-expanded={advOpen} onClick={() => setAdvOpen((o) => !o)}>
                    <span className={`caret${advOpen ? " open" : ""}`} aria-hidden="true">▸</span> Advanced Options
                  </button>
                  {advOpen && (
                    <div className="clone-section-body">
                      <fieldset disabled>
                        <legend>Git depth</legend>
                        <label className="check"><input type="radio" name={`${uid}-depth`} defaultChecked /><span>Full clone</span></label>
                        <label className="check"><input type="radio" name={`${uid}-depth`} /><span>Shallow clone</span></label>
                        <div className="depth-row">
                          <label htmlFor={`${uid}-depthn`}>Depth:</label>
                          <input id={`${uid}-depthn`} type="number" min={1} defaultValue={1} />
                        </div>
                        <label className="check"><input type="checkbox" /><span>Initialize submodules</span></label>
                        <label className="check"><input type="checkbox" /><span>Clone LFS files</span></label>
                        <label className="check"><input type="checkbox" /><span>Automatically analyze repository</span></label>
                      </fieldset>
                      <span className="hint">Not available yet - a full clone of the chosen branch is made.</span>
                    </div>
                  )}
                </div>

                {serverError && <div className="alert" role="alert">{serverError}</div>}
              </div>

              <aside className="clone-side" aria-live="polite" aria-label="Repository details">
                {info ? (
                  <div className="repo-card found">
                    <div className="repo-card-head"><span className="repo-ok" aria-hidden="true">✓</span> Repository found</div>
                    <dl>
                      <dt>Repository</dt><dd>{info.repository}</dd>
                      <dt>Owner</dt><dd>{info.owner}</dd>
                      <dt>Default</dt><dd>{info.defaultBranch}</dd>
                      <dt>Branches</dt><dd>{info.branchCount}</dd>
                      <dt>Visibility</dt><dd>{info.visibility}</dd>
                    </dl>
                  </div>
                ) : checkError ? (
                  <div className="repo-card failed" role="alert">
                    <div className="repo-card-head"><span className="repo-bad" aria-hidden="true">✕</span> Repository not available</div>
                    <p>{checkError}</p>
                  </div>
                ) : (
                  <div className="repo-card idle">
                    <div className="repo-card-head">{checking ? "Checking repository…" : "Repository details"}</div>
                    <p>{checking ? "Contacting the remote." : "Enter a URL and press Check Repository. The details and the branches to choose from show up here."}</p>
                  </div>
                )}
              </aside>
            </div>

            <div className="jb-footer">
              <button type="button" className="btn ghost" onClick={onClose}>Cancel</button>
              <button type="submit" className="btn" disabled={!canClone} title={info ? undefined : "Check the repository first"}>Clone Repository</button>
            </div>
          </>
        )}
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
