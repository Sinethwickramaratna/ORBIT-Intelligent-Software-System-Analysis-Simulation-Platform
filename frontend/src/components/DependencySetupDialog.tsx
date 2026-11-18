"use client";

import { useEffect, useRef, useState } from "react";
import { ApiError, type DependencyCheck } from "@/lib/api";

interface Props {
  projectName: string;
  location: string;
  check: DependencyCheck;
  onCancel: () => void;
  onConfirm: () => Promise<void>;
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
  const confirmRef = useRef<HTMLButtonElement>(null);
  const units = check.units.filter((u) => u.missing > 0 || u.status === "MISSING");

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
      await onConfirm();
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
            <code>{location}</code>, built for <strong>{check.targetPlatform}</strong>. Your own files are not changed;
            install scripts of packages are not run. This can take a few minutes for large projects.
          </p>
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
