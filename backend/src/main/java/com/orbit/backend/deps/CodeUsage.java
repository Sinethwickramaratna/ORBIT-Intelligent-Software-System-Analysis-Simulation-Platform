package com.orbit.backend.deps;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the project's source code really uses, so Setup Dependencies downloads only the declared dependencies that are
 * needed. It reads the imports of the JavaScript / TypeScript (and CSS), Python and Dart files, and the names mentioned
 * in configuration, build and CI files (package.json scripts, tool configs, Dockerfiles, Procfiles, workflows ...).
 * <p>
 * It is deliberately generous: a dependency counts as used when any of its import names, command names or plugin short
 * names shows up. Only dependencies with no trace in the code are "not used". When a folder has no source files of the
 * language at all, nothing can be judged and everything counts as used.
 */
public final class CodeUsage {

    private static final int MAX_DEPTH = 16;
    private static final int MAX_FILES = 60_000;
    private static final long MAX_FILE_BYTES = 1L << 20;
    private static final long MAX_TOTAL_BYTES = 400L << 20;

    private static final Set<String> JS_EXT = Set.of("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "vue", "svelte", "astro");
    private static final Set<String> CSS_EXT = Set.of("css", "scss", "sass", "less", "styl");
    private static final Set<String> TEXT_EXT = Set.of("yml", "yaml", "ini", "cfg", "toml", "sh", "bash", "bat", "cmd", "ps1",
            "conf", "service", "properties", "mk");

    /** One scanned source file: its folder (relative, {@code /} separated) and the names it uses. */
    private record Source(String dir, Set<String> names, Set<String> flags) {
    }

    private final List<Source> js = new ArrayList<>();
    private final List<Source> py = new ArrayList<>();
    private final List<Source> dart = new ArrayList<>();
    /** folder -> lower-case text of the configuration / build files in it. */
    private final List<Map.Entry<String, String>> texts = new ArrayList<>();
    private final Map<String, Set<String>> cache = new ConcurrentHashMap<>();
    private final Map<String, String> textCache = new ConcurrentHashMap<>();
    private final boolean everythingUsed;

    private CodeUsage(boolean everythingUsed) {
        this.everythingUsed = everythingUsed;
    }

    /** Treats every dependency as used (when the option to skip unused dependencies is off, and in tests). */
    public static CodeUsage everything() {
        return new CodeUsage(true);
    }

    public static CodeUsage scan(Path root) {
        CodeUsage u = new CodeUsage(false);
        Path base = root.toAbsolutePath().normalize();
        long[] budget = {0, 0}; // files, bytes
        try {
            Files.walkFileTree(base, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(base)) {
                        String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (ProjectFiles.SKIPPED.contains(name) || name.equals("site-packages") || name.equals("vendor")
                                || Files.exists(dir.resolve("pyvenv.cfg"))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (++budget[0] > MAX_FILES || budget[1] > MAX_TOTAL_BYTES) {
                        return FileVisitResult.TERMINATE;
                    }
                    String rel = base.relativize(file).toString().replace('\\', '/');
                    if (u.read(file, rel)) {
                        budget[1] += attrs.size();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // what was read so far is used
        }
        return u;
    }

    // ------------------------------------------------------------------------------------------ reading

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String text(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            try {
                return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            } catch (IOException ex) {
                return null;
            }
        }
    }

    /** Reads one file when it is source or configuration; true when it was read. */
    private boolean read(Path file, String rel) {
        String name = ProjectFiles.fileName(rel);
        String lower = name.toLowerCase(Locale.ROOT);
        String dir = ProjectFiles.dirOf(rel);
        String ext = ext(lower);
        if (lower.endsWith(".min.js") || lower.endsWith(".map") || lower.equals("package-lock.json")) {
            return false;
        }
        if (JS_EXT.contains(ext) || CSS_EXT.contains(ext)) {
            String t = text(file);
            if (t == null) {
                return false;
            }
            Set<String> flags = new HashSet<>();
            flags.add(CSS_EXT.contains(ext) ? "css" : "js");
            flags.add(ext);
            Set<String> names = CSS_EXT.contains(ext) ? cssImports(t) : jsImports(t);
            if (isConfigName(lower)) {
                addText(dir, t); // a tool config: plugin names are mentioned, not imported
            }
            synchronized (js) {
                js.add(new Source(dir, names, flags));
            }
            return true;
        }
        if (ext.equals("py") || ext.equals("pyi") || ext.equals("ipynb")) {
            String t = text(file);
            if (t == null) {
                return false;
            }
            if (ext.equals("ipynb")) {
                t = notebookCode(t);
            }
            Set<String> flags = new HashSet<>();
            if (lower.startsWith("test_") || lower.endsWith("_test.py") || lower.equals("conftest.py")) {
                flags.add("tests");
            }
            if (ext.equals("ipynb")) {
                flags.add("notebook");
            }
            String lt = t.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> k : PY_KEYWORDS.entrySet()) {
                for (String word : k.getValue()) {
                    if (lt.contains(word)) {
                        flags.add("kw:" + k.getKey());
                        break;
                    }
                }
            }
            py.add(new Source(dir, pythonImports(t), flags));
            return true;
        }
        if (ext.equals("dart")) {
            String t = text(file);
            if (t == null) {
                return false;
            }
            Set<String> flags = new HashSet<>();
            String lt = t.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> k : DART_KEYWORDS.entrySet()) {
                for (String word : k.getValue()) {
                    if (lt.contains(word)) {
                        flags.add("kw:" + k.getKey());
                        break;
                    }
                }
            }
            dart.add(new Source(dir, dartImports(t), flags));
            return true;
        }
        if (lower.equals("package.json")) {
            String t = text(file);
            if (t != null) {
                addText(dir, packageJsonWithoutDependencies(t));
            }
            return t != null;
        }
        if (lower.equals("pubspec.yaml")) {
            String t = text(file);
            if (t != null) {
                addText(dir, pubspecWithoutDependencies(t));
            }
            return t != null;
        }
        if (lower.equals("pyproject.toml")) {
            String t = text(file);
            if (t != null) {
                addText(dir, pyprojectTools(t));
            }
            return t != null;
        }
        if (lower.startsWith("requirements") || lower.equals("pipfile") || lower.equals("pubspec.lock")
                || lower.equals("poetry.lock") || lower.equals("pipfile.lock") || lower.equals("yarn.lock")
                || lower.equals("pnpm-lock.yaml") || lower.equals("uv.lock")) {
            return false; // dependency lists: mentioning a name there is not using it
        }
        boolean config = TEXT_EXT.contains(ext) || lower.startsWith("dockerfile") || lower.endsWith(".dockerfile")
                || lower.equals("procfile") || lower.equals("makefile") || lower.equals("justfile") || lower.equals("tox.ini")
                || lower.equals("setup.cfg") || lower.equals("setup.py") || lower.equals("manage.py")
                || (ext.equals("json") && isConfigName(lower)) || (lower.startsWith(".") && lower.contains("rc"))
                || lower.equals("analysis_options.yaml") || lower.equals("build.yaml");
        if (config) {
            String t = text(file);
            if (t != null) {
                addText(dir, t);
                if (ext.equals("yaml") || ext.equals("yml")) {
                    // analysis_options.yaml: include: package:flutter_lints/flutter.yaml
                    Set<String> names = dartImports(t);
                    if (!names.isEmpty()) {
                        dart.add(new Source(dir, names, Set.of("config")));
                    }
                }
            }
            return t != null;
        }
        return false;
    }

    private static boolean isConfigName(String lower) {
        return lower.contains(".config.") || lower.startsWith("tsconfig") || lower.startsWith("jsconfig")
                || lower.matches("^\\.[\\w-]*rc(\\.\\w+)?$") || lower.equals("angular.json") || lower.equals("nx.json")
                || lower.equals("project.json") || lower.equals("workspace.json") || lower.equals("app.json")
                || lower.equals("eas.json") || lower.equals("components.json") || lower.equals("vercel.json")
                || lower.equals("firebase.json") || lower.equals("turbo.json") || lower.equals("lerna.json")
                || lower.equals("babel.config.json") || lower.startsWith("vite.") || lower.startsWith("webpack.")
                || lower.startsWith("rollup.") || lower.startsWith("gulpfile") || lower.startsWith("gruntfile")
                || lower.startsWith("karma.") || lower.startsWith("protractor.") || lower.equals("metro.config.js")
                || lower.startsWith("setuptests") || lower.startsWith("jest.") || lower.startsWith("vitest.")
                || lower.startsWith("playwright.") || lower.startsWith("cypress.");
    }

    private void addText(String dir, String text) {
        synchronized (texts) {
            texts.add(Map.entry(dir, text.toLowerCase(Locale.ROOT)));
        }
    }

    // ------------------------------------------------------------------------------------------ JavaScript

    private static final Pattern JS_IMPORT = Pattern.compile(
            "(?:\\bfrom\\s*|\\bimport\\s*\\(?\\s*|\\brequire(?:\\.resolve)?\\s*\\(\\s*|\\bexport\\s[^;'\"]*?\\bfrom\\s*"
                    + "|\\bjest\\.(?:mock|requireActual)\\(\\s*|\\bvi\\.mock\\(\\s*)['\"`]([^'\"`\\s]+)['\"`]");
    private static final Pattern CSS_IMPORT = Pattern.compile("@(?:import|use|forward|plugin|config|reference)\\s+(?:url\\()?['\"]~?([^'\"]+)['\"]");

    static Set<String> jsImports(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = JS_IMPORT.matcher(text);
        while (m.find()) {
            String p = packageOf(m.group(1));
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    static Set<String> cssImports(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = CSS_IMPORT.matcher(text);
        while (m.find()) {
            String p = packageOf(m.group(1));
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** The package of a bare module specifier ({@code @mui/material/Button} -> {@code @mui/material}); null for relative paths. */
    static String packageOf(String spec) {
        String s = spec.trim();
        if (s.startsWith("node:")) {
            return null;
        }
        if (s.startsWith("npm:")) {
            s = s.substring(4);
        }
        if (s.isEmpty() || s.startsWith(".") || s.startsWith("/") || s.startsWith("#") || s.contains("://")
                || s.startsWith("~/") || s.startsWith("@/") || s.startsWith("$")) {
            return null;
        }
        String[] parts = s.split("/");
        String name = s.startsWith("@") ? (parts.length < 2 ? null : parts[0] + "/" + parts[1]) : parts[0];
        if (name == null || !name.matches("^(@[a-z0-9][\\w.~-]*/)?[a-z0-9][\\w.~-]*$")) {
            return null;
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static String packageJsonWithoutDependencies(String text) {
        JsonNode json = InstallContext.json(text).orElse(null);
        if (json == null || !json.isObject()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var e : json.properties()) {
            String k = e.getKey();
            if (k.endsWith("ependencies") || k.equals("name") || k.equals("version") || k.equals("description")
                    || k.equals("overrides") || k.equals("resolutions") || k.equals("peerDependenciesMeta")) {
                continue;
            }
            sb.append(k).append(": ").append(e.getValue().toString()).append('\n');
        }
        return sb.toString();
    }

    /** Command names of well-known packages whose command is not the package name. */
    private static final Map<String, List<String>> KNOWN_BINS = Map.ofEntries(
            Map.entry("typescript", List.of("tsc", "tsserver")),
            Map.entry("@angular/cli", List.of("ng")),
            Map.entry("@nestjs/cli", List.of("nest")),
            Map.entry("@vue/cli-service", List.of("vue-cli-service")),
            Map.entry("@expo/cli", List.of("expo")),
            Map.entry("npm-run-all", List.of("run-s", "run-p", "npm-run-all")),
            Map.entry("npm-run-all2", List.of("run-s", "run-p", "npm-run-all")),
            Map.entry("webpack-cli", List.of("webpack")),
            Map.entry("webpack-dev-server", List.of("webpack-dev-server", "webpack serve")),
            Map.entry("@biomejs/biome", List.of("biome")),
            Map.entry("@playwright/test", List.of("playwright")),
            Map.entry("@storybook/cli", List.of("storybook", "sb")),
            Map.entry("storybook", List.of("storybook", "sb")),
            Map.entry("@sveltejs/kit", List.of("svelte-kit")),
            Map.entry("@remix-run/dev", List.of("remix")),
            Map.entry("@react-native-community/cli", List.of("react-native")),
            Map.entry("ts-node", List.of("ts-node", "ts-node-dev")),
            Map.entry("ts-node-dev", List.of("ts-node-dev", "tsnd")),
            Map.entry("@swc/cli", List.of("swc")),
            Map.entry("@babel/cli", List.of("babel")),
            Map.entry("@babel/node", List.of("babel-node")),
            Map.entry("prisma", List.of("prisma")),
            Map.entry("drizzle-kit", List.of("drizzle-kit")),
            Map.entry("@11ty/eleventy", List.of("eleventy")),
            Map.entry("@electron-forge/cli", List.of("electron-forge")),
            Map.entry("http-server", List.of("http-server")),
            Map.entry("json-server", List.of("json-server")),
            Map.entry("cross-env", List.of("cross-env")),
            Map.entry("dotenv-cli", List.of("dotenv")),
            Map.entry("@tailwindcss/cli", List.of("tailwindcss")));

    /** Prefixes of plugin packages that configs name by a short form ({@code eslint-plugin-react} -> {@code react}). */
    private static final List<String> PLUGIN_PREFIXES = List.of("eslint-plugin-", "eslint-config-", "prettier-plugin-",
            "babel-plugin-", "babel-preset-", "@babel/plugin-", "@babel/preset-", "postcss-", "stylelint-config-",
            "stylelint-plugin-", "jest-environment-", "karma-", "remark-", "rehype-", "gatsby-plugin-", "vite-plugin-",
            "@vitejs/plugin-", "rollup-plugin-", "@rollup/plugin-", "markdownlint-", "commitlint-config-",
            "@commitlint/config-", "tailwindcss-", "@tailwindcss/");

    /**
     * Whether the JavaScript / TypeScript code under {@code dir} uses npm package {@code name}. {@code bins} are its
     * command names (from the lockfile or its package.json), used to recognise it in scripts.
     */
    public boolean jsUsed(String dir, String name, Collection<String> bins) {
        if (everythingUsed || !hasSources(js, dir)) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        Set<String> imports = names(js, "js", dir);
        Set<String> flags = flags(js, "js", dir);
        if (imports.contains(n)) {
            return true;
        }
        boolean ts = flags.contains("ts") || flags.contains("tsx") || flags.contains("mts") || flags.contains("cts")
                || mentioned(dir, "typescript") || hasTsConfig(dir);
        switch (n) {
            case "typescript", "tslib" -> {
                if (ts) {
                    return true;
                }
            }
            case "react" -> {
                if (flags.contains("jsx") || flags.contains("tsx")) {
                    return true;
                }
            }
            case "vue" -> {
                if (flags.contains("vue")) {
                    return true;
                }
            }
            case "svelte" -> {
                if (flags.contains("svelte")) {
                    return true;
                }
            }
            case "astro" -> {
                if (flags.contains("astro")) {
                    return true;
                }
            }
            case "sass", "sass-embedded", "node-sass" -> {
                if (flags.contains("scss") || flags.contains("sass")) {
                    return true;
                }
            }
            case "less" -> {
                if (flags.contains("less")) {
                    return true;
                }
            }
            default -> {
            }
        }
        // a package another used package needs at run time without an import of its own
        if (n.equals("react-dom") && jsUsed(dir, "react", List.of())) {
            return true;
        }
        if ((n.equals("@angular/compiler-cli") || n.equals("@angular/compiler") || n.equals("@angular/platform-browser-dynamic")
                || n.equals("zone.js")) && imports.stream().anyMatch(i -> i.startsWith("@angular/"))) {
            return true;
        }
        if (n.startsWith("@types/")) {
            if (!ts) {
                return false;
            }
            String typed = n.substring("@types/".length());
            typed = typed.contains("__") ? "@" + typed.replace("__", "/") : typed;
            return typed.equals("node") || imports.contains(typed) || mentioned(dir, typed)
                    || (!typed.equals(name) && jsUsed(dir, typed, List.of()));
        }
        Set<String> words = new LinkedHashSet<>();
        words.add(n);
        bins.forEach(b -> words.add(b.toLowerCase(Locale.ROOT)));
        words.addAll(KNOWN_BINS.getOrDefault(n, List.of()));
        if (n.contains("/")) {
            words.add(n.substring(n.indexOf('/') + 1).equals("cli") ? n.substring(1, n.indexOf('/')) : n.substring(n.indexOf('/') + 1));
            if (n.endsWith("/eslint-plugin") || n.endsWith("/eslint-config")) {
                words.add(n.substring(0, n.indexOf('/'))); // @typescript-eslint/eslint-plugin -> @typescript-eslint
            }
        }
        for (String prefix : PLUGIN_PREFIXES) {
            if (n.startsWith(prefix) && n.length() > prefix.length()) {
                words.add(n.substring(prefix.length()));
            }
        }
        for (String w : words) {
            if (w.length() >= 2 && mentioned(dir, w)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTsConfig(String dir) {
        return texts(dir).contains("compileroptions");
    }

    // ------------------------------------------------------------------------------------------ Python

    private static final Pattern PY_IMPORT = Pattern.compile("(?m)^[ \\t]*import[ \\t]+([\\w. \\t,]+)");
    private static final Pattern PY_FROM = Pattern.compile("(?m)^[ \\t]*from[ \\t]+([\\w.]+)[ \\t]+import[ \\t]+\\(?[ \\t]*([\\w \\t,*]*)");
    private static final Pattern PY_STRING = Pattern.compile("['\"]([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)['\"]");

    static Set<String> pythonImports(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = PY_IMPORT.matcher(text);
        while (m.find()) {
            for (String part : m.group(1).split(",")) {
                String mod = part.trim().split("\\s+")[0];
                addModule(out, mod);
            }
        }
        m = PY_FROM.matcher(text);
        while (m.find()) {
            String base = m.group(1);
            if (base.startsWith(".")) {
                continue; // relative import: the project's own code
            }
            addModule(out, base);
            for (String part : m.group(2).split(",")) {
                String n = part.trim().split("\\s+")[0];
                if (!n.isEmpty() && !n.equals("*")) {
                    addModule(out, base + "." + n);
                }
            }
        }
        // dotted names in strings: Django INSTALLED_APPS, importlib.import_module("x"), "uvicorn.workers.UvicornWorker"
        m = PY_STRING.matcher(text);
        while (m.find()) {
            String s = m.group(1);
            if (s.length() >= 2 && s.length() <= 120) {
                addModule(out, s);
            }
        }
        return out;
    }

    private static void addModule(Set<String> out, String dotted) {
        if (dotted.isEmpty()) {
            return;
        }
        String[] parts = dotted.toLowerCase(Locale.ROOT).split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                return;
            }
            if (!sb.isEmpty()) {
                sb.append('.');
            }
            sb.append(p);
            out.add(sb.toString());
        }
    }

    private static String notebookCode(String text) {
        JsonNode nb = InstallContext.json(text).orElse(null);
        if (nb == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode cell : nb.path("cells")) {
            if (!"code".equals(InstallContext.text(cell, "cell_type"))) {
                continue;
            }
            JsonNode src = cell.path("source");
            if (src.isArray()) {
                src.forEach(l -> sb.append(l.asString("")));
            } else {
                sb.append(src.asString(""));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String pyprojectTools(String text) {
        // only the [tool.x] tables say a tool is used; the dependency lists are not usage
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("[")) {
                in = t.startsWith("[tool.") && !t.startsWith("[tool.poetry");
                if (t.startsWith("[tool.")) {
                    sb.append(t).append('\n'); // [tool.black] -> black
                }
                continue;
            }
            if (in) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString().replace("[tool.", "[ ");
    }

    /** Distributions whose import name differs from the distribution name. */
    private static final Map<String, List<String>> PY_MODULES = Map.ofEntries(
            Map.entry("pyyaml", List.of("yaml")),
            Map.entry("pillow", List.of("pil")),
            Map.entry("beautifulsoup4", List.of("bs4")),
            Map.entry("beautifulsoup", List.of("beautifulsoup")),
            Map.entry("scikit-learn", List.of("sklearn")),
            Map.entry("scikit-image", List.of("skimage")),
            Map.entry("opencv-python", List.of("cv2")),
            Map.entry("opencv-python-headless", List.of("cv2")),
            Map.entry("opencv-contrib-python", List.of("cv2")),
            Map.entry("opencv-contrib-python-headless", List.of("cv2")),
            Map.entry("python-dateutil", List.of("dateutil")),
            Map.entry("python-dotenv", List.of("dotenv")),
            Map.entry("python-jose", List.of("jose")),
            Map.entry("python-multipart", List.of("multipart", "python_multipart")),
            Map.entry("python-decouple", List.of("decouple")),
            Map.entry("python-magic", List.of("magic")),
            Map.entry("python-docx", List.of("docx")),
            Map.entry("python-pptx", List.of("pptx")),
            Map.entry("python-slugify", List.of("slugify")),
            Map.entry("python-json-logger", List.of("pythonjsonlogger")),
            Map.entry("python-telegram-bot", List.of("telegram")),
            Map.entry("python-socketio", List.of("socketio")),
            Map.entry("python-engineio", List.of("engineio")),
            Map.entry("python-levenshtein", List.of("levenshtein")),
            Map.entry("pyjwt", List.of("jwt")),
            Map.entry("pymongo", List.of("pymongo", "bson", "gridfs")),
            Map.entry("protobuf", List.of("google.protobuf")),
            Map.entry("grpcio", List.of("grpc")),
            Map.entry("grpcio-tools", List.of("grpc_tools")),
            Map.entry("google-api-python-client", List.of("googleapiclient", "apiclient")),
            Map.entry("google-auth", List.of("google.auth", "google.oauth2")),
            Map.entry("google-generativeai", List.of("google.generativeai")),
            Map.entry("google-genai", List.of("google.genai")),
            Map.entry("psycopg2-binary", List.of("psycopg2")),
            Map.entry("psycopg-binary", List.of("psycopg")),
            Map.entry("mysqlclient", List.of("mysqldb")),
            Map.entry("mysql-connector-python", List.of("mysql.connector", "mysql")),
            Map.entry("pycryptodome", List.of("crypto")),
            Map.entry("pycryptodomex", List.of("cryptodome")),
            Map.entry("pyopenssl", List.of("openssl")),
            Map.entry("attrs", List.of("attr", "attrs")),
            Map.entry("msgpack-python", List.of("msgpack")),
            Map.entry("pyserial", List.of("serial")),
            Map.entry("pyusb", List.of("usb")),
            Map.entry("pywin32", List.of("win32api", "win32con", "win32com", "win32gui", "win32process", "pythoncom", "pywintypes", "win32file")),
            Map.entry("pyzmq", List.of("zmq")),
            Map.entry("faiss-cpu", List.of("faiss")),
            Map.entry("faiss-gpu", List.of("faiss")),
            Map.entry("tensorflow-gpu", List.of("tensorflow")),
            Map.entry("tensorflow-cpu", List.of("tensorflow")),
            Map.entry("tf-keras", List.of("tf_keras")),
            Map.entry("pytorch-lightning", List.of("pytorch_lightning", "lightning")),
            Map.entry("djangorestframework", List.of("rest_framework")),
            Map.entry("djangorestframework-simplejwt", List.of("rest_framework_simplejwt")),
            Map.entry("django-cors-headers", List.of("corsheaders")),
            Map.entry("django-environ", List.of("environ")),
            Map.entry("django-filter", List.of("django_filters")),
            Map.entry("django-crispy-forms", List.of("crispy_forms")),
            Map.entry("django-storages", List.of("storages")),
            Map.entry("django-debug-toolbar", List.of("debug_toolbar")),
            Map.entry("django-allauth", List.of("allauth")),
            Map.entry("django-celery-beat", List.of("django_celery_beat")),
            Map.entry("django-import-export", List.of("import_export")),
            Map.entry("django-phonenumber-field", List.of("phonenumber_field")),
            Map.entry("django-ckeditor", List.of("ckeditor")),
            Map.entry("setuptools", List.of("setuptools", "pkg_resources")),
            Map.entry("discord-py", List.of("discord")),
            Map.entry("websocket-client", List.of("websocket")),
            Map.entry("umap-learn", List.of("umap")),
            Map.entry("scikit-surprise", List.of("surprise")),
            Map.entry("pyinstaller", List.of("pyinstaller")),
            Map.entry("pygobject", List.of("gi")),
            Map.entry("pycairo", List.of("cairo")),
            Map.entry("ffmpeg-python", List.of("ffmpeg")),
            Map.entry("py-cpuinfo", List.of("cpuinfo")),
            Map.entry("ipython", List.of("ipython")),
            Map.entry("pymupdf", List.of("fitz", "pymupdf")),
            Map.entry("pypdf2", List.of("pypdf2")),
            Map.entry("python-barcode", List.of("barcode")),
            Map.entry("qrcode", List.of("qrcode")),
            Map.entry("pydantic-settings", List.of("pydantic_settings")),
            Map.entry("sqlalchemy-utils", List.of("sqlalchemy_utils")),
            Map.entry("flask-restful", List.of("flask_restful")),
            Map.entry("paho-mqtt", List.of("paho.mqtt", "paho")),
            Map.entry("ruamel-yaml", List.of("ruamel.yaml", "ruamel")),
            Map.entry("typing-extensions", List.of("typing_extensions")),
            Map.entry("importlib-metadata", List.of("importlib_metadata")),
            Map.entry("speechrecognition", List.of("speech_recognition")),
            Map.entry("pyaudio", List.of("pyaudio")),
            Map.entry("pyttsx3", List.of("pyttsx3")),
            Map.entry("openai-whisper", List.of("whisper")),
            Map.entry("sentencepiece", List.of("sentencepiece")),
            Map.entry("huggingface-hub", List.of("huggingface_hub")),
            Map.entry("langchain-community", List.of("langchain_community")),
            Map.entry("azure-identity", List.of("azure.identity")),
            Map.entry("boto3", List.of("boto3")),
            Map.entry("jinja2", List.of("jinja2")),
            Map.entry("markupsafe", List.of("markupsafe")),
            Map.entry("dnspython", List.of("dns")),
            Map.entry("gitpython", List.of("git")),
            Map.entry("pyqt5", List.of("pyqt5")),
            Map.entry("pyqt6", List.of("pyqt6")),
            Map.entry("pyside6", List.of("pyside6")),
            Map.entry("wxpython", List.of("wx")),
            Map.entry("pygame-ce", List.of("pygame")),
            Map.entry("tensorflow-intel", List.of("tensorflow")));

    /** Packages used through a setting or a string (a database URL, a file type) instead of an import. */
    private static final Map<String, List<String>> PY_KEYWORDS = Map.ofEntries(
            Map.entry("postgres", List.of("postgresql", "postgres://", "django.db.backends.postgresql")),
            Map.entry("mysql", List.of("mysql", "mariadb")),
            Map.entry("oracle", List.of("oracle")),
            Map.entry("mssql", List.of("mssql", "sql server", "odbc")),
            Map.entry("redis", List.of("redis://", "rediss://", "redis")),
            Map.entry("excel", List.of("read_excel", "to_excel", ".xlsx", "excelwriter")),
            Map.entry("xls", List.of(".xls'", ".xls\"", "xlrd")),
            Map.entry("parquet", List.of("parquet", "feather")),
            Map.entry("image", List.of("imagefield")),
            Map.entry("email", List.of("emailstr")),
            Map.entry("form", List.of("form(", "uploadfile", "file(")),
            Map.entry("bcrypt", List.of("bcrypt")),
            Map.entry("argon2", List.of("argon2")),
            Map.entry("lxml", List.of("lxml")),
            Map.entry("html5lib", List.of("html5lib")),
            Map.entry("matplotlib", List.of("%matplotlib", ".plot(", ".hist(")),
            Map.entry("jinja", List.of("templateresponse", "jinja2templates", "render_template")),
            Map.entry("aiofiles", List.of("staticfiles")),
            Map.entry("gevent", List.of("gevent", "worker-class", "worker_class")),
            Map.entry("whitenoise", List.of("whitenoise")),
            Map.entry("celery", List.of("celery")),
            Map.entry("tabulate", List.of("to_markdown")),
            Map.entry("sqlalchemy", List.of("to_sql(", "read_sql")));

    /** distribution -> keyword groups (see {@link #PY_KEYWORDS}) that make it used. */
    private static final Map<String, List<String>> PY_IMPLICIT = Map.ofEntries(
            Map.entry("psycopg2", List.of("postgres")), Map.entry("psycopg2-binary", List.of("postgres")),
            Map.entry("psycopg", List.of("postgres")), Map.entry("psycopg-binary", List.of("postgres")),
            Map.entry("asyncpg", List.of("postgres")), Map.entry("pg8000", List.of("postgres")),
            Map.entry("mysqlclient", List.of("mysql")), Map.entry("pymysql", List.of("mysql")),
            Map.entry("mysql-connector-python", List.of("mysql")), Map.entry("aiomysql", List.of("mysql")),
            Map.entry("cx-oracle", List.of("oracle")), Map.entry("oracledb", List.of("oracle")),
            Map.entry("pyodbc", List.of("mssql")), Map.entry("pymssql", List.of("mssql")), Map.entry("mssql-django", List.of("mssql")),
            Map.entry("redis", List.of("redis")), Map.entry("django-redis", List.of("redis")), Map.entry("hiredis", List.of("redis")),
            Map.entry("openpyxl", List.of("excel")), Map.entry("xlsxwriter", List.of("excel")), Map.entry("xlrd", List.of("xls", "excel")),
            Map.entry("pyarrow", List.of("parquet")), Map.entry("fastparquet", List.of("parquet")),
            Map.entry("pillow", List.of("image")), Map.entry("email-validator", List.of("email")),
            Map.entry("python-multipart", List.of("form")), Map.entry("bcrypt", List.of("bcrypt")),
            Map.entry("argon2-cffi", List.of("argon2")), Map.entry("lxml", List.of("lxml")), Map.entry("html5lib", List.of("html5lib")),
            Map.entry("jinja2", List.of("jinja")), Map.entry("aiofiles", List.of("aiofiles")),
            Map.entry("gevent", List.of("gevent")), Map.entry("eventlet", List.of("gevent")),
            Map.entry("whitenoise", List.of("whitenoise")), Map.entry("tabulate", List.of("tabulate")),
            Map.entry("sqlalchemy", List.of("sqlalchemy")));

    /** Packages a used package needs without the project importing them. */
    private static final Map<String, List<String>> PY_COMPANIONS = Map.ofEntries(
            Map.entry("uvicorn", List.of("fastapi", "starlette")),
            Map.entry("python-multipart", List.of("fastapi")),
            Map.entry("ipykernel", List.of("*notebook")), Map.entry("jupyter", List.of("*notebook")),
            Map.entry("notebook", List.of("*notebook")), Map.entry("jupyterlab", List.of("*notebook")),
            Map.entry("pytest", List.of("*tests")), Map.entry("channels-redis", List.of("channels")),
            Map.entry("daphne", List.of("channels")), Map.entry("tf-keras", List.of("transformers")),
            Map.entry("accelerate", List.of("transformers")), Map.entry("safetensors", List.of("transformers")),
            Map.entry("tokenizers", List.of("transformers")), Map.entry("torchvision", List.of("torch")),
            Map.entry("torchaudio", List.of("torch")));

    /** PEP 503 name: lower case, runs of {@code - _ .} become one {@code -}. */
    static String normalize(String dist) {
        return dist.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
    }

    /** Names Python code may import distribution {@code dist} by (best guess when the package is not installed yet). */
    static Set<String> pythonModuleNames(String dist) {
        String d = normalize(dist);
        Set<String> out = new LinkedHashSet<>(PY_MODULES.getOrDefault(d, List.of()));
        out.add(d);
        String[] parts = d.split("[-.]");
        // every mix of '.' and '_' between the parts: google-cloud-storage -> google.cloud.storage, google.cloud_storage
        if (parts.length <= 6) {
            int gaps = parts.length - 1;
            for (int mask = 0; mask < (1 << gaps); mask++) {
                StringBuilder sb = new StringBuilder(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    sb.append((mask & (1 << (i - 1))) != 0 ? '.' : '_').append(parts[i]);
                }
                out.add(sb.toString());
            }
        }
        out.add(d.replace("-", ""));
        for (String prefix : List.of("python-", "py-", "py")) {
            if (d.startsWith(prefix) && d.length() > prefix.length() + 1) {
                out.add(d.substring(prefix.length()).replace('-', '_'));
            }
        }
        for (String suffix : List.of("-python", "-py", "-binary", "-headless", "-client", "-sdk", "-api", "-cpu", "-gpu")) {
            if (d.endsWith(suffix) && d.length() > suffix.length() + 1) {
                out.add(d.substring(0, d.length() - suffix.length()).replace('-', '_'));
            }
        }
        if (d.startsWith("types-")) {
            out.add(d.substring(6).replace('-', '_')); // types-requests -> requests
        }
        if (d.endsWith("-stubs")) {
            out.add(d.substring(0, d.length() - 6).replace('-', '_'));
        }
        return out;
    }

    /**
     * Whether the Python code under {@code dir} uses distribution {@code dist}. {@code knownModules} are its real
     * top-level modules when it is installed already (from its {@code top_level.txt} / {@code RECORD}).
     */
    public boolean pyUsed(String dir, String dist, Collection<String> knownModules) {
        if (everythingUsed || !hasSources(py, dir)) {
            return true;
        }
        String d = normalize(dist);
        Set<String> imports = names(py, "py", dir);
        Set<String> flags = flags(py, "py", dir);
        Set<String> candidates = new LinkedHashSet<>(pythonModuleNames(d));
        knownModules.forEach(m -> candidates.add(m.toLowerCase(Locale.ROOT)));
        for (String c : candidates) {
            if (imports.contains(c)) {
                return true;
            }
        }
        for (String group : PY_IMPLICIT.getOrDefault(d, List.of())) {
            if (flags.contains("kw:" + group)) {
                return true;
            }
        }
        for (String other : PY_COMPANIONS.getOrDefault(d, List.of())) {
            if (other.startsWith("*") ? flags.contains(other.substring(1)) : pyUsed(dir, other, List.of())) {
                return true;
            }
        }
        // pytest plugins load themselves when pytest runs; type stubs come with what they type
        if (d.startsWith("pytest-") && (flags.contains("tests") || mentioned(dir, "pytest"))) {
            return true;
        }
        if ((d.startsWith("types-") || d.endsWith("-stubs")) && mentioned(dir, "mypy")) {
            return true;
        }
        if (d.startsWith("flake8-") && mentioned(dir, "flake8")) {
            return true;
        }
        // commands: gunicorn / uvicorn / celery in a Dockerfile or Procfile, [tool.black] in pyproject.toml ...
        Set<String> words = new LinkedHashSet<>();
        words.add(d);
        words.add(d.replace('-', '_'));
        for (String w : words) {
            if (mentioned(dir, w)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ Dart

    private static final Pattern DART_IMPORT = Pattern.compile("package:([a-z0-9_]+)/");

    static Set<String> dartImports(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = DART_IMPORT.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static final Map<String, List<String>> DART_KEYWORDS = Map.of(
            "codegen", List.of(".g.dart", ".freezed.dart", ".gr.dart", ".config.dart", ".mocks.dart", ".chopper.dart"),
            "freezed", List.of(".freezed.dart", "@freezed"),
            "json_serializable", List.of("@jsonserializable", ".g.dart"),
            "cupertino_icons", List.of("cupertinoicons."),
            "mockito_gen", List.of("@generatemocks", "@genmocks", ".mocks.dart"));

    private static String pubspecWithoutDependencies(String text) {
        Map<String, Object> y = PubSupport.yaml(text);
        StringBuilder sb = new StringBuilder();
        for (var e : y.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("dependencies") || k.equals("dependency_overrides") || k.equals("name")) {
                continue;
            }
            sb.append(k).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    /** Whether the Dart code under {@code dir} uses pub package {@code name}. */
    public boolean dartUsed(String dir, String name) {
        if (everythingUsed || !hasSources(dart, dir)) {
            return true;
        }
        String n = name.toLowerCase(Locale.ROOT);
        Set<String> flags = flags(dart, "dart", dir);
        if (names(dart, "dart", dir).contains(n) || flags.contains("kw:" + n)) {
            return true;
        }
        switch (n) {
            case "build_runner" -> {
                if (flags.contains("kw:codegen")) {
                    return true;
                }
            }
            case "mockito" -> {
                if (flags.contains("kw:mockito_gen")) {
                    return true;
                }
            }
            case "test", "flutter_test", "integration_test" -> {
                return true; // the test runner of the tests folder; tiny and needed to analyse the tests
            }
            default -> {
            }
        }
        return mentioned(dir, n);
    }

    // ------------------------------------------------------------------------------------------ queries

    private static boolean under(String dir, String fileDir) {
        return dir.isEmpty() || fileDir.equals(dir) || fileDir.startsWith(dir + "/");
    }

    private boolean hasSources(List<Source> list, String dir) {
        for (Source s : list) {
            if (under(dir, s.dir()) && !s.flags().contains("config") && !s.flags().contains("css")) {
                return true;
            }
        }
        return false;
    }

    private Set<String> names(List<Source> list, String lang, String dir) {
        return cache.computeIfAbsent(lang + ":n:" + dir, k -> {
            Set<String> out = new HashSet<>();
            for (Source s : list) {
                if (under(dir, s.dir())) {
                    out.addAll(s.names());
                }
            }
            return out;
        });
    }

    private Set<String> flags(List<Source> list, String lang, String dir) {
        return cache.computeIfAbsent(lang + ":f:" + dir, k -> {
            Set<String> out = new HashSet<>();
            for (Source s : list) {
                if (under(dir, s.dir())) {
                    out.addAll(s.flags());
                }
            }
            return out;
        });
    }

    private String texts(String dir) {
        return textCache.computeIfAbsent(dir, k -> {
            StringBuilder sb = new StringBuilder();
            synchronized (texts) {
                for (Map.Entry<String, String> e : texts) {
                    // a config file in the folder, below it, or in a folder above it (a monorepo root's CI / Dockerfile)
                    if (under(dir, e.getKey()) || under(e.getKey(), dir)) {
                        sb.append(e.getValue()).append('\n');
                    }
                }
            }
            return sb.toString();
        });
    }

    /** {@code word} appears as a whole word in the configuration / build files that apply to {@code dir}. */
    boolean mentioned(String dir, String word) {
        String t = texts(dir);
        String w = word.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int i = t.indexOf(w, from);
            if (i < 0) {
                return false;
            }
            int end = i + w.length();
            boolean startOk = i == 0 || !isWordChar(t.charAt(i - 1), true);
            boolean endOk = end >= t.length() || !isWordChar(t.charAt(end), false);
            if (startOk && endOk) {
                return true;
            }
            from = i + 1;
        }
    }

    private static boolean isWordChar(char c, boolean before) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || (before && (c == '@' || c == '.'));
    }

    /** True when the scan found no source code at all for the language (nothing can be judged). */
    public boolean hasJs(String dir) {
        return everythingUsed || hasSources(js, dir);
    }

    public boolean hasPython(String dir) {
        return everythingUsed || hasSources(py, dir);
    }

    public boolean hasDart(String dir) {
        return everythingUsed || hasSources(dart, dir);
    }
}
