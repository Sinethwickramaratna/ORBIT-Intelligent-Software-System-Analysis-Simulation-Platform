"use client";

import { useEffect, useRef, type ReactNode } from "react";

export interface ConfirmAction {
  label: string;
  /** "danger" = red, "primary" = accent, "ghost" = outlined (the default for Cancel-like actions). */
  tone?: "danger" | "primary" | "ghost";
  onClick: () => void;
}

interface Props {
  title: string;
  children: ReactNode;
  /** Buttons from left to right; the last one gets the focus and runs on Enter. */
  actions: ConfirmAction[];
  /** Escape / clicking the backdrop / the × button. */
  onCancel: () => void;
  busy?: boolean;
  error?: string | null;
}

/** Small JetBrains-style confirmation window (Delete file, unsaved changes, file changed on disk). */
export default function ConfirmDialog({ title, children, actions, onCancel, busy = false, error }: Props) {
  const lastRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    lastRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape" && !busy) {
        e.preventDefault();
        e.stopPropagation();
        onCancel();
      }
    }
    window.addEventListener("keydown", onKey, true);
    return () => window.removeEventListener("keydown", onKey, true);
  }, [busy, onCancel]);

  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && !busy && onCancel()}>
      <div className="modal jb-modal confirm-modal" role="alertdialog" aria-modal="true" aria-label={title}>
        <div className="jb-titlebar">
          <span className="jb-title">{title}</span>
          <button type="button" className="jb-close" onClick={onCancel} aria-label="Close" disabled={busy}>×</button>
        </div>
        <div className="jb-body confirm-body">
          {children}
          {error && <div className="alert" role="alert">{error}</div>}
        </div>
        <div className="jb-footer">
          {actions.map((a, i) => (
            <button
              key={a.label}
              ref={i === actions.length - 1 ? lastRef : undefined}
              type="button"
              className={`btn${a.tone === "danger" ? " danger" : a.tone === "primary" ? "" : " ghost"}`}
              onClick={a.onClick}
              disabled={busy}
            >
              {a.label}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
