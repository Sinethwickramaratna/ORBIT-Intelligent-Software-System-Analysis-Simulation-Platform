"use client";

import type { Monaco } from "@monaco-editor/react";
import type * as TreeSitter from "@vscode/tree-sitter-wasm";

/**
 * Syntax errors underlined in red, like VS Code. TypeScript / JavaScript, JSON and CSS / SCSS / Less are checked by
 * Monaco's own language services (the same ones VS Code uses). The languages below are parsed with tree-sitter - the
 * parser runtime and grammars VS Code itself ships (@vscode/tree-sitter-wasm), served from /tree-sitter - and every
 * place the grammar cannot parse becomes an error marker on the editor model.
 */

export const SYNTAX_OWNER = "orbit-syntax";

/** Editor language (Shiki id) -> tree-sitter grammar file in /tree-sitter. */
const GRAMMAR: Record<string, string> = {
  python: "tree-sitter-python.wasm",
  java: "tree-sitter-java.wasm",
  csharp: "tree-sitter-c-sharp.wasm",
  c: "tree-sitter-cpp.wasm",
  cpp: "tree-sitter-cpp.wasm",
  go: "tree-sitter-go.wasm",
  rust: "tree-sitter-rust.wasm",
  ruby: "tree-sitter-ruby.wasm",
  php: "tree-sitter-php.wasm",
  shellscript: "tree-sitter-bash.wasm",
  powershell: "tree-sitter-powershell.wasm",
};

/** Languages whose syntax errors are shown (tree-sitter ones + Monaco's own checkers). */
export const CHECKED_LANGUAGES = [...Object.keys(GRAMMAR), "typescript", "javascript", "tsx", "jsx", "json", "jsonc", "css", "scss", "less"];

const BASE = "/tree-sitter/";
const MAX_CHARS = 1_000_000;
const MAX_MARKERS = 100;
const DELAY_MS = 350;

type TS = typeof TreeSitter;
let runtime: Promise<TS> | null = null;
const languages = new Map<string, Promise<TreeSitter.Language>>();
const parsers = new Map<string, TreeSitter.Parser>();

/**
 * Loads the tree-sitter runtime. Its UMD wrapper would register itself with Monaco's AMD loader (a global `define`)
 * when loaded as a <script>, so it is evaluated with its own `module` / `exports` instead.
 */
function loadRuntime(): Promise<TS> {
  runtime ??= (async () => {
    const code = await (await fetch(BASE + "tree-sitter.js")).text();
    const mod: { exports: unknown } = { exports: {} };
    // eslint-disable-next-line no-new-func
    new Function("module", "exports", "define", code)(mod, mod.exports, undefined);
    const ts = mod.exports as TS;
    await ts.Parser.init({ locateFile: (file: string) => BASE + file });
    return ts;
  })();
  runtime.catch(() => (runtime = null)); // try again next time
  return runtime;
}

async function parserFor(language: string): Promise<TreeSitter.Parser | null> {
  const file = GRAMMAR[language];
  if (!file) return null;
  const cached = parsers.get(file);
  if (cached) return cached;
  const ts = await loadRuntime();
  let lang = languages.get(file);
  if (!lang) {
    lang = ts.Language.load(BASE + file);
    languages.set(file, lang);
    lang.catch(() => languages.delete(file));
  }
  const parser = new ts.Parser();
  parser.setLanguage(await lang);
  parsers.set(file, parser);
  return parser;
}

export interface SyntaxProblem {
  message: string;
  startLine: number; // 1-based, like Monaco
  startColumn: number;
  endLine: number;
  endColumn: number;
}

const short = (text: string) => {
  const t = text.replace(/\s+/g, " ").trim();
  return t.length > 30 ? t.slice(0, 29) + "…" : t;
};

/** The first token (leaf) inside a node: where the parser first got stuck. */
function firstLeaf(node: TreeSitter.Node): TreeSitter.Node {
  let n = node;
  while (n.childCount > 0) {
    const c = n.child(0);
    if (!c) break;
    n = c;
  }
  return n;
}

/** Turns the ERROR / MISSING nodes of a parse tree into problems, pointing at the exact spot where possible. */
export function problemsFromTree(root: TreeSitter.Node, lines: string[]): SyntaxProblem[] {
  const out: SyntaxProblem[] = [];
  const lineEnd = (row: number) => (lines[row]?.length ?? 0) + 1;
  const visit = (node: TreeSitter.Node) => {
    if (out.length >= MAX_MARKERS) return;
    if (node.isMissing) {
      // zero-width: underline the character before it, where the missing token belongs. At the start of a line that
      // is the end of the last line with code (an empty line would show no squiggle).
      let { row, column } = node.startPosition;
      if (column === 0) {
        let r = row - 1;
        while (r >= 0 && !(lines[r] ?? "").trim()) r--;
        if (r >= 0) {
          row = r;
          column = (lines[r] ?? "").trimEnd().length;
        }
      }
      const start = Math.max(column, 1);
      out.push({ message: `Syntax error: missing "${node.type}"`, startLine: row + 1, startColumn: start, endLine: row + 1, endColumn: Math.min(start + 1, lineEnd(row) + 1) });
      return;
    }
    if (node.isError) {
      const s = node.startPosition;
      const e = node.endPosition;
      if (s.row === e.row && e.column > s.column) {
        out.push({ message: `Syntax error: unexpected "${short(node.text)}"`, startLine: s.row + 1, startColumn: s.column + 1, endLine: e.row + 1, endColumn: e.column + 1 });
      } else {
        // a long error region: point at the token where parsing first failed, as VS Code does
        const leaf = firstLeaf(node);
        const ls = leaf.startPosition;
        const le = leaf.endPosition;
        const endCol = le.row === ls.row && le.column > ls.column ? le.column + 1 : lineEnd(ls.row);
        out.push({ message: `Syntax error: unexpected "${short(leaf.text || lines[ls.row]?.slice(ls.column) || "")}"`, startLine: ls.row + 1, startColumn: ls.column + 1, endLine: ls.row + 1, endColumn: Math.max(endCol, ls.column + 2) });
      }
      // MISSING tokens inside an error region are still worth reporting
      for (const c of node.children) if (c && c.isMissing) visit(c);
      return;
    }
    if (!node.hasError) return;
    for (const c of node.children) if (c) visit(c);
  };
  visit(root);
  return out;
}

/** Parses `text` as `language`; null when the language has no tree-sitter grammar here. */
export async function checkSyntax(language: string, text: string): Promise<SyntaxProblem[] | null> {
  const parser = await parserFor(language);
  if (!parser) return null;
  if (text.length > MAX_CHARS) return [];
  const tree = parser.parse(text);
  if (!tree) return [];
  try {
    return tree.rootNode.hasError ? problemsFromTree(tree.rootNode, text.split("\n")) : [];
  } finally {
    tree.delete();
  }
}

type Model = ReturnType<Monaco["editor"]["createModel"]>;
let installed = false;

/** Checks every editor model (now and later) whose language has a grammar, again shortly after each change. */
export function installSyntaxChecker(monaco: Monaco): void {
  if (installed) return;
  installed = true;
  const attach = (model: Model) => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    let run = 0;
    const check = () => {
      timer = null;
      const lang = model.getLanguageId();
      if (!GRAMMAR[lang]) {
        monaco.editor.setModelMarkers(model, SYNTAX_OWNER, []);
        return;
      }
      const mine = ++run;
      const version = model.getVersionId();
      checkSyntax(lang, model.getValue())
        .then((problems) => {
          if (model.isDisposed() || mine !== run || version !== model.getVersionId() || !problems) return;
          monaco.editor.setModelMarkers(
            model,
            SYNTAX_OWNER,
            problems.map((p) => ({
              message: p.message,
              startLineNumber: p.startLine,
              startColumn: p.startColumn,
              endLineNumber: p.endLine,
              endColumn: p.endColumn,
              severity: monaco.MarkerSeverity.Error,
              source: "syntax",
            })),
          );
        })
        .catch((e: unknown) => console.warn("Syntax check failed", e));
    };
    const schedule = (delay: number) => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(check, delay);
    };
    model.onDidChangeContent(() => schedule(DELAY_MS));
    model.onDidChangeLanguage(() => schedule(0));
    model.onWillDispose(() => {
      if (timer) clearTimeout(timer);
      monaco.editor.setModelMarkers(model, SYNTAX_OWNER, []); // a closed file has no problems listed any more
    });
    schedule(0);
  };
  monaco.editor.getModels().forEach(attach);
  monaco.editor.onDidCreateModel(attach);
}
