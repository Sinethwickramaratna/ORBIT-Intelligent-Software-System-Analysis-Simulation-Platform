"use client";

import type { ProjectScan } from "@/lib/api";

interface Props {
  /** The newest scan, or null when the project has not been scanned (or it is still being loaded). */
  scan: ProjectScan | null;
  /** True until the stored scan has been asked for. */
  loading: boolean;
  scanning: boolean;
  error: string | null;
}

const number = (n: number) => n.toLocaleString();
const percent = (p: number) => `${Number.isInteger(p) ? p : p.toFixed(1)}%`;

/** "Analysis" window under the Explorer: language shares of the newest scan as a horizontal bar chart. */
export default function AnalysisPanel({ scan, loading, scanning, error }: Props) {
  // largest first, whatever order the server sent
  const languages = scan ? [...scan.languages].sort((a, b) => b.lines - a.lines || b.files - a.files || a.language.localeCompare(b.language)) : [];

  return (
    <section className="analysis" aria-label="Project analysis" aria-busy={scanning}>
      <div className="analysis-head">
        <span className="sidebar-title">ANALYSIS</span>
        {scanning && <span className="analysis-status" role="status">Scanning…</span>}
      </div>

      <div className="analysis-body">
        {error && <div className="analysis-error" role="alert">{error}</div>}

        {!scan && !error && (
          <p className="analysis-empty">
            {loading ? "Loading…" : "This project has not been scanned yet. Press Scan to detect the languages it uses."}
          </p>
        )}

        {scan && (
          <>
            <dl className="analysis-summary">
              <div>
                <dt>Scanned</dt>
                <dd>{new Date(scan.scannedAt).toLocaleString()}</dd>
              </div>
              <div>
                <dt>Files</dt>
                <dd>{number(scan.totalFiles)}</dd>
              </div>
              <div>
                <dt>Lines of code</dt>
                <dd>{number(scan.totalLines)}</dd>
              </div>
            </dl>

            <h3 className="analysis-sub">Languages</h3>
            {languages.length === 0 ? (
              <p className="analysis-empty">No source files of a known language were found.</p>
            ) : (
              <ul className="lang-list">
                {languages.map((l) => (
                  <li key={l.language} className="lang-row">
                    <div className="lang-line">
                      <span className="lang-name">{l.language}</span>
                      <span className="lang-pct">{percent(l.percentage)}</span>
                    </div>
                    <div className="lang-track" role="img" aria-label={`${l.language} ${percent(l.percentage)}`}>
                      <div className="lang-fill" style={{ width: `${Math.max(l.percentage, l.lines > 0 ? 1 : 0)}%` }} />
                    </div>
                    <div className="lang-detail">
                      {number(l.files)} {l.files === 1 ? "file" : "files"} · {number(l.lines)} {l.lines === 1 ? "line" : "lines"}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </div>
    </section>
  );
}
