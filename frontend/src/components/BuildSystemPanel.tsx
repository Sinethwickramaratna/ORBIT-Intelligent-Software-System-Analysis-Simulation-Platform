"use client";

import type { ProjectScan } from "@/lib/api";

interface Props {
  /** The newest scan, or null when the project has not been scanned (or it is still being loaded). */
  scan: ProjectScan | null;
  loading: boolean;
  scanning: boolean;
  /** Hidden = only the title bar is shown. */
  open: boolean;
  onToggle: () => void;
}

/** "Build System" window under Analysis: every build system the newest scan found, each with its evidence files. */
export default function BuildSystemPanel({ scan, loading, scanning, open, onToggle }: Props) {
  const found = scan?.buildSystems ?? [];

  return (
    <section className="build" aria-label="Build systems" aria-busy={scanning}>
      <div className="build-head">
        <button
          type="button"
          className="build-toggle"
          onClick={onToggle}
          aria-expanded={open}
          aria-controls="build-body"
          title={open ? "Hide build systems" : "Show build systems"}
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ transform: open ? "rotate(90deg)" : "none", transition: "transform .1s" }}>
            <path d="m9 6 6 6-6 6" />
          </svg>
          <span className="sidebar-title">BUILD SYSTEM</span>
        </button>
        {scan && <span className="build-status">{found.length}</span>}
      </div>

      <div className="build-body" id="build-body" hidden={!open}>
        {!scan && <p className="build-empty">{loading ? "Loading…" : "Not scanned yet. Press Scan to detect the build systems."}</p>}
        {scan && found.length === 0 && <p className="build-empty">No build system was detected in this project.</p>}
        {found.map((b) => (
          <div key={b.name} className="build-item">
            <div className="build-name">
              <span className="build-label">Build System:</span> {b.name}
            </div>
            <div className="build-label">Evidence:</div>
            <ul className="build-evidence">
              {b.evidence.map((f) => (
                <li key={f} title={f}>{f}</li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </section>
  );
}
