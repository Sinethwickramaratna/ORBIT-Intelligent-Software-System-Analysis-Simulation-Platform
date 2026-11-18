"use client";

import { useEffect, useRef, useState } from "react";
import type { DependencyProgressStep, DependencySetupProgress } from "@/lib/api";
import { CheckMark, CrossMark } from "./DependencyToolbar";

interface Props {
  projectName: string;
  progress: DependencySetupProgress;
  onClose: () => void;
  onCancel: () => void;
}

function elapsed(from: string | null, to: string | null, now: number): string {
  if (!from) return "0:00";
  const ms = Math.max(0, (to ? Date.parse(to) : now) - Date.parse(from));
  const s = Math.floor(ms / 1000);
  const m = Math.floor(s / 60);
  return m >= 60 ? `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}` : `${m}:${String(s % 60).padStart(2, "0")}`;
}

function StepIcon({ state }: { state: DependencyProgressStep["state"] }) {
  if (state === "DONE") return <span className="dep-icon ok step" aria-label="Done"><CheckMark size={9} /></span>;
  if (state === "FAILED") return <span className="dep-icon missing step" aria-label="Failed"><CrossMark size={8} /></span>;
  if (state === "RUNNING") return <span className="dep-icon busy step" aria-label="Running" />;
  return <span className="dep-icon pending step" aria-label="Waiting" />;
}

/**
 * The progress window of Setup Dependencies: overall bar, one row per manifest group (waiting / running / done /
 * failed), the package being downloaded, counters, elapsed time and the activity log. When the setup has ended it
 * stays open as the record of what happened, until the user closes it.
 */
export default function DependencyProgressDialog({ projectName, progress, onClose, onCancel }: Props) {
  const running = progress.state === "RUNNING";
  const [now, setNow] = useState(() => Date.now());
  const [cancelling, setCancelling] = useState(false);
  const logRef = useRef<HTMLPreElement>(null);
  const followLog = useRef(true);

  useEffect(() => {
    if (!running) return;
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, [running]);

  // keep the log scrolled to the newest line unless the user scrolled up to read
  useEffect(() => {
    const el = logRef.current;
    if (el && followLog.current) el.scrollTop = el.scrollHeight;
  }, [progress.log]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const ok = progress.state === "SUCCEEDED";
  const title = running
    ? "Setting Up Dependencies"
    : ok
      ? "Dependencies Installed"
      : progress.state === "CANCELLED"
        ? "Setup Cancelled"
        : "Setup Finished With Problems";
  const headline = running ? progress.phase ?? "Working…" : progress.phase ?? "";
  const stepNo = progress.unitCount > 0 ? Math.min(progress.unitIndex + 1, progress.unitCount) : 0;

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal jb-modal dep-progress-modal" role="dialog" aria-modal="true" aria-labelledby="dep-progress-title">
        <div className="jb-titlebar">
          <span className="jb-title" id="dep-progress-title">{title} · {projectName}</span>
          <button type="button" className="jb-close" onClick={onClose} aria-label={running ? "Hide (setup keeps running)" : "Close"}>×</button>
        </div>

        <div className="jb-body dep-progress-body">
          <div className="dep-progress-head" aria-live="polite">
            {running ? (
              <span className="dep-icon busy" style={{ width: 30, height: 30 }} aria-hidden="true" />
            ) : ok ? (
              <span className="dep-icon ok" style={{ width: 30, height: 30 }} aria-hidden="true"><CheckMark size={18} /></span>
            ) : (
              <span className="dep-icon missing" style={{ width: 30, height: 30 }} aria-hidden="true"><CrossMark size={16} /></span>
            )}
            <div className="dep-progress-headline">
              <strong>{headline}</strong>
              {running && progress.current && <span className="dep-progress-current" title={progress.current}>{progress.current}</span>}
            </div>
            <span className="dep-progress-time" title="Elapsed time">{elapsed(progress.startedAt, progress.finishedAt, now)}</span>
          </div>

          <div className="dep-progress-bar big" role="progressbar" aria-label="Setup progress" aria-valuemin={0} aria-valuemax={100} aria-valuenow={progress.percent}>
            <span className={running ? "" : ok ? "ok" : "bad"} style={{ width: `${progress.percent}%` }} />
          </div>
          <div className="dep-progress-row">
            <span>{progress.unitCount > 0 ? `Step ${stepNo} of ${progress.unitCount}` : running ? "Preparing…" : "Nothing had to be downloaded"}</span>
            <span>
              <span className="dep-count ok">{progress.installed} installed</span>
              {progress.failed > 0 && <span className="dep-count bad"> · {progress.failed} failed</span>}
              {" · "}
              {progress.percent}%
            </span>
          </div>

          {progress.steps.length > 0 && (
            <ol className="dep-steps">
              {progress.steps.map((s, i) => (
                <li key={i} className={`dep-step ${s.state.toLowerCase()}`}>
                  <StepIcon state={s.state} />
                  <span className="dep-step-title" title={s.title}>{s.title}</span>
                  <span className="dep-step-summary">
                    {s.state === "RUNNING" ? "working…" : s.state === "PENDING" ? "waiting" : s.summary}
                  </span>
                </li>
              ))}
            </ol>
          )}

          {progress.changes.length > 0 && (
            <div className="dep-changes" role="status">
              <strong>Updated in your project files ({progress.changes.length}):</strong>
              <ul>
                {progress.changes.map((c, i) => (
                  <li key={i} title={c.reason}>
                    <code>{c.file}</code>: <span className="dep-name">{c.dependency}</span>{" "}
                    <span className="dep-change-from">{c.from || "(unpinned)"}</span> → <span className="dep-change-to">{c.to}</span>
                    <span className="dep-change-why">{c.reason}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {!running && progress.failures.length > 0 && (
            <div className="alert dep-failures" role="alert">
              <strong>Could not be set up ({progress.failures.length}):</strong>
              <ul>
                {progress.failures.slice(0, 30).map((f, i) => (
                  <li key={i}>{f}</li>
                ))}
                {progress.failures.length > 30 && <li>… and {progress.failures.length - 30} more (see the activity log)</li>}
              </ul>
            </div>
          )}

          <details className="dep-log-box" open>
            <summary>Activity log ({progress.log.length} lines)</summary>
            <pre
              className="dep-log"
              ref={logRef}
              onScroll={(e) => {
                const el = e.currentTarget;
                followLog.current = el.scrollTop + el.clientHeight >= el.scrollHeight - 8;
              }}
            >
              {progress.log.length ? progress.log.join("\n") : "Waiting for the first step…"}
            </pre>
          </details>
        </div>

        <div className="jb-footer">
          {running ? (
            <>
              <button
                type="button"
                className="btn ghost"
                disabled={cancelling}
                onClick={() => {
                  setCancelling(true);
                  onCancel();
                }}
              >
                {cancelling ? "Cancelling…" : "Cancel Setup"}
              </button>
              <button type="button" className="btn" onClick={onClose}>Run in Background</button>
            </>
          ) : (
            <button type="button" className="btn" onClick={onClose} autoFocus>Close</button>
          )}
        </div>
      </div>
    </div>
  );
}
