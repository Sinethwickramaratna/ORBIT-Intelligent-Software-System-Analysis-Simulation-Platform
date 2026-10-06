"use client";

import { useCallback, useRef, useState } from "react";
import type { KeyboardEvent, PointerEvent } from "react";

interface Options {
  /** localStorage key that remembers the height. */
  storageKey: string;
  defaultHeight: number;
  min: number;
  /** Largest height that still leaves the file tree its room; measured from the DOM, not guessed. */
  getMax: () => number;
  /** False until the container has a real height (before layout), so nothing is clamped to nonsense. */
  hasRoom: () => boolean;
}

/**
 * Height of a bottom window of the Explorer that the user changes by dragging the splitter above it (mouse, touch)
 * or with the keyboard (arrows, Shift = bigger steps, Home/End = smallest/largest, double-click = default).
 * The choice is remembered and always clamped to the room that is available.
 */
export function usePanelResize({ storageKey, defaultHeight, min, getMax, hasRoom }: Options) {
  const [height, setHeight] = useState(defaultHeight);
  const drag = useRef<{ startY: number; startHeight: number } | null>(null);
  const latest = useRef({ getMax, hasRoom });
  latest.current = { getMax, hasRoom };

  const clamp = useCallback(
    (h: number) => (latest.current.hasRoom() ? Math.min(Math.max(h, min), Math.max(min, latest.current.getMax())) : Math.max(h, min)),
    [min],
  );

  const save = useCallback(
    (h: number) => {
      try {
        localStorage.setItem(storageKey, String(Math.round(h)));
      } catch {
        /* the height just is not remembered */
      }
    },
    [storageKey],
  );

  /** Restores the remembered height (clamped). */
  const restore = useCallback(() => {
    let stored = defaultHeight;
    try {
      const n = Number(localStorage.getItem(storageKey));
      if (Number.isFinite(n) && n >= min) stored = n;
    } catch {
      /* default */
    }
    setHeight(clamp(stored));
  }, [clamp, defaultHeight, min, storageKey]);

  /** Re-applies the limits (the window got smaller, or another window changed size). */
  const reclamp = useCallback(() => setHeight((h) => clamp(h)), [clamp]);

  const splitterProps = (label: string) => ({
    role: "separator" as const,
    "aria-orientation": "horizontal" as const,
    "aria-label": label,
    "aria-valuemin": min,
    "aria-valuemax": Math.round(Math.max(min, getMax())),
    "aria-valuenow": Math.round(height),
    tabIndex: 0,
    title: "Drag to resize (double-click to reset)",
    onPointerDown: (ev: PointerEvent<HTMLDivElement>) => {
      ev.preventDefault();
      ev.currentTarget.setPointerCapture(ev.pointerId);
      drag.current = { startY: ev.clientY, startHeight: height };
    },
    onPointerMove: (ev: PointerEvent<HTMLDivElement>) => {
      const d = drag.current;
      if (d) setHeight(clamp(d.startHeight + (d.startY - ev.clientY))); // dragging up makes the window taller
    },
    onPointerUp: (ev: PointerEvent<HTMLDivElement>) => {
      if (!drag.current) return;
      drag.current = null;
      if (ev.currentTarget.hasPointerCapture(ev.pointerId)) ev.currentTarget.releasePointerCapture(ev.pointerId);
      save(height);
    },
    onPointerCancel: (ev: PointerEvent<HTMLDivElement>) => {
      if (!drag.current) return;
      drag.current = null;
      if (ev.currentTarget.hasPointerCapture(ev.pointerId)) ev.currentTarget.releasePointerCapture(ev.pointerId);
      save(height);
    },
    onKeyDown: (ev: KeyboardEvent<HTMLDivElement>) => {
      const step = ev.shiftKey ? 64 : 16;
      let next: number | null = null;
      if (ev.key === "ArrowUp") next = height + step;
      else if (ev.key === "ArrowDown") next = height - step;
      else if (ev.key === "Home") next = min;
      else if (ev.key === "End") next = getMax();
      if (next === null) return;
      ev.preventDefault();
      const h = clamp(next);
      setHeight(h);
      save(h);
    },
    onDoubleClick: () => {
      const h = clamp(defaultHeight);
      setHeight(h);
      save(h);
    },
  });

  return { height, restore, reclamp, splitterProps };
}
