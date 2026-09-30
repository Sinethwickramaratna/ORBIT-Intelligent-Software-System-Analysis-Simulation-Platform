"use client";

import { useState } from "react";
import { ApiError, api } from "@/lib/api";
import { THEMES, applyTheme, markThemeChosen } from "@/lib/themes";
import OnboardingShell from "./OnboardingShell";

interface Props {
  initial: string;
  onDone: () => void;
}

export default function ThemePicker({ initial, onDone }: Props) {
  const [selected, setSelected] = useState(initial);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function choose(id: string) {
    setSelected(id);
    applyTheme(id); // live preview
  }

  async function save() {
    setSaving(true);
    setError(null);
    markThemeChosen(); // remembered in this browser; copied to the database as soon as it exists
    try {
      await api.saveTheme(selected);
    } catch (e) {
      // 503 = the database has not been created yet (first run). The choice is synced later.
      if (!(e instanceof ApiError && e.status === 503)) {
        setError(e instanceof ApiError ? e.message : "Could not save your theme");
        setSaving(false);
        return;
      }
    }
    setSaving(false);
    onDone();
  }

  return (
    <OnboardingShell step={0} wide>
      <div className="eyebrow">Welcome</div>
      <h2 className="title">Pick your look</h2>
      <p className="lead">Choose a colour theme. You are previewing it live — it is saved to your local database once it has been created.</p>
      {error && <div className="alert" role="alert">{error}</div>}
      <div className="theme-grid" role="radiogroup" aria-label="Colour theme">
        {THEMES.map((t) => (
          <button key={t.id} type="button" role="radio" aria-checked={selected === t.id} className="theme-card" onClick={() => choose(t.id)}>
            <div className="swatch" aria-hidden="true">
              {t.preview.map((c, i) => (
                <span key={i} style={{ background: c }} />
              ))}
            </div>
            <strong>{t.label}</strong>
            <small>{t.description}</small>
          </button>
        ))}
      </div>
      <button className="btn" type="button" onClick={save} disabled={saving}>
        {saving ? "Saving…" : "Continue"}
      </button>
    </OnboardingShell>
  );
}
