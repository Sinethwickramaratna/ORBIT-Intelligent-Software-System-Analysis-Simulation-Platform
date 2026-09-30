"use client";

import { useState } from "react";
import { api, type User } from "@/lib/api";

export default function HomeScreen({ user, onLoggedOut }: { user: User; onLoggedOut: () => void }) {
  const [busy, setBusy] = useState(false);

  async function logout() {
    setBusy(true);
    try {
      await api.logout();
    } catch {
      /* the local session is cleared regardless */
    } finally {
      onLoggedOut();
    }
  }

  return (
    <main className="home">
      <p className="hello">Hello, {user.userName}</p>
      <h1>
        Welcome to <span className="gradient-text">Orbit</span>
      </h1>
      <p>Intelligent Software System Analysis &amp; Simulation Platform. Your workspace is ready — more modules are on the way.</p>
      <button className="btn ghost" type="button" onClick={logout} disabled={busy}>
        {busy ? "Signing out…" : "Log out"}
      </button>
    </main>
  );
}
