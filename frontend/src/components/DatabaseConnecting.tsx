"use client";

import { useState } from "react";
import { ApiError, api, type SetupStatus } from "@/lib/api";
import OnboardingShell from "./OnboardingShell";

interface Props {
  status: SetupStatus;
  onRetry: (next: SetupStatus) => void;
}

/** Shown while PostgreSQL starts with the new credentials and the schema is created (AppShell polls the status). */
export default function DatabaseConnecting({ status, onRetry }: Props) {
  const failed = status.databaseState === "FAILED";
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function retry() {
    setBusy(true);
    setError(null);
    try {
      onRetry(await api.retryDatabase());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Retry failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <OnboardingShell step={1}>
      <div className="eyebrow">Database</div>
      <h2 className="title">{failed ? "Database problem" : "Creating your database"}</h2>
      {failed ? (
        <>
          <div className="alert" role="alert">{status.databaseMessage}</div>
          {error && <div className="alert" role="alert">{error}</div>}
          <button className="btn" type="button" onClick={retry} disabled={busy}>
            {busy ? "Retrying…" : "Retry"}
          </button>
        </>
      ) : (
        <div role="status" aria-live="polite" style={{ display: "flex", alignItems: "center", gap: 14 }}>
          <div className="spinner" />
          <span className="lead" style={{ margin: 0 }}>{status.databaseMessage}</span>
        </div>
      )}
    </OnboardingShell>
  );
}
