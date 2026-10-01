"use client";

import { FormEvent, useState } from "react";
import { ApiError, api, type SetupStatus } from "@/lib/api";
import OnboardingShell from "./OnboardingShell";
import FoldersEditor, { validateFolders } from "./FoldersEditor";
import PasswordInput from "./PasswordInput";

const MIN_SECRET = 32;
const MIN_DB_PASSWORD = 8;

function randomSecret(): string {
  const bytes = new Uint8Array(48);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/[+/=]/g, "x").slice(0, 48);
}

interface Props {
  status: SetupStatus;
  onDone: () => void;
}

/** First-run environment: JWT secret key + database username/password (name `orbit` and port `5433` are defaults). */
export default function EnvironmentSetup({ status, onDone }: Props) {
  const needSecret = !status.secretKeyConfigured;
  const needDb = !status.databaseCredentialsConfigured;
  const needFolders = status.foldersSetupNeeded;
  const onlyFolders = needFolders && !needSecret && !needDb;

  const [secret, setSecret] = useState("");
  const [dbUser, setDbUser] = useState("");
  const [dbPassword, setDbPassword] = useState("");
  const [dbPasswordConfirm, setDbPasswordConfirm] = useState("");
  const [folders, setFolders] = useState<string[]>([""]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (needSecret && secret.trim().length < MIN_SECRET) {
      setError(`The secret key must be at least ${MIN_SECRET} characters.`);
      return;
    }
    if (needDb) {
      if (!/^[A-Za-z_][A-Za-z0-9_]{0,62}$/.test(dbUser.trim())) {
        setError("The database username must start with a letter or _ and contain only letters, digits and _.");
        return;
      }
      if (dbPassword.length < MIN_DB_PASSWORD) {
        setError(`The database password must be at least ${MIN_DB_PASSWORD} characters.`);
        return;
      }
      if (dbPassword !== dbPasswordConfirm) {
        setError("The database passwords do not match.");
        return;
      }
    }
    if (needFolders) {
      const problem = validateFolders(folders);
      if (problem) {
        setError(problem);
        return;
      }
    }
    setSaving(true);
    try {
      await api.saveEnvironment({
        ...(needSecret ? { secretKey: secret.trim() } : {}),
        ...(needDb ? { dbUsername: dbUser.trim(), dbPassword } : {}),
        ...(needFolders ? { folders: folders.map((f) => f.trim()).filter(Boolean) } : {}),
      });
      onDone();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not save your settings");
    } finally {
      setSaving(false);
    }
  }

  return (
    <OnboardingShell step={1}>
      <div className="eyebrow">Environment</div>
      <h2 className="title">{onlyFolders ? "Choose your project folders" : "Set up your environment"}</h2>
      <p className="lead">
        {onlyFolders
          ? "ORBIT runs in Docker, so it can only open the drives and folders you list here. They are saved to the .env file in the project root."
          : "These values are unique to this computer. They are saved to the .env file in the project root (never committed to git) and used to create your own local database."}
      </p>
      {error && <div className="alert" role="alert">{error}</div>}
      <form onSubmit={submit} noValidate>
        {needSecret && (
          <div className="field">
            <label htmlFor="secret">Secret key (JWT_SECRET)</label>
            <PasswordInput id="secret" autoComplete="off" value={secret} onChange={setSecret} />
            <span className="hint">
              {secret.trim().length}/{MIN_SECRET}+ characters · signs your login tokens
            </span>
            <button className="btn ghost" type="button" style={{ alignSelf: "flex-start", fontSize: 14, padding: "6px 14px" }} onClick={() => setSecret(randomSecret())}>
              Generate
            </button>
          </div>
        )}
        {needDb && (
          <>
            <div className="field">
              <label htmlFor="db-user">Database username (DB_USERNAME)</label>
              <input id="db-user" autoComplete="off" value={dbUser} onChange={(e) => setDbUser(e.target.value)} />
            </div>
            <div className="field">
              <label htmlFor="db-pass">Database password (DB_PASSWORD)</label>
              <PasswordInput id="db-pass" autoComplete="new-password" value={dbPassword} onChange={setDbPassword} />
              <span className="hint">At least {MIN_DB_PASSWORD} characters</span>
            </div>
            <div className="field">
              <label htmlFor="db-pass2">Confirm database password</label>
              <PasswordInput id="db-pass2" autoComplete="new-password" value={dbPasswordConfirm} onChange={setDbPasswordConfirm} />
              <span className="hint">
                Database name <code>{status.databaseName}</code> and port <code>{status.databasePort}</code> are defaults.
              </span>
            </div>
          </>
        )}
        {needFolders && (
          <div className="field">
            <label>Folders ORBIT can open (ORBIT_MOUNT)</label>
            <FoldersEditor value={folders} onChange={setFolders} idPrefix="env-folder" />
            <span className="hint">
              Leave empty to use only ORBIT&apos;s own <code>projects</code> folder. You can change this later in Settings.
              After changing it, run <code>start.cmd</code> (Windows) or <code>./start.sh</code> once to apply it.
            </span>
          </div>
        )}
        <button className="btn full" type="submit" disabled={saving}>
          {saving ? "Saving…" : onlyFolders ? "Save" : "Save & create database"}
        </button>
      </form>
    </OnboardingShell>
  );
}
