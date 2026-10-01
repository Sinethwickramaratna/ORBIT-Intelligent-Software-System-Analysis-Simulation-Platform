"use client";

import { useEffect, useState } from "react";

const DRIVE = /^[A-Za-z]:([\\/].*)?$/;

/** Light client-side check; the server applies the full rules and explains anything else. */
export function validateFolders(list: string[]): string | null {
  for (const raw of list) {
    const v = raw.trim();
    if (!v) continue;
    if (!DRIVE.test(v) && !v.startsWith("/")) {
      return `"${v}" must be a full path, e.g. E:/  D:/Work  /Users/you/code  /home/you/code`;
    }
    if (/["'#$`|,*?<>]/.test(v)) {
      return `"${v}" contains a character that is not allowed ( " ' # $ \` | , * ? < > )`;
    }
  }
  return null;
}

function isSystemDrive(v: string): boolean {
  const t = v.trim();
  return /^[cC]:[\\/]?$/.test(t);
}

interface Props {
  value: string[];
  onChange: (next: string[]) => void;
  max?: number;
  idPrefix?: string;
}

/** Rows of "drive or folder" text boxes with add / remove. Used by the setup screen and the Settings window. */
export default function FoldersEditor({ value, onChange, max = 8, idPrefix = "folder" }: Props) {
  const [example, setExample] = useState("E:/  or  D:/Work");

  useEffect(() => {
    const ua = navigator.userAgent;
    if (/Windows/i.test(ua)) setExample("E:/");
    else if (/Mac/i.test(ua)) setExample("/Users/you/code");
    else setExample("/home/you/code");
  }, []);

  const rows = value.length ? value : [""];

  function set(i: number, v: string) {
    const next = [...rows];
    next[i] = v;
    onChange(next);
  }

  return (
    <div className="folders-editor">
      {rows.map((row, i) => (
        <div key={i} className="folder-row">
          <input
            id={`${idPrefix}-${i}`}
            value={row}
            onChange={(e) => set(i, e.target.value)}
            placeholder={i === 0 ? example : "Another drive or folder (optional)"}
            aria-label={`Folder ${i + 1}`}
            spellCheck={false}
            autoComplete="off"
          />
          <button
            type="button"
            className="icon-btn small"
            aria-label={`Remove folder ${i + 1}`}
            title="Remove"
            onClick={() => onChange(rows.filter((_, j) => j !== i))}
            disabled={rows.length === 1 && !row}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" aria-hidden="true"><path d="M6 6l12 12M18 6 6 18" /></svg>
          </button>
          {isSystemDrive(row) && (
            <span className="folder-warn">System drive: ORBIT could read and change Windows files. Prefer a project folder or another drive.</span>
          )}
        </div>
      ))}
      <button type="button" className="btn ghost add-folder" onClick={() => onChange([...rows, ""])} disabled={rows.length >= max}>
        + Add another folder
      </button>
      <p className="hint">
        Type the full path of a drive or folder. ORBIT can read <strong>and change</strong> everything inside it, and the
        project folder chooser only shows these places. Examples: <code>E:/</code> (a whole drive),
        <code> D:/Work</code>, <code>/Users/you/code</code>, <code>/home/you/code</code>. Use forward slashes.
      </p>
    </div>
  );
}
