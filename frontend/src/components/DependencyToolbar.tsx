"use client";

import type { Dependencies } from "@/lib/useDependencies";

export const CheckMark = ({ size = 14 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4.5 12.5l5 5L19.5 7" />
  </svg>
);

export const CrossMark = ({ size = 14 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.2" strokeLinecap="round" aria-hidden="true">
    <path d="M6 6l12 12M18 6 6 18" />
  </svg>
);

const ShieldIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6Z" />
    <path d="m9 12 2 2 4-4" />
  </svg>
);

const DownloadIcon = () => (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />
  </svg>
);

/** The green check / red X (or a spinner while checking or installing). */
export function DependencyStatusIcon({ dep, size = 22 }: { dep: Dependencies; size?: number }) {
  const { indicator, check } = dep;
  const missing = check?.missingDependencies ?? 0;
  const total = check?.totalDependencies ?? 0;
  if (indicator === "ok") {
    const label = check?.status === "NO_DEPENDENCIES" ? "No dependencies to install" : `All ${total} dependencies are installed`;
    return (
      <span className="dep-icon ok" role="img" aria-label={label} title={label} style={{ width: size, height: size }}>
        <CheckMark size={Math.round(size * 0.62)} />
      </span>
    );
  }
  if (indicator === "missing") {
    const label = `${missing} of ${total} dependencies are missing`;
    return (
      <span className="dep-icon missing" role="img" aria-label={label} title={label} style={{ width: size, height: size }}>
        <CrossMark size={Math.round(size * 0.58)} />
      </span>
    );
  }
  if (indicator === "error") {
    return (
      <span className="dep-icon unknown" role="img" aria-label="Dependency status unknown" title={dep.error ?? "Dependency status unknown"} style={{ width: size, height: size }}>
        ?
      </span>
    );
  }
  const label = indicator === "installing" ? "Setting up dependencies…" : "Checking dependencies…";
  return <span className="dep-icon busy" role="img" aria-label={label} title={label} style={{ width: size, height: size }} />;
}

/**
 * Controls of the open project (in the tab bar, next to Problems and the terminal): the Dependency Check button, the status icon (green check = everything
 * installed, red X = something missing) and, when something is missing, Setup Dependencies.
 */
export default function DependencyToolbar({ dep, compact = false }: { dep: Dependencies; compact?: boolean }) {
  const busy = dep.checking || dep.installing || dep.indicator === "loading";
  const showSetup = dep.indicator === "missing" || dep.installing;
  return (
    <div className={`dep-toolbar${compact ? " compact" : ""}`} role="group" aria-label="Dependencies">
      <button type="button" className="dep-btn" onClick={dep.runCheck} disabled={busy} title="Check that every dependency of the project is installed">
        <ShieldIcon />
        <span>{dep.checking ? "Checking…" : "Dependency Check"}</span>
      </button>
      <DependencyStatusIcon dep={dep} size={compact ? 16 : 22} />
      {dep.checking && dep.checkProgress && (
        <span className="dep-running" role="status" title={dep.checkProgress.phase ?? undefined}>
          <span className="dep-running-bar" aria-hidden="true">
            <span style={{ width: `${dep.checkProgress.percent}%` }} />
          </span>
          {dep.checkProgress.unitCount > 0
            ? `${Math.min(dep.checkProgress.unitIndex + 1, dep.checkProgress.unitCount)}/${dep.checkProgress.unitCount} · ${dep.checkProgress.percent}%`
            : "Reading project…"}
        </span>
      )}
      {showSetup &&
        (dep.installing ? (
          <button type="button" className="dep-running as-button" onClick={dep.openProgress}
            title={`${dep.setup?.phase ?? ""}${dep.setup?.current ? "\n" + dep.setup.current : ""}\nClick to see the progress`}>
            <span className="dep-running-bar" aria-hidden="true">
              <span style={{ width: `${dep.setup?.percent ?? 0}%` }} />
            </span>
            Setting up… {dep.setup?.percent ?? 0}% · {dep.setup?.installed ?? 0} installed
          </button>
        ) : (
          <button type="button" className="dep-btn primary" onClick={dep.askSetup} disabled={busy}>
            <DownloadIcon />
            <span>Setup Dependencies</span>
          </button>
        ))}
    </div>
  );
}
