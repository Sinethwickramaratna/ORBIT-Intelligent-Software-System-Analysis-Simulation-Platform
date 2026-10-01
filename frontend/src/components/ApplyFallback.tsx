"use client";

import { useState } from "react";
import { api, type FolderSettings } from "@/lib/api";

interface Props {
  onSettings: (s: FolderSettings) => void;
}

/**
 * Shown when the folder change could not be applied automatically (the ORBIT helper is not running). "Apply now"
 * asks the helper again, so starting it (start.cmd / start.sh, or scripts/orbit-watch.*) is enough.
 */
export default function ApplyFallback({ onSettings }: Props) {
  const [busy, setBusy] = useState(false);
  const [miss, setMiss] = useState(false);

  async function applyNow() {
    setBusy(true);
    setMiss(false);
    try {
      const next = await api.applyFolderSettings();
      onSettings(next);
      if (!next.autoApply) setMiss(true);
    } catch {
      setMiss(true);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <span>
        The ORBIT helper that restarts Docker for you is not running. Double-click <code>start.cmd</code> (Windows) or run{" "}
        <code>./start.sh</code> - that starts the helper and applies the change - then press <strong>Apply now</strong>
        {miss ? " (the helper still was not found)" : ""}.
      </span>
      <button type="button" className="btn" onClick={applyNow} disabled={busy}>
        {busy ? "Applying…" : "Apply now"}
      </button>
    </>
  );
}
