"use client";

import type { FrameworkEvidence, ProjectScan } from "@/lib/api";

interface Props {
  /** The newest scan, or null when the project has not been scanned (or it is still being loaded). */
  scan: ProjectScan | null;
  loading: boolean;
  scanning: boolean;
  /** Hidden = only the title bar is shown. */
  open: boolean;
  onToggle: () => void;
}

/** The evidence of one framework grouped by the manifest it was read from (input order kept: shallowest first). */
function groupByFile(evidence: FrameworkEvidence[]): { filePath: string; hits: FrameworkEvidence[] }[] {
  const groups = new Map<string, FrameworkEvidence[]>();
  for (const e of evidence) {
    const list = groups.get(e.filePath);
    if (list) list.push(e);
    else groups.set(e.filePath, [e]);
  }
  return [...groups].map(([filePath, hits]) => ({ filePath, hits }));
}

/** Folder part and file name of a project-relative path, so the file name can stand out. */
function splitPath(path: string): { dir: string; base: string } {
  const cut = path.lastIndexOf("/");
  return cut < 0 ? { dir: "", base: path } : { dir: path.slice(0, cut + 1), base: path.slice(cut + 1) };
}

/**
 * "Framework" window: every framework the newest scan detected, with its evidence - the dependency declarations
 * that prove it, grouped by manifest file and shown with their line and column ("pom.xml  9:19  spring-boot-starter-web").
 */
export default function FrameworkPanel({ scan, loading, scanning, open, onToggle }: Props) {
  const found = scan?.frameworks ?? [];

  return (
    <section className="fw" aria-label="Frameworks" aria-busy={scanning}>
      <div className="fw-head">
        <button
          type="button"
          className="fw-toggle"
          onClick={onToggle}
          aria-expanded={open}
          aria-controls="fw-body"
          title={open ? "Hide frameworks" : "Show frameworks"}
        >
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" style={{ transform: open ? "rotate(90deg)" : "none", transition: "transform .1s" }}>
            <path d="m9 6 6 6-6 6" />
          </svg>
          <span className="sidebar-title">FRAMEWORK</span>
        </button>
        {scan && <span className="fw-status">{found.length}</span>}
      </div>

      <div className="fw-body" id="fw-body" hidden={!open}>
        {!scan && <p className="fw-empty">{loading ? "Loading…" : "Not scanned yet. Press Scan to detect the frameworks."}</p>}
        {scan && found.length === 0 && <p className="fw-empty">No framework was detected in the dependency files of this project.</p>}
        {found.map((f) => {
          const files = groupByFile(f.evidence);
          return (
            <div key={f.name} className="fw-item">
              <div className="fw-name">
                <span className="fw-label">Framework:</span> {f.name}
                <span className="fw-count" title={`${f.evidence.length} dependency declaration${f.evidence.length === 1 ? "" : "s"}`}>{f.evidence.length}</span>
              </div>
              <div className="fw-label">Evidence:</div>
              {files.map((g) => {
                const { dir, base } = splitPath(g.filePath);
                return (
                  <div key={g.filePath} className="fw-file">
                    <div className="fw-path" title={g.filePath}>
                      {dir && <span className="fw-dir">{dir}</span>}
                      <span className="fw-base">{base}</span>
                    </div>
                    <ul className="fw-hits">
                      {g.hits.map((e) => (
                        <li key={`${e.line}:${e.column}`} className="fw-hit" title={`${g.filePath}, line ${e.line}, column ${e.column}`}>
                          <span className="fw-pos">{e.line}:{e.column}</span>
                          <span className="fw-dep">{e.dependency}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                );
              })}
            </div>
          );
        })}
      </div>
    </section>
  );
}
