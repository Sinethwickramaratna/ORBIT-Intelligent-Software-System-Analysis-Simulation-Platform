"use client";

import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";

export interface MenuItem {
  label: string;
  icon?: ReactNode;
  shortcut?: string;
  danger?: boolean;
  onSelect: () => void;
}

/** `null` in the list draws a separator line. */
export type MenuEntry = MenuItem | null;

interface Props {
  x: number;
  y: number;
  items: MenuEntry[];
  onClose: () => void;
}

/**
 * Right-click menu of the Explorer, VS Code style: opens at the pointer, stays inside the window, arrow keys move,
 * Enter picks, Escape / a click elsewhere / scrolling / resizing closes it.
 */
export default function TreeContextMenu({ x, y, items, onClose }: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ left: x, top: y });
  const [active, setActive] = useState(0);
  const actions = items.filter((i): i is MenuItem => i !== null);

  // keep the whole menu on screen (flip left / up near the right / bottom edge)
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const { width, height } = el.getBoundingClientRect();
    const left = x + width > window.innerWidth - 4 ? Math.max(4, x - width) : x;
    const top = y + height > window.innerHeight - 4 ? Math.max(4, y - height) : y;
    setPos({ left, top });
    el.focus();
  }, [x, y]);

  useEffect(() => {
    function onDown(e: MouseEvent) {
      if (!ref.current?.contains(e.target as Node)) onClose();
    }
    function onBlur() {
      onClose();
    }
    window.addEventListener("mousedown", onDown, true);
    window.addEventListener("resize", onBlur);
    window.addEventListener("blur", onBlur);
    window.addEventListener("wheel", onBlur, { passive: true });
    return () => {
      window.removeEventListener("mousedown", onDown, true);
      window.removeEventListener("resize", onBlur);
      window.removeEventListener("blur", onBlur);
      window.removeEventListener("wheel", onBlur);
    };
  }, [onClose]);

  function pick(item: MenuItem) {
    onClose();
    item.onSelect();
  }

  let index = -1;
  return (
    <div
      ref={ref}
      className="ctx-menu"
      role="menu"
      aria-label="File actions"
      tabIndex={-1}
      style={{ left: pos.left, top: pos.top }}
      onContextMenu={(e) => e.preventDefault()}
      onKeyDown={(e) => {
        if (e.key === "Escape") {
          e.preventDefault();
          e.stopPropagation();
          onClose();
        } else if (e.key === "ArrowDown") {
          e.preventDefault();
          setActive((a) => (a + 1) % actions.length);
        } else if (e.key === "ArrowUp") {
          e.preventDefault();
          setActive((a) => (a - 1 + actions.length) % actions.length);
        } else if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          if (actions[active]) pick(actions[active]);
        } else if (e.key === "Tab") {
          e.preventDefault();
          onClose();
        }
      }}
    >
      {items.map((item, i) => {
        if (item === null) return <div key={`sep-${i}`} className="ctx-sep" role="separator" />;
        index += 1;
        const mine = index;
        return (
          <button
            key={item.label}
            type="button"
            role="menuitem"
            tabIndex={-1}
            className={`ctx-item${mine === active ? " active" : ""}${item.danger ? " danger" : ""}`}
            onMouseEnter={() => setActive(mine)}
            onClick={() => pick(item)}
          >
            <span className="ctx-icon" aria-hidden="true">{item.icon}</span>
            <span className="ctx-label">{item.label}</span>
            {item.shortcut && <span className="ctx-shortcut">{item.shortcut}</span>}
          </button>
        );
      })}
    </div>
  );
}
