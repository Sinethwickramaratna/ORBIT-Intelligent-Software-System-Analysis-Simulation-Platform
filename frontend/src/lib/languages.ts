import { bundledLanguagesInfo } from "shiki";

/**
 * Which VS Code language (TextMate grammar shipped with Shiki) a file is coloured with. Kept free of browser APIs so
 * it can be tested on its own.
 */

/** Whole file names (lower-case) that decide the language on their own. */
const BY_NAME: Record<string, string> = {
  dockerfile: "docker",
  containerfile: "docker",
  makefile: "make",
  gnumakefile: "make",
  "cmakelists.txt": "cmake",
  ".env": "dotenv",
  ".editorconfig": "ini",
  ".npmrc": "ini",
  ".gitconfig": "ini",
  ".gitattributes": "ini",
  ".eslintrc": "json",
  ".prettierrc": "json",
  ".babelrc": "json",
  ".swcrc": "json",
  ".bashrc": "shellscript",
  ".zshrc": "shellscript",
  ".profile": "shellscript",
  jenkinsfile: "groovy",
  gemfile: "ruby",
  rakefile: "ruby",
  vagrantfile: "ruby",
  "nginx.conf": "nginx",
  codeowners: "codeowners",
};

/** Extensions VS Code maps differently from Shiki's own names, or that Shiki has no alias for. */
const BY_EXTENSION: Record<string, string> = {
  mjs: "javascript",
  cjs: "javascript",
  h: "cpp",
  hh: "cpp",
  hpp: "cpp",
  hxx: "cpp",
  cc: "cpp",
  cxx: "cpp",
  "c++": "cpp",
  ino: "cpp",
  gradle: "groovy",
  kts: "kotlin",
  svg: "xml",
  csproj: "xml",
  fsproj: "xml",
  vbproj: "xml",
  props: "xml",
  targets: "xml",
  xaml: "xml",
  plist: "xml",
  xsd: "xml",
  xsl: "xml",
  xslt: "xml",
  resx: "xml",
  nuspec: "xml",
  cshtml: "razor",
  ipynb: "json",
  webmanifest: "json",
  properties: "ini",
  cfg: "ini",
  conf: "ini",
  htm: "html",
  xhtml: "html",
  txt: "plaintext",
  text: "plaintext",
};

/** Shiki language ids and all their aliases (ts, py, yml, ...) -> language id. */
const ALIASES = new Map<string, string>();
const NAMES = new Map<string, string>();
for (const info of bundledLanguagesInfo) {
  ALIASES.set(info.id, info.id);
  for (const alias of info.aliases ?? []) ALIASES.set(alias, info.id);
  NAMES.set(info.id, info.name);
}

/** The language id for a project path such as "src/App.tsx"; "plaintext" when nothing fits. */
export function languageForPath(filePath: string): string {
  const name = filePath.slice(filePath.lastIndexOf("/") + 1).toLowerCase();
  if (BY_NAME[name]) return BY_NAME[name];
  if (name.startsWith("dockerfile.") || name.endsWith(".dockerfile")) return "docker";
  if (name.startsWith(".env.")) return "dotenv";
  if (name.endsWith(".gradle.kts")) return "kotlin";
  const dot = name.lastIndexOf(".");
  if (dot <= 0) return "plaintext"; // no extension, or a dot-file not listed above
  const ext = name.slice(dot + 1);
  return BY_EXTENSION[ext] ?? ALIASES.get(ext) ?? "plaintext";
}

/** Display name for the status bar, e.g. "TypeScript"; "Plain Text" for files without a grammar. */
export function languageName(id: string): string {
  return id === "plaintext" ? "Plain Text" : (NAMES.get(id) ?? id);
}
