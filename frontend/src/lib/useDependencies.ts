"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, type DependencyCheck, type DependencySetupProgress } from "./api";

const POLL_MS = 700;

/** What the top-bar status icon shows. */
export type DependencyIndicator = "loading" | "checking" | "installing" | "ok" | "missing" | "error";

export interface Dependencies {
  check: DependencyCheck | null;
  setup: DependencySetupProgress | null;
  indicator: DependencyIndicator;
  checking: boolean;
  installing: boolean;
  error: string | null;
  confirmOpen: boolean;
  /** The Dependency Check button. */
  runCheck: () => void;
  /** Setup Dependencies: opens the confirmation window first. */
  askSetup: () => void;
  closeConfirm: () => void;
  /** Called by the confirmation window's confirm button. */
  confirmSetup: () => Promise<void>;
  cancelSetup: () => void;
}

/**
 * Dependency check + Setup Dependencies of the open project. The check made when the project was added is loaded;
 * a project that was never checked (added before this feature existed) is checked automatically. A setup that is
 * still running on the server (page reload) is picked up again.
 */
export function useDependencies(projectId: string | null): Dependencies {
  const [check, setCheck] = useState<DependencyCheck | null>(null);
  const [setup, setSetup] = useState<DependencySetupProgress | null>(null);
  const [loading, setLoading] = useState(false);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const current = useRef(projectId);
  const pollTimer = useRef<number | null>(null);

  const stopPolling = useCallback(() => {
    if (pollTimer.current !== null) {
      window.clearTimeout(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const runCheck = useCallback(async () => {
    const id = current.current;
    if (!id) return;
    setChecking(true);
    setError(null);
    try {
      const result = await api.runDependencyCheck(id);
      if (current.current === id) setCheck(result);
    } catch (e) {
      if (current.current === id) setError(e instanceof ApiError ? e.message : "The dependencies could not be checked.");
    } finally {
      if (current.current === id) setChecking(false);
    }
  }, []);

  /** Polls the setup until it ends, then shows the check the server stored at the end of the setup. */
  const poll = useCallback(
    (id: string) => {
      stopPolling();
      const tick = async () => {
        if (current.current !== id) return;
        try {
          const p = await api.dependencySetupProgress(id);
          if (current.current !== id) return;
          setSetup(p);
          if (p.state === "RUNNING") {
            pollTimer.current = window.setTimeout(tick, POLL_MS);
            return;
          }
          const latest = await api.dependencyCheck(id);
          if (current.current === id && latest) setCheck(latest);
        } catch {
          if (current.current === id) pollTimer.current = window.setTimeout(tick, POLL_MS * 3); // backend busy / restarting
        }
      };
      pollTimer.current = window.setTimeout(tick, POLL_MS);
    },
    [stopPolling],
  );

  // Open project changed: load its stored check, or check it now when it has never been checked (autoCheck).
  useEffect(() => {
    current.current = projectId;
    stopPolling();
    setCheck(null);
    setSetup(null);
    setError(null);
    setChecking(false);
    setConfirmOpen(false);
    if (!projectId) return;
    const id = projectId;
    setLoading(true);
    const autoCheck = async () => {
      try {
        const [stored, progress] = await Promise.all([api.dependencyCheck(id), api.dependencySetupProgress(id)]);
        if (current.current !== id) return;
        if (progress.state !== "IDLE") setSetup(progress);
        if (progress.state === "RUNNING") poll(id);
        if (stored) setCheck(stored);
        else if (progress.state !== "RUNNING") await runCheck();
      } catch (e) {
        if (current.current === id) setError(e instanceof ApiError ? e.message : "The dependencies could not be loaded.");
      } finally {
        if (current.current === id) setLoading(false);
      }
    };
    void autoCheck();
    return stopPolling;
  }, [projectId, poll, runCheck, stopPolling]);

  const confirmSetup = useCallback(async () => {
    const id = current.current;
    if (!id) return;
    setError(null);
    try {
      const started = await api.startDependencySetup(id);
      if (current.current !== id) return;
      setSetup(started);
      setConfirmOpen(false);
      poll(id);
    } catch (e) {
      if (e instanceof ApiError && e.code === "DEPENDENCY_SETUP_RUNNING") {
        setConfirmOpen(false);
        poll(id); // already running (other tab): just follow it
        return;
      }
      throw e;
    }
  }, [poll]);

  const cancelSetup = useCallback(() => {
    const id = current.current;
    if (!id) return;
    api
      .cancelDependencySetup(id)
      .then((p) => current.current === id && setSetup(p))
      .catch(() => undefined);
  }, []);

  const installing = setup?.state === "RUNNING";
  let indicator: DependencyIndicator;
  if (installing) indicator = "installing";
  else if (checking) indicator = "checking";
  else if (loading && !check) indicator = "loading";
  else if (check) indicator = check.status === "MISSING" ? "missing" : "ok";
  else indicator = "error";

  return {
    check,
    setup,
    indicator,
    checking,
    installing,
    error,
    confirmOpen,
    runCheck: () => void runCheck(),
    askSetup: () => setConfirmOpen(true),
    closeConfirm: () => setConfirmOpen(false),
    confirmSetup,
    cancelSetup,
  };
}
