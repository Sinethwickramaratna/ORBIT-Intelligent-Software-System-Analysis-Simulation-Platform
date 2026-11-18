// Copies Monaco (the VS Code editor) into public/monaco/vs so the editor is served by ORBIT itself - no CDN, works
// offline and inside Docker. Runs automatically before `npm run dev` and `npm run build` (predev / prebuild).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
// the package's "exports" map hides package.json and min/, so use the installed folder directly
const pkgDir = path.join(root, "node_modules", "monaco-editor");
const { version } = JSON.parse(fs.readFileSync(path.join(pkgDir, "package.json"), "utf8"));
const src = path.join(pkgDir, "min", "vs");
const dest = path.join(root, "public", "monaco", "vs");
const stamp = path.join(root, "public", "monaco", ".version");

if (!fs.existsSync(path.join(src, "loader.js"))) {
  console.error(`copy-monaco: ${src} has no loader.js - is monaco-editor installed?`);
  process.exit(1);
}
if (fs.existsSync(stamp) && fs.readFileSync(stamp, "utf8").trim() === version && fs.existsSync(path.join(dest, "loader.js"))) {
  console.log(`copy-monaco: monaco-editor ${version} already in public/monaco`);
  process.exit(0);
}
fs.rmSync(path.join(root, "public", "monaco"), { recursive: true, force: true });
// source maps are left out: they are only for debugging Monaco itself and double the size
fs.cpSync(src, dest, { recursive: true, filter: (p) => !p.endsWith(".map") });
fs.writeFileSync(stamp, version + "\n");
console.log(`copy-monaco: copied monaco-editor ${version} to public/monaco/vs`);
