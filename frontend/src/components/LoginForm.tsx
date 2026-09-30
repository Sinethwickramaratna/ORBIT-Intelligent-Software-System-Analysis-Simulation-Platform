"use client";

import { FormEvent, useState } from "react";
import { ApiError, api, type User } from "@/lib/api";
import OnboardingShell from "./OnboardingShell";
import PasswordInput from "./PasswordInput";

interface Props {
  justRegistered: boolean;
  onLoggedIn: (user: User) => void;
}

export default function LoginForm({ justRegistered, onLoggedIn }: Props) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      onLoggedIn(await api.login(username.trim(), password));
    } catch (err) {
      // USER_NOT_FOUND → "No user exists with that username"; INVALID_PASSWORD → "Incorrect password"
      setError(err instanceof ApiError ? err.message : "Could not sign in");
    } finally {
      setBusy(false);
    }
  }

  return (
    <OnboardingShell step={3}>
      <div className="eyebrow">Welcome back</div>
      <h2 className="title">Sign in</h2>
      <p className="lead">Enter your username and password to continue.</p>
      {justRegistered && <div className="notice" role="status">Account created. Please sign in.</div>}
      {error && <div className="alert" role="alert">{error}</div>}
      <form onSubmit={submit} noValidate>
        <div className="field">
          <label htmlFor="login-username">Username</label>
          <input id="login-username" autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required autoFocus />
        </div>
        <div className="field">
          <label htmlFor="login-password">Password</label>
          <PasswordInput id="login-password" autoComplete="current-password" value={password} onChange={setPassword} required />
        </div>
        <button className="btn full" type="submit" disabled={busy || !username || !password}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </OnboardingShell>
  );
}
