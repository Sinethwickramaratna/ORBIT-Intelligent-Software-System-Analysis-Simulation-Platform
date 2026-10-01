"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError, api, hasStoredSession, type SetupStatus, type User } from "@/lib/api";
import { DEFAULT_THEME, THEME_CACHE_KEY, applyTheme, hasChosenTheme, isThemeId } from "@/lib/themes";
import DatabaseConnecting from "./DatabaseConnecting";
import EnvironmentSetup from "./EnvironmentSetup";
import HomeScreen from "./HomeScreen";
import LoginForm from "./LoginForm";
import RegisterForm from "./RegisterForm";
import ThemePicker from "./ThemePicker";

type Phase = "loading" | "unreachable" | "theme" | "environment" | "database" | "register" | "login" | "home";

/**
 * First-run order: theme (kept in the browser until the database exists) -> environment (secret key + database
 * credentials, saved to .env) -> wait for the database -> register -> login.
 */
function phaseFor(status: SetupStatus): Phase {
  if (!status.themeSelected && !hasChosenTheme()) return "theme";
  if (!status.secretKeyConfigured || !status.databaseCredentialsConfigured || status.foldersSetupNeeded) return "environment";
  if (status.databaseState !== "READY") return "database";
  if (!status.hasUsers) return "register";
  return "login";
}

function cachedTheme(): string {
  try {
    const t = localStorage.getItem(THEME_CACHE_KEY);
    if (isThemeId(t)) return t;
  } catch {
    /* ignore */
  }
  return DEFAULT_THEME;
}

export default function AppShell() {
  const [phase, setPhase] = useState<Phase>("loading");
  const [status, setStatus] = useState<SetupStatus | null>(null);
  const [user, setUser] = useState<User | null>(null);
  const [justRegistered, setJustRegistered] = useState(false);
  const [errorMessage, setErrorMessage] = useState("");

  const load = useCallback(async () => {
    setPhase("loading");
    try {
      let s = await api.setupStatus();
      if (s.databaseState === "READY" && !s.themeSelected && hasChosenTheme()) {
        // The theme was picked before the database existed: store it now.
        try {
          await api.saveTheme(cachedTheme());
          s = { ...s, themeSelected: true, theme: cachedTheme() };
        } catch {
          /* retried on the next load */
        }
      }
      setStatus(s);
      if (isThemeId(s.theme)) applyTheme(s.theme);
      const next = phaseFor(s);
      if (next === "login" && hasStoredSession()) {
        try {
          setUser(await api.currentUser()); // transparently refreshes an expired access token
          setPhase("home");
          return;
        } catch {
          /* session gone (refresh token expired/deleted) - fall through to login */
        }
      }
      setPhase(next);
    } catch (e) {
      setErrorMessage(e instanceof ApiError ? e.message : "Unexpected error");
      setPhase("unreachable");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // While PostgreSQL is starting / migrating, poll the backend; continue automatically once it is READY.
  useEffect(() => {
    if (phase !== "database") return;
    const timer = setInterval(() => {
      api
        .setupStatus()
        .then((s) => {
          if (s.databaseState === "READY") void load();
          else setStatus(s);
        })
        .catch(() => undefined);
    }, 2000);
    return () => clearInterval(timer);
  }, [phase, load]);

  if (phase === "loading") {
    return (
      <div className="center-screen" role="status" aria-live="polite">
        <div className="spinner" />
        <span>Checking your local ORBIT installation…</span>
      </div>
    );
  }

  if (phase === "unreachable") {
    return (
      <div className="center-screen">
        <h2 className="title">Backend offline</h2>
        <p className="lead" style={{ maxWidth: 480 }}>{errorMessage}</p>
        <p className="lead" style={{ maxWidth: 480 }}>
          Start the stack with <code>docker compose up -d</code>, or run the backend with <code>mvnw spring-boot:run</code>.
        </p>
        <button className="btn" onClick={() => void load()}>Retry</button>
      </div>
    );
  }

  if (phase === "theme") return <ThemePicker initial={cachedTheme()} onDone={() => void load()} />;
  if (phase === "environment" && status) return <EnvironmentSetup status={status} onDone={() => void load()} />;
  if (phase === "database" && status)
    return (
      <DatabaseConnecting
        status={status}
        onRetry={(next) => {
          setStatus(next);
        }}
      />
    );
  if (phase === "register")
    return (
      <RegisterForm
        onDone={() => {
          setJustRegistered(true);
          void load();
        }}
      />
    );
  if (phase === "login")
    return (
      <LoginForm
        justRegistered={justRegistered}
        onLoggedIn={(u) => {
          setUser(u);
          setPhase("home");
        }}
      />
    );
  if (phase === "home" && user)
    return (
      <HomeScreen
        user={user}
        onLoggedOut={() => {
          setUser(null);
          setJustRegistered(false);
          setPhase(status ? phaseFor(status) : "login");
        }}
      />
    );
  return null;
}
