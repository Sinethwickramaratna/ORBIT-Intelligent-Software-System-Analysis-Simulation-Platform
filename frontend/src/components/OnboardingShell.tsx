import type { ReactNode } from "react";

const STEPS = ["Theme", "Environment", "Account", "Sign in"];

export type OnboardingStep = 0 | 1 | 2 | 3;

interface Props {
  step: OnboardingStep;
  wide?: boolean;
  children: ReactNode;
}

/** Branded split layout with a progress stepper (Theme → Environment → Account → Sign in). */
export default function OnboardingShell({ step, wide, children }: Props) {
  return (
    <main className="shell">
      <aside className="brand-panel">
        <div className="brand-top">
          <h1 className="brand-mark">Orbit</h1>
          <p className="brand-tag">Intelligent software system analysis &amp; simulation platform.</p>
        </div>
        <div className="brand-foot">Local-first · your data stays on this computer</div>
      </aside>
      <section className="content-panel">
        <div className={wide ? "card wide" : "card"}>
          <ol className="stepper" aria-label="Setup progress">
            {STEPS.map((label, i) => (
              <li key={label} className={i < step ? "done" : i === step ? "current" : ""} aria-current={i === step ? "step" : undefined}>
                <div className="bar" />
                {i + 1}. {label}
              </li>
            ))}
          </ol>
          {children}
        </div>
      </section>
    </main>
  );
}
