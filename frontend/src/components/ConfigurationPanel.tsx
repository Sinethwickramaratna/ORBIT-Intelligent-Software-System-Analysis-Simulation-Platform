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

/** "Configuration" window: every configuration file the newest scan found and where it is (the evidence). */
export default function ConfigurationPanel({ scan, loading, scanning, open, onToggle }: Props) {
  const found = scan?.configurationFiles ?? [];

  return (
    <section className="config" aria-label="Configuration files" aria-busy={scanning}>
      <div className="config-head">
        <button
          type="button"
          className="config-toggle"
          onClick={onToggle}
          aria-expanded={open}
          aria-controls="config-body"
          title={open ? "Hide configuration files" : "Show configuration files"}
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ transform: open ? "rotate(90deg)" : "none", transition: "transform .1s" }}>
            <path d="m9 6 6 6-6 6" />
          </svg>
          <span className="sidebar-title">CONFIGURATION</span>
        </button>
        {scan && <span className="config-status">{found.length}</span>}
      </div>

      <div className="config-body" id="config-body" hidden={!open}>
        {!scan && <p className="config-empty">{loading ? "Loading…" : "Not scanned yet. Press Scan to detect the configuration files."}</p>}
        {scan && found.length === 0 && <p className="config-empty">No configuration file was detected in this project.</p>}
        {found.map((c) => (
          <div key={c.fileName} className="config-item">
            <div className="config-name">
              <span className="config-label">Configuration File:</span> {c.fileName}
            </div>
            <div className="config-label">Evidence:</div>
            <ul className="config-evidence">
              {c.locations.map((p) => (
                <li key={p} title={p}>{p}</li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </section>
  );
}
