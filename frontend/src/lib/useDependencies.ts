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
  /** Live progress of the Dependency Check while it runs (null otherwise). */
  checkProgress: DependencySetupProgress | null;
  /** The progress window of Setup Dependencies is shown. */
  progressOpen: boolean;
  openProgress: () => void;
  closeProgress: () => void;
  /** The Dependency Check button. */
  runCheck: () => void;
  /** Setup Dependencies: opens the confirmation window first. */
  askSetup: () => void;
  closeConfirm: () => void;
  /**
   * Called by the confirmation window's confirm button. `updateManifests`: when a pinned version cannot be installed,
   * install a newer one that can and update the requirements file. `skipUnused`: do not download declared
   * dependencies the source code never uses.
   */
  confirmSetup: (updateManifests: boolean, skipUnused?: boolean) => Promise<void>;
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
  const [checkProgress, setCheckProgress] = useState<DependencySetupProgress | null>(null);
  const [progressOpen, setProgressOpen] = useState(false);
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
    setCheckProgress({ kind: "CHECK", state: "RUNNING", phase: "Starting…", current: null, percent: 0, unitIndex: 0, unitCount: 0,
      steps: [], installed: 0, failed: 0, changes: [], startedAt: null, finishedAt: null, failures: [], log: [] });
    // the check request stays open until it is done: ask the server how far it is meanwhile
    let polling = true;
    const timer = window.setInterval(() => {
      api
        .dependencyCheckProgress(id)
        .then((p) => {
          if (polling && current.current === id && p.state === "RUNNING") setCheckProgress(p);
        })
        .catch(() => undefined);
    }, 250);
    try {
      const result = await api.runDependencyCheck(id);
      if (current.current === id) setCheck(result);
    } catch (e) {
      if (current.current === id) setError(e instanceof ApiError ? e.message : "The dependencies could not be checked.");
    } finally {
      polling = false;
      window.clearInterval(timer);
      if (current.current === id) {
        setChecking(false);
        setCheckProgress(null);
      }
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
    setCheckProgress(null);
    setProgressOpen(false);
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

  const confirmSetup = useCallback(async (updateManifests: boolean, skipUnused = true) => {
    const id = current.current;
    if (!id) return;
    setError(null);
    try {
      const started = await api.startDependencySetup(id, updateManifests, skipUnused);
      if (current.current !== id) return;
      setSetup(started);
      setConfirmOpen(false);
      setProgressOpen(true); // show what happens while it downloads
      poll(id);
    } catch (e) {
      if (e instanceof ApiError && e.code === "DEPENDENCY_SETUP_RUNNING") {
        setConfirmOpen(false);
        setProgressOpen(true);
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
    checkProgress,
    progressOpen,
    openProgress: () => setProgressOpen(true),
    closeProgress: () => setProgressOpen(false),
    runCheck: () => void runCheck(),
    askSetup: () => setConfirmOpen(true),
    closeConfirm: () => setConfirmOpen(false),
    confirmSetup,
    cancelSetup,
  };
}
