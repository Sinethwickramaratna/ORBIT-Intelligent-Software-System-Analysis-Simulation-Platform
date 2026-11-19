// Copies VS Code's tree-sitter build (@vscode/tree-sitter-wasm: the parser runtime + language grammars) into
// public/tree-sitter, so ORBIT can underline syntax errors offline. Runs before `npm run dev` / `npm run build`.
// Only the grammars used for syntax checking are copied (see src/lib/diagnostics.ts).
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const pkg = path.join(root, "node_modules", "@vscode", "tree-sitter-wasm");
const src = path.join(pkg, "wasm");
const dest = path.join(root, "public", "tree-sitter");
const files = [
  "tree-sitter.js", "tree-sitter.wasm",
  "tree-sitter-python.wasm", "tree-sitter-java.wasm", "tree-sitter-c-sharp.wasm", "tree-sitter-cpp.wasm",
  "tree-sitter-go.wasm", "tree-sitter-rust.wasm", "tree-sitter-ruby.wasm", "tree-sitter-php.wasm",
  "tree-sitter-bash.wasm", "tree-sitter-powershell.wasm",
];
if (!fs.existsSync(path.join(src, "tree-sitter.js"))) {
  console.error(`copy-tree-sitter: ${src} is missing - is @vscode/tree-sitter-wasm installed?`);
  process.exit(1);
}
const { version } = JSON.parse(fs.readFileSync(path.join(pkg, "package.json"), "utf8"));
const stamp = path.join(dest, ".version");
const want = `${version} ${files.join(",")}`;
if (fs.existsSync(stamp) && fs.readFileSync(stamp, "utf8").trim() === want) {
  console.log(`copy-tree-sitter: @vscode/tree-sitter-wasm ${version} already in public/tree-sitter`);
  process.exit(0);
}
fs.rmSync(dest, { recursive: true, force: true });
fs.mkdirSync(dest, { recursive: true });
for (const f of files) fs.copyFileSync(path.join(src, f), path.join(dest, f));
fs.writeFileSync(stamp, want + "\n");
console.log(`copy-tree-sitter: copied ${files.length} files of @vscode/tree-sitter-wasm ${version} to public/tree-sitter`);
