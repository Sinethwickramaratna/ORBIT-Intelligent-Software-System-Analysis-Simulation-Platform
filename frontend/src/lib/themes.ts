/** Theme ids must match SettingsService.THEMES in the backend. */
export interface ThemeOption {
  id: string;
  label: string;
  description: string;
  /** bg, surface, accent-ish colours used only to draw the preview swatch */
  preview: [string, string, string, string];
}

export const THEMES: ThemeOption[] = [
  { id: "orbit-dark", label: "Orbit Dark", description: "Deep space with the ORBIT sunset accent", preview: ["#14101a", "#211a2b", "#ffaf39", "#bf1679"] },
  { id: "orbit-light", label: "Orbit Light", description: "Warm paper with the ORBIT sunset accent", preview: ["#fff8ef", "#ffffff", "#ee7e1b", "#cf214b"] },
  { id: "vs-dark", label: "Dark+ (VS Code)", description: "Familiar dark editor look", preview: ["#1e1e1e", "#252526", "#569cd6", "#ce9178"] },
  { id: "vs-light", label: "Light+ (VS Code)", description: "Familiar light editor look", preview: ["#ffffff", "#f3f3f3", "#0000ff", "#a31515"] },
  { id: "monokai", label: "Monokai", description: "Classic high-energy dark", preview: ["#272822", "#31322b", "#a6e22e", "#f92672"] },
  { id: "solarized-dark", label: "Solarized Dark", description: "Low-contrast, easy on the eyes", preview: ["#002b36", "#073642", "#b58900", "#cb4b16"] },
  { id: "solarized-light", label: "Solarized Light", description: "Soft, warm light", preview: ["#fdf6e3", "#eee8d5", "#b58900", "#cb4b16"] },
  { id: "high-contrast", label: "High Contrast", description: "Maximum readability", preview: ["#000000", "#0a0a0a", "#ffff00", "#00ffff"] },
];

export const DEFAULT_THEME = "orbit-dark";
export const THEME_CACHE_KEY = "orbit.theme";
/** Set once the user has picked a theme; it may not be in the database yet (the database is created after this step). */
export const THEME_CHOSEN_KEY = "orbit.theme.chosen";

export function hasChosenTheme(): boolean {
  try {
    return localStorage.getItem(THEME_CHOSEN_KEY) === "1";
  } catch {
    return false;
  }
}

export function markThemeChosen(): void {
  try {
    localStorage.setItem(THEME_CHOSEN_KEY, "1");
  } catch {
    /* ignore */
  }
}

export function isThemeId(value: unknown): value is string {
  return typeof value === "string" && THEMES.some((t) => t.id === value);
}

export function applyTheme(id: string): void {
  document.documentElement.setAttribute("data-theme", id);
  try {
    localStorage.setItem(THEME_CACHE_KEY, id);
  } catch {
    /* storage may be unavailable - the database is the source of truth */
  }
}
