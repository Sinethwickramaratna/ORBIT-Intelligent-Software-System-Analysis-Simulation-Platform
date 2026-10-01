"use client";

import { useEffect, useState } from "react";
import { api, type FolderSettings } from "./api";

const POLL_MS = 3000;
const GIVE_UP_MS = 180_000;

/**
 * While the host helper is re-creating the containers (autoApply) the backend restarts for a moment. This keeps
 * asking for the folder settings until the new folders are active, and reports `gaveUp` when that takes too long
 * so the screen can fall back to the manual instruction.
 */
export function useFolderApply(settings: FolderSettings | null, update: (s: FolderSettings) => void): boolean {
  const [gaveUp, setGaveUp] = useState(false);
  const waiting = !!settings && settings.restartRequired && settings.autoApply;

  useEffect(() => {
    if (!waiting) {
      setGaveUp(false);
      return;
    }
    const started = Date.now();
    const timer = setInterval(() => {
      if (Date.now() - started > GIVE_UP_MS) {
        clearInterval(timer);
        setGaveUp(true);
        return;
      }
      api.getFolderSettings().then(update).catch(() => undefined); // the backend is restarting: try again
    }, POLL_MS);
    return () => clearInterval(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting]);

  return gaveUp;
}
