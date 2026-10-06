"use client";

import { useEffect } from "react";

/**
 * Scrollbars are invisible until the pointer is over a scroll area or it is scrolling (like VS Code).
 * Hover is pure CSS; this marks the element that is scrolling with data-scrolling for a moment after each scroll event.
 */
export default function ScrollbarAutoHide() {
  useEffect(() => {
    const timers = new WeakMap<Element, number>();
    function onScroll(ev: Event) {
      const t = ev.target;
      const el = t instanceof Element ? t : document.documentElement; // page scroll reports the document
      el.setAttribute("data-scrolling", "");
      const old = timers.get(el);
      if (old) window.clearTimeout(old);
      timers.set(el, window.setTimeout(() => el.removeAttribute("data-scrolling"), 900));
    }
    // scroll does not bubble: listen in the capture phase to see every scroll area
    document.addEventListener("scroll", onScroll, { capture: true, passive: true });
    return () => document.removeEventListener("scroll", onScroll, { capture: true });
  }, []);
  return null;
}
