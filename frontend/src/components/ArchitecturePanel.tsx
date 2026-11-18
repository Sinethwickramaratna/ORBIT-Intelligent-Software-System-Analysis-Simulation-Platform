"use client";

import type { DependencyItem, DependencyUnit } from "@/lib/api";
import type { Dependencies } from "@/lib/useDependencies";
import { CheckMark, CrossMark, DependencyStatusIcon } from "./DependencyToolbar";

const STATE_LABEL: Record<DependencyItem["state"], string> = {
  INSTALLED: "Installed",
  MISSING: "Missing",
  OUTDATED: "Wrong version",
  LOCAL: "In project",
  SDK: "From SDK",
  UNSUPPORTED: "Manual",
  UNUSED: "Not used",
};

function headline(dep: Dependencies): { title: string; detail: string } {
  const c = dep.check;
  switch (dep.indicator) {
    case "loading":
      return { title: "Loading…", detail: "Reading the last dependency check." };
    case "checking":
      return { title: "Checking dependencies…", detail: "Reading the project's manifests and what is installed." };
    case "installing":
      return { title: "Setting up dependencies…", detail: dep.setup?.phase ?? "" };
    case "ok":
      return c?.status === "NO_DEPENDENCIES"
        ? { title: "Nothing to install", detail: "The project declares no dependencies ORBIT can install." }
        : { title: "All dependencies are installed", detail: `${c?.totalDependencies ?? 0} dependencies checked.` };
    case "missing":
      return {
        title: `${c?.missingDependencies ?? 0} of ${c?.totalDependencies ?? 0} dependencies are missing`,
        detail: "Press Setup Dependencies in the top bar to download them into the project folder.",
      };
    default:
      return { title: "Dependency status unknown", detail: dep.error ?? "Press Dependency Check to try again." };
  }
}

function UnitRow({ unit }: { unit: DependencyUnit }) {
  const ok = unit.status !== "MISSING";
  const shown = unit.dependencies;
  const unused = shown.filter((d) => d.state === "UNUSED").length;
  return (
    <details className={`dep-unit${ok ? "" : " has-missing"}`} open={!ok && unit.missing <= 40}>
      <summary>
        <span className={`dep-icon ${ok ? "ok" : "missing"} small`} aria-hidden="true">
          {ok ? <CheckMark size={10} /> : <CrossMark size={9} />}
        </span>
        <span className="dep-unit-eco">{unit.ecosystemLabel}</span>
        <code className="dep-unit-manifest" title={unit.manifest}>{unit.manifest}</code>
        <span className="dep-unit-count">
          {unit.total === 0 ? "no dependencies" : ok ? `${unit.total - unused} installed` : `${unit.missing} of ${unit.total - unused} missing`}
          {unused > 0 && `, ${unused} not used`}
        </span>
      </summary>
      <div className="dep-unit-body">
        {unit.message && <p className="dep-unit-message">{unit.message}</p>}
        {unit.installLocation && unit.total > 0 && (
          <p className="dep-unit-message">
            Installed into <code>{unit.installLocation}</code>
          </p>
        )}
        {shown.length > 0 && (
          <table className="dep-table">
            <thead>
              <tr>
                <th scope="col">Dependency</th>
                <th scope="col">Required</th>
                <th scope="col">Installed</th>
                <th scope="col">State</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((d, i) => (
                <tr key={d.name + i} title={d.note ?? undefined}>
                  <td className="dep-name">
                    {d.name}
                    {d.scope && d.scope !== "runtime" && d.scope !== "compile" && d.scope !== "package" && (
                      <span className="dep-scope">{d.scope}</span>
                    )}
                  </td>
                  <td>{d.required ?? "—"}</td>
                  <td>{d.installed ?? "—"}</td>
                  <td>
                    <span className={`dep-state ${d.state.toLowerCase()}`}>{STATE_LABEL[d.state]}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </details>
  );
}

/**
 * Architecture Reconstruction workspace of the open project. Step 1 is the dependency check: the analysis needs every
 * library the code uses on disk, so the result of the check (and of Setup Dependencies) is shown here in detail.
 */
export default function ArchitecturePanel({ dep }: { dep: Dependencies }) {
  const { title, detail } = headline(dep);
  const setup = dep.setup;
  const units = dep.check?.units ?? [];
  const finished = setup && setup.state !== "RUNNING" && setup.state !== "IDLE";
  return (
    <section className="arch-panel" aria-labelledby="arch-title">
      <header className="arch-head">
        <h2 id="arch-title">Architecture Reconstruction</h2>
        <p>Step 1 · Dependencies — every library the project uses must be on disk before its architecture is reconstructed.</p>
      </header>

      <div className="dep-card" aria-live="polite">
        <div className="dep-card-status">
          <DependencyStatusIcon dep={dep} size={34} />
          <div>
            <h3>{title}</h3>
            <p>{detail}</p>
            {dep.check && (
              <p className="dep-card-meta">
                Checked {new Date(dep.check.checkedAt).toLocaleString()} · for {dep.check.targetPlatform}
              </p>
            )}
          </div>
        </div>

        {dep.checking && dep.checkProgress && (
          <div className="dep-progress">
            <div className="dep-progress-bar" role="progressbar" aria-label="Check progress" aria-valuemin={0} aria-valuemax={100}
              aria-valuenow={dep.checkProgress.percent}>
              <span style={{ width: `${dep.checkProgress.percent}%` }} />
            </div>
            <div className="dep-progress-row">
              <span className="dep-progress-phase" title={dep.checkProgress.phase ?? undefined}>
                {dep.checkProgress.current ?? dep.checkProgress.phase ?? "Starting…"}
              </span>
              <span>
                {dep.checkProgress.unitCount > 0
                  ? `${Math.min(dep.checkProgress.unitIndex + 1, dep.checkProgress.unitCount)} of ${dep.checkProgress.unitCount} · `
                  : ""}
                {dep.checkProgress.percent}%
              </span>
            </div>
          </div>
        )}

        {dep.installing && setup && (
          <div className="dep-progress">
            <div className="dep-progress-bar" role="progressbar" aria-label="Setup progress" aria-valuemin={0} aria-valuemax={100}
              aria-valuenow={setup.percent}>
              <span style={{ width: `${setup.percent}%` }} />
            </div>
            <div className="dep-progress-row">
              <span className="dep-progress-phase" title={setup.current ?? undefined}>
                {setup.unitCount > 0 ? `Step ${Math.min(setup.unitIndex + 1, setup.unitCount)} of ${setup.unitCount} · ` : ""}
                {setup.current ?? setup.phase}
              </span>
              <span>
                {setup.installed} installed{setup.failed > 0 ? ` · ${setup.failed} failed` : ""} · {setup.percent}%
              </span>
            </div>
            <div className="dep-progress-actions">
              <button type="button" className="btn ghost small" onClick={dep.openProgress}>Show Progress</button>
              <button type="button" className="btn ghost small" onClick={dep.cancelSetup}>Cancel</button>
            </div>
          </div>
        )}

        {finished && (
          <div className={`dep-last-run ${setup.state === "SUCCEEDED" ? "ok" : "bad"}`}>
            {setup.state === "SUCCEEDED" ? <CheckMark size={12} /> : <CrossMark size={11} />}
            <span>
              {setup.state === "SUCCEEDED"
                ? `Setup finished: ${setup.installed} packages installed, all dependencies are in place.` +
                  (setup.changes.length ? ` ${setup.changes.length} version${setup.changes.length === 1 ? " was" : "s were"} updated in your files.` : "")
                : setup.state === "CANCELLED"
                  ? `Setup was cancelled after ${setup.installed} packages.`
                  : `Setup finished: ${setup.installed} installed, ${setup.failures.length} could not be set up.`}
            </span>
            <button type="button" className="btn ghost small" onClick={dep.openProgress}>View Details</button>
          </div>
        )}
        {dep.error && dep.indicator !== "error" && <div className="alert" role="alert">{dep.error}</div>}
      </div>

      {units.length > 0 ? (
        <div className="dep-units">{units.map((u) => <UnitRow key={u.ecosystem + u.manifest} unit={u} />)}</div>
      ) : (
        dep.check && <p className="dep-empty">No dependency manifests (package.json, pom.xml, requirements.txt, …) were found in the project.</p>
      )}
    </section>
  );
}
