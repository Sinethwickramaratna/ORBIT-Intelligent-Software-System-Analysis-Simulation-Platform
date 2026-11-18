"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Editor, { loader, type Monaco, type OnMount } from "@monaco-editor/react";
import { languageForPath } from "@/lib/languages";
import { applyEditorTheme, configureLanguageServices, editorThemeFor, prepareLanguage } from "@/lib/syntax";

/** Monaco is served by ORBIT itself (copied to public/ by scripts/copy-monaco.mjs): no CDN, works offline. */
export const MONACO_PATH = "/monaco/vs";
loader.config({ paths: { vs: MONACO_PATH } });

/** Model URI of an open file: one Monaco model per file keeps its own undo history and scroll position. */
export function modelPath(projectId: string, filePath: string): string {
  return "file:///" + [projectId, ...filePath.split("/")].map(encodeURIComponent).join("/");
}

/** Frees the editor model of a closed / deleted / renamed file. */
export function disposeModel(path: string): void {
  const monaco = loader.__getMonacoInstance();
  monaco?.editor.getModel(monaco.Uri.parse(path))?.dispose();
}

/** The app theme (data-theme on <html>), following changes made in Settings. */
function useAppTheme(): string | null {
  const [theme, setTheme] = useState<string | null>(null);
  useEffect(() => {
    const root = document.documentElement;
    setTheme(root.getAttribute("data-theme"));
    const mo = new MutationObserver(() => setTheme(root.getAttribute("data-theme")));
    mo.observe(root, { attributes: true, attributeFilter: ["data-theme"] });
    return () => mo.disconnect();
  }, []);
  return theme;
}

interface Props {
  /** Project-relative path of the file: picks the language (VS Code grammar) for the colours. */
  filePath: string;
  /** Monaco model URI, see {@link modelPath}. */
  path: string;
  value: string;
  onChange: (value: string) => void;
  onCursor?: (line: number, column: number) => void;
  /** Ctrl+S / Cmd+S inside the editor. */
  onSave?: () => void;
  label: string;
  autoFocus?: boolean;
}

/**
 * The code editor of the IDE view: Monaco (the editor of VS Code) coloured by the TextMate grammars and themes of
 * VS Code's language extensions. Brackets, indentation, multi-cursor, find/replace, minimap and undo work as in VS Code.
 */
export default function CodeEditor({ filePath, path, value, onChange, onCursor, onSave, label, autoFocus }: Props) {
  const appTheme = useAppTheme();
  const language = useMemo(() => languageForPath(filePath), [filePath]);
  const [ready, setReady] = useState<string | null>(null); // the language whose grammar is installed
  const [failed, setFailed] = useState<string | null>(null);
  const monacoRef = useRef<Monaco | null>(null);
  const saveRef = useRef(onSave);
  saveRef.current = onSave;
  const cursorRef = useRef(onCursor);
  cursorRef.current = onCursor;

  // load Monaco + the grammar of this file's language before the editor is shown, so it opens already coloured
  useEffect(() => {
    let alive = true;
    loader
      .init()
      .then(async (monaco) => {
        monacoRef.current = monaco;
        configureLanguageServices(monaco);
        applyEditorTheme(monaco, document.documentElement.getAttribute("data-theme"));
        await prepareLanguage(monaco, language);
        if (alive) setReady(language);
      })
      .catch((e: unknown) => {
        // a missing grammar must not block editing: fall back to plain text
        console.error("Editor setup failed", e);
        if (!alive) return;
        if (monacoRef.current) setReady("plaintext");
        else setFailed(e instanceof Error ? e.message : "The editor could not be loaded.");
      });
    return () => {
      alive = false;
    };
  }, [language]);

  useEffect(() => {
    if (monacoRef.current) applyEditorTheme(monacoRef.current, appTheme);
  }, [appTheme, ready]);

  const onMount: OnMount = (editor, monaco) => {
    editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveRef.current?.());
    editor.onDidChangeCursorPosition((e) => cursorRef.current?.(e.position.lineNumber, e.position.column));
    // the UI font loads asynchronously: measure again once it is there, or the cursor drifts
    document.fonts?.ready.then(() => monaco.editor.remeasureFonts()).catch(() => undefined);
    if (autoFocus) editor.focus();
  };

  if (failed) {
    return (
      <div className="editor-note error" role="alert">
        The editor could not be loaded. <span>{failed}</span>
      </div>
    );
  }
  if (ready === null) return <div className="editor-note">Loading editor…</div>;

  return (
    <div className="code-editor" aria-label={label}>
      <Editor
        path={path}
        language={ready === language ? language : "plaintext"}
        value={value}
        theme={editorThemeFor(appTheme)}
        onChange={(v) => onChange(v ?? "")}
        onMount={onMount}
        loading={<div className="editor-note">Loading editor…</div>}
        options={{
          fontFamily: '"Source Code Pro", ui-monospace, "Cascadia Code", Consolas, monospace',
          fontSize: 13,
          lineHeight: 20,
          automaticLayout: true,
          minimap: { enabled: true },
          scrollBeyondLastLine: false,
          smoothScrolling: true,
          bracketPairColorization: { enabled: true },
          guides: { bracketPairs: "active", indentation: true },
          renderWhitespace: "selection",
          stickyScroll: { enabled: true },
          tabSize: 4,
          detectIndentation: true,
          ariaLabel: label,
          fixedOverflowWidgets: true,
        }}
      />
    </div>
  );
}
