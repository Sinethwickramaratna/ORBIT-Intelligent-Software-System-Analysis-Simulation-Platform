"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, type DependencyCheck } from "@/lib/api";

interface Props {
  projectName: string;
  location: string;
  check: DependencyCheck;
  onCancel: () => void;
  onConfirm: (updateManifests: boolean, skipUnused: boolean) => Promise<void>;
}

const REGISTRY: Record<string, string> = {
  npm: "registry.npmjs.org",
  pip: "pypi.org",
  maven: "Maven Central",
  gradle: "Maven Central / Google",
  nuget: "nuget.org",
  pub: "pub.dev",
};

/** Asks before anything is downloaded: what will be installed, from where, and into which folder of the project. */
export default function DependencySetupDialog({ projectName, location, check, onCancel, onConfirm }: Props) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [allowUpdates, setAllowUpdates] = useState(true);
  const [includeUnused, setIncludeUnused] = useState(false);
  const unused = check.units.flatMap((u) => u.dependencies.filter((d) => d.state === "UNUSED" && !d.installed));
  const confirmRef = useRef<HTMLButtonElement>(null);
  const units = check.units.filter((u) => u.missing > 0 || u.status === "MISSING");
  const hasPython = units.some((u) => u.ecosystem === "pip");

  useEffect(() => {
    confirmRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !busy) onCancel();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [busy, onCancel]);

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      await onConfirm(allowUpdates, !includeUnused);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Setup could not be started.");
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onCancel()}>
      <div className="modal jb-modal dep-confirm" role="alertdialog" aria-modal="true" aria-labelledby="dep-confirm-title" aria-describedby="dep-confirm-lead">
        <div className="jb-titlebar">
          <span className="jb-title" id="dep-confirm-title">Confirm Dependency Setup</span>
          <button type="button" className="jb-close" onClick={onCancel} aria-label="Close" disabled={busy}>×</button>
        </div>

        <div className="jb-body">
          <p className="delete-lead" id="dep-confirm-lead">
            Download and install <strong>{check.missingDependencies}</strong> missing{" "}
            {check.missingDependencies === 1 ? "dependency" : "dependencies"} of <strong>{projectName}</strong>?
          </p>
          <ul className="dep-confirm-list">
            {units.map((u) => (
              <li key={u.ecosystem + u.manifest}>
                <span className="dep-confirm-eco">{u.ecosystemLabel}</span>
                <code title={u.manifest}>{u.manifest}</code>
                <span className="dep-confirm-count">{u.missing} missing</span>
                {u.installLocation && (
                  <span className="dep-confirm-target">
                    from {REGISTRY[u.ecosystem] ?? "the package registry"} into <code>{u.installLocation}</code>
                  </span>
                )}
              </li>
            ))}
          </ul>
          <p className="hint">
            The packages (with the packages they depend on) are downloaded and saved in the project folder{" "}
            <code>{location}</code>, built for <strong>{check.targetPlatform}</strong>. Install scripts of packages are
            not run. This can take a few minutes for large projects.
          </p>
          {hasPython && (
            <p className="hint">
              Python packages go into the project&apos;s virtual environment (an existing one, or a new <code>.venv</code>{" "}
              next to the requirements), never into the Python installed on the computer.
            </p>
          )}
          {unused.length > 0 && (
            <label className="check dep-update-option">
              <input type="checkbox" checked={includeUnused} onChange={(e) => setIncludeUnused(e.target.checked)} disabled={busy} />
              <span>
                Also download the <strong>{unused.length}</strong> declared{" "}
                {unused.length === 1 ? "dependency" : "dependencies"} the code does not use (
                <span className="dep-unused-names" title={unused.map((d) => d.name).join(", ")}>
                  {unused.slice(0, 6).map((d) => d.name).join(", ")}
                  {unused.length > 6 ? `, … ${unused.length - 6} more` : ""}
                </span>
                ). Not needed to reconstruct the architecture.
              </span>
            </label>
          )}
          {hasPython && (
            <label className="check dep-update-option">
              <input type="checkbox" checked={allowUpdates} onChange={(e) => setAllowUpdates(e.target.checked)} disabled={busy} />
              <span>
                If a pinned version cannot be installed on {check.targetPlatform}, install the newest version that works
                (same major version first) and update the line in <code>requirements.txt</code>. The original file is kept
                as <code>requirements.txt.orbit-backup</code>.
              </span>
            </label>
          )}
          {!(hasPython && allowUpdates) && <p className="hint">Your own files are not changed.</p>}
          {error && <div className="alert" role="alert">{error}</div>}
        </div>

        <div className="jb-footer">
          <button type="button" className="btn ghost" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="button" className="btn" ref={confirmRef} onClick={confirm} disabled={busy}>
            {busy ? "Starting…" : "Yes, Set Up"}
          </button>
        </div>
      </div>
    </div>
  );
}
