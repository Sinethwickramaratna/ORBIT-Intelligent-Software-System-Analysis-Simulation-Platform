"use client";

import type { Monaco } from "@monaco-editor/react";
import { shikiToMonaco } from "@shikijs/monaco";
import { bundledThemes, createHighlighter, type BundledLanguage, type Highlighter, type ThemeRegistration } from "shiki";
import { createOnigurumaEngine } from "shiki/engine/oniguruma";

/**
 * Syntax colouring for the Monaco editor with Shiki: the TextMate grammars and colour themes of VS Code's own
 * language extensions, so code is coloured exactly like in VS Code. Grammars load on demand, one language at a time.
 */

/** App theme (data-theme on <html>) -> editor colour theme. ORBIT's own themes get VS Code Dark+/Light+ colours on ORBIT's background. */
const EDITOR_THEME: Record<string, string> = {
  "orbit-dark": "orbit-dark",
  "orbit-light": "orbit-light",
  "vs-dark": "dark-plus",
  "vs-light": "light-plus",
  monokai: "monokai",
  "solarized-dark": "solarized-dark",
  "solarized-light": "solarized-light",
  "high-contrast": "github-dark-high-contrast",
};

export function editorThemeFor(appTheme: string | null | undefined): string {
  return EDITOR_THEME[appTheme ?? ""] ?? "orbit-dark";
}

/** VS Code Dark+ / Light+ token colours on ORBIT's own editor surface. */
async function orbitTheme(base: "dark-plus" | "light-plus", name: string, colors: Record<string, string>): Promise<ThemeRegistration> {
  const theme = (await bundledThemes[base]()).default as ThemeRegistration;
  return { ...theme, name, displayName: name, bg: colors["editor.background"], colors: { ...theme.colors, ...colors } };
}

let highlighterPromise: Promise<Highlighter> | null = null;

function getHighlighter(): Promise<Highlighter> {
  highlighterPromise ??= (async () =>
    createHighlighter({
      themes: [
        // first theme = the one shikiToMonaco starts with
        await orbitTheme("dark-plus", "orbit-dark", {
          "editor.background": "#14101a",
          "editorGutter.background": "#14101a",
          "editor.lineHighlightBackground": "#211a2b",
          "editorLineNumber.foreground": "#6d6078",
          "editorLineNumber.activeForeground": "#ffaf39",
          "editorCursor.foreground": "#ffaf39",
          "editor.selectionBackground": "#ffaf3940",
          "minimap.background": "#14101a",
        }),
        await orbitTheme("light-plus", "orbit-light", {
          "editor.background": "#fff8ef",
          "editorGutter.background": "#fff8ef",
          "editor.lineHighlightBackground": "#fff1de",
          "editorLineNumber.foreground": "#b39a86",
          "editorLineNumber.activeForeground": "#ee7e1b",
          "editorCursor.foreground": "#ee7e1b",
          "editor.selectionBackground": "#ee7e1b33",
          "minimap.background": "#fff8ef",
        }),
        "dark-plus",
        "light-plus",
        "monokai",
        "solarized-dark",
        "solarized-light",
        "github-dark-high-contrast",
      ],
      langs: [],
      // the same Oniguruma regex engine VS Code uses (WebAssembly), so every grammar behaves as in VS Code
      engine: createOnigurumaEngine(import("shiki/wasm")),
    }))();
  return highlighterPromise;
}

/** Monaco's own setTheme/create, before shikiToMonaco wraps them (it wraps them again on every call). */
let original: { setTheme: Monaco["editor"]["setTheme"]; create: Monaco["editor"]["create"] } | null = null;
let currentTheme = "orbit-dark";
let pending: Promise<unknown> = Promise.resolve();

/**
 * Makes sure `language` is coloured: loads its grammar (and the grammars it embeds, e.g. CSS/JS inside HTML),
 * registers them with Monaco and installs Shiki as their tokenizer. Calls are queued so they never interleave.
 */
export function prepareLanguage(monaco: Monaco, language: string): Promise<void> {
  const run = async () => {
    const highlighter = await getHighlighter();
    const before = highlighter.getLoadedLanguages().length;
    if (language !== "plaintext" && !highlighter.getLoadedLanguages().includes(language)) {
      await highlighter.loadLanguage(language as BundledLanguage);
    }
    if (original && highlighter.getLoadedLanguages().length === before) return; // nothing new to install

    const known = new Set(monaco.languages.getLanguages().map((l: { id: string }) => l.id));
    for (const id of highlighter.getLoadedLanguages()) if (!known.has(id)) monaco.languages.register({ id });

    if (!original) original = { setTheme: monaco.editor.setTheme, create: monaco.editor.create };
    monaco.editor.setTheme = original.setTheme;
    monaco.editor.create = original.create;
    shikiToMonaco(highlighter, monaco);
    monaco.editor.setTheme(currentTheme); // shikiToMonaco switches to its first theme
  };
  const next = pending.then(run, run);
  pending = next.catch(() => undefined);
  return next;
}

/** Switches every editor to the colour theme that belongs to the app theme. */
export function applyEditorTheme(monaco: Monaco, appTheme: string | null | undefined): void {
  currentTheme = editorThemeFor(appTheme);
  if (original) monaco.editor.setTheme(currentTheme);
}

/** Quietens language services that cannot know the project (no node_modules types, comments in tsconfig.json). */
export function configureLanguageServices(monaco: Monaco): void {
  // Monaco 0.5x moved these namespaces; support both places
  type Defaults = { setDiagnosticsOptions?: (options: object) => void };
  type Namespace = Record<string, Defaults | undefined>;
  const top = monaco as unknown as Record<string, Namespace | undefined>;
  const langs = monaco.languages as unknown as Record<string, Namespace | undefined>;
  const ts = top.typescript ?? langs.typescript;
  for (const defaults of [ts?.typescriptDefaults, ts?.javascriptDefaults]) {
    defaults?.setDiagnosticsOptions?.({ noSemanticValidation: true, noSyntaxValidation: false, noSuggestionDiagnostics: true });
  }
  const json = top.json ?? langs.json;
  json?.jsonDefaults?.setDiagnosticsOptions?.({ validate: true, allowComments: true, trailingCommas: "ignore", enableSchemaRequest: false });
}
