"use client";

import { useEffect, useRef, useState } from "react";

interface Props {
  projectName: string;
  tabIndex?: number;
  onDelete: () => void;
}

/** The small drop-down arrow on a project in the sidebar. For now it has one item: Delete. */
export default function ProjectMenu({ projectName, tabIndex, onDelete }: Props) {
  const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const open = pos !== null;

  useEffect(() => {
    if (!open) return;
    const close = () => setPos(null);
    function onDown(e: MouseEvent) {
      const t = e.target as Node;
      if (!menuRef.current?.contains(t) && !buttonRef.current?.contains(t)) close();
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        close();
        buttonRef.current?.focus();
      }
    }
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    window.addEventListener("resize", close);
    window.addEventListener("scroll", close, true);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("resize", close);
      window.removeEventListener("scroll", close, true);
    };
  }, [open]);

  useEffect(() => {
    if (open) menuRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
  }, [open]);

  function toggle() {
    if (open) {
      setPos(null);
      return;
    }
    const r = buttonRef.current?.getBoundingClientRect();
    if (r) setPos({ top: r.bottom + 4, left: Math.max(8, r.right - 150) });
  }

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={`project-menu-btn${open ? " open" : ""}`}
        aria-label={`Options for ${projectName}`}
        aria-haspopup="menu"
        aria-expanded={open}
        tabIndex={tabIndex}
        onClick={toggle}
      >
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="m6 9 6 6 6-6" />
        </svg>
      </button>
      {open && (
        <div ref={menuRef} className="project-menu" role="menu" style={{ top: pos.top, left: pos.left }}>
          <button
            type="button"
            role="menuitem"
            className="project-menu-item danger"
            onClick={() => {
              setPos(null);
              onDelete();
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14M10 11v6M14 11v6" />
            </svg>
            Delete
          </button>
        </div>
      )}
    </>
  );
}
