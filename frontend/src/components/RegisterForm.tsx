"use client";

import { FormEvent, useState } from "react";
import { ApiError, api } from "@/lib/api";
import OnboardingShell from "./OnboardingShell";
import PasswordInput from "./PasswordInput";

export default function RegisterForm({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setFieldErrors({});
    if (password !== confirm) {
      setFieldErrors({ confirm: "Passwords do not match" });
      return;
    }
    setSaving(true);
    try {
      await api.register(username.trim(), password);
      onDone();
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
        setFieldErrors(err.fieldErrors ?? {});
      } else {
        setError("Could not create the account");
      }
    } finally {
      setSaving(false);
    }
  }

  return (
    <OnboardingShell step={2}>
      <div className="eyebrow">First run</div>
      <h2 className="title">Create your account</h2>
      <p className="lead">No users exist yet. Register a username and password — they are stored in your local database (the password as an Argon2 hash).</p>
      {error && <div className="alert" role="alert">{error}</div>}
      <form onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="username">Username</label>
          <input id="username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
          {fieldErrors.username && <span className="field-error">{fieldErrors.username}</span>}
        </div>
        <div className="field">
          <label htmlFor="password">Password</label>
          <PasswordInput id="password" autoComplete="new-password" value={password} onChange={setPassword} required />
          {fieldErrors.password ? <span className="field-error">{fieldErrors.password}</span> : <span className="hint">At least 8 characters</span>}
        </div>
        <div className="field">
          <label htmlFor="confirm">Confirm password</label>
          <PasswordInput id="confirm" autoComplete="new-password" value={confirm} onChange={setConfirm} required />
          {fieldErrors.confirm && <span className="field-error">{fieldErrors.confirm}</span>}
        </div>
        <button className="btn full" type="submit" disabled={saving}>
          {saving ? "Creating…" : "Register"}
        </button>
      </form>
    </OnboardingShell>
  );
}
