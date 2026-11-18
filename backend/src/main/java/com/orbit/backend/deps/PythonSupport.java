package com.orbit.backend.deps;

import com.orbit.backend.deps.DependencyReport.Item;
import com.orbit.backend.deps.version.Pep440;
import com.orbit.backend.deps.version.Pep440.Requirement;
import com.orbit.backend.deps.version.Pep440.Specifier;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Python projects. The Python manifests of one folder ({@code requirements*.txt}, {@code pyproject.toml} with PEP 621
 * or Poetry dependencies, {@code Pipfile}) form a unit. Packages live in the project's virtual environment
 * ({@code .venv}, {@code venv}, {@code env} next to the manifest or above it); without one, setup creates
 * {@code .venv} next to the manifest. Setup resolves the requirements against PyPI (with transitive dependencies and
 * environment markers) and installs ready-made wheels built for the user's operating system and Python version;
 * source-only packages are reported because building them needs Python itself.
 */
public final class PythonSupport implements EcosystemSupport {

    private static final List<String> VENV_NAMES = List.of(".venv", "venv", "env", ".env", "virtualenv");
    private static final int MAX_PACKAGES = 2_000;

    @Override
    public Ecosystem ecosystem() {
        return Ecosystem.PYTHON;
    }

    @Override
    public List<UnitSpec> units(ProjectFiles files) {
        Map<String, List<String>> byDir = new LinkedHashMap<>();
        for (String p : files.all()) {
            String n = ProjectFiles.fileName(p).toLowerCase(Locale.ROOT);
            boolean python = (n.startsWith("requirements") && n.endsWith(".txt")) || n.equals("pyproject.toml") || n.equals("pipfile");
            if (!python) {
                continue;
            }
            String dir = ProjectFiles.dirOf(p);
            // requirements/base.txt, requirements/dev.txt belong to the folder above
            if (ProjectFiles.fileName(dir).equalsIgnoreCase("requirements")) {
                dir = ProjectFiles.dirOf(dir);
            }
            byDir.computeIfAbsent(dir, k -> new ArrayList<>()).add(p);
        }
        List<UnitSpec> out = new ArrayList<>();
        byDir.forEach((dir, list) -> out.add(new UnitSpec(Ecosystem.PYTHON, dir, List.copyOf(list))));
        return out;
    }

    // ------------------------------------------------------------------------------------------ declared requirements

    /**
     * A declared requirement and where it came from; {@code raw} is shown in the UI. {@code source} / {@code line}
     * (0-based physical line) say which requirements*.txt line declares it, so setup can update that line; null / -1
     * when it cannot be edited safely (pyproject.toml, Pipfile, continued lines, hash-checked lines).
     */
    record Declared(Requirement req, String raw, String scope, String unsupported, String source, int line) {
        Declared(Requirement req, String raw, String scope, String unsupported) {
            this(req, raw, scope, unsupported, null, -1);
        }
    }

    List<Declared> declared(ProjectFiles files, UnitSpec unit) {
        Map<String, Declared> out = new LinkedHashMap<>();
        Set<String> selfNames = new HashSet<>();
        for (String manifest : unit.manifests()) {
            String n = ProjectFiles.fileName(manifest).toLowerCase(Locale.ROOT);
            List<Declared> list = new ArrayList<>();
            if (n.endsWith(".txt")) {
                requirementsFile(files, manifest, list, new HashSet<>(), n.contains("dev") || n.contains("test") ? "dev" : "runtime");
            } else if (n.equals("pyproject.toml")) {
                pyproject(files.read(manifest), list, selfNames);
            } else {
                pipfile(files.read(manifest), list);
            }
            for (Declared d : list) {
                String key = Pep440.normalizeName(d.req().name());
                Declared old = out.get(key);
                if (old == null) {
                    out.put(key, d);
                } else if (old.unsupported() == null && d.unsupported() == null) {
                    Set<String> extras = new LinkedHashSet<>(old.req().extras());
                    extras.addAll(d.req().extras());
                    Requirement merged = new Requirement(old.req().name(), extras, old.req().specifier().and(d.req().specifier()),
                            old.req().marker(), null);
                    out.put(key, new Declared(merged, old.raw().equals(d.raw()) ? old.raw() : old.raw() + "; " + d.raw(),
                            "runtime".equals(old.scope()) || "runtime".equals(d.scope()) ? "runtime" : old.scope(), null));
                }
            }
        }
        selfNames.forEach(s -> out.remove(Pep440.normalizeName(s)));
        return new ArrayList<>(out.values());
    }

    private static final Pattern EGG = Pattern.compile("#egg=([A-Za-z0-9._-]+)");

    private void requirementsFile(ProjectFiles files, String path, List<Declared> out, Set<String> seen, String scope) {
        if (!seen.add(path) || seen.size() > 50) {
            return;
        }
        String text = files.read(path);
        if (text == null) {
            return;
        }
        String[] physical = text.replace("\r", "").split("\n", -1);
        for (int i = 0; i < physical.length; i++) {
            int first = i;
            StringBuilder joined = new StringBuilder(physical[i]);
            while (joined.toString().endsWith("\\") && i + 1 < physical.length) {
                joined.setLength(joined.length() - 1);
                joined.append(' ').append(physical[++i]);
            }
            // only a requirement on one line of its own, without hashes, is rewritten by setup
            boolean editable = first == i && !physical[first].contains("--hash");
            String line = joined.toString().replaceAll("(^|\\s)#.*$", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher inc = Pattern.compile("^(?:-r|--requirement)(?:\\s+|=)(\\S+)").matcher(line);
            if (inc.find()) {
                String target = ProjectFiles.join(ProjectFiles.dirOf(path), inc.group(1).replace('\\', '/'));
                requirementsFile(files, java.nio.file.Path.of(target).normalize().toString().replace('\\', '/'), out, seen, scope);
                continue;
            }
            if (line.startsWith("-")) {
                continue; // -e ., -c constraints, --index-url, --hash lines ...
            }
            line = line.replaceAll("\\s--hash[= ]\\S+", "").trim();
            if (line.matches("^(\\.|/|[A-Za-z]:|git\\+|hg\\+|svn\\+|bzr\\+|https?://|file:).*")) {
                Matcher egg = EGG.matcher(line);
                String name = egg.find() ? egg.group(1) : line;
                boolean local = line.matches("^(\\.|/|[A-Za-z]:|file:).*");
                out.add(new Declared(new Requirement(name, Set.of(), Pep440.ANY, null, line), line, scope,
                        local ? "local" : "URL requirement: install it with pip"));
                continue;
            }
            String l = line;
            int lineNo = editable ? first : -1;
            Pep440.requirement(l).ifPresent(r -> out.add(new Declared(r, l, scope,
                    r.url() != null ? "URL requirement: install it with pip" : null, path, lineNo)));
        }
    }

    private void pyproject(String text, List<Declared> out, Set<String> selfNames) {
        JsonNode t = InstallContext.toml(text).orElse(null);
        if (t == null) {
            return;
        }
        String own = InstallContext.text(t.path("project"), "name");
        if (own != null) {
            selfNames.add(own);
        }
        String poetryName = InstallContext.text(t.path("tool").path("poetry"), "name");
        if (poetryName != null) {
            selfNames.add(poetryName);
        }
        JsonNode deps = t.path("project").path("dependencies");
        if (deps.isArray()) {
            for (JsonNode d : deps) {
                String line = d.asString("");
                Pep440.requirement(line).ifPresent(r -> out.add(new Declared(r, line, "runtime",
                        r.url() != null ? "URL requirement: install it with pip" : null)));
            }
        }
        JsonNode poetry = t.path("tool").path("poetry");
        poetryTable(poetry.path("dependencies"), "runtime", out);
        poetryTable(poetry.path("dev-dependencies"), "dev", out);
        JsonNode groups = poetry.path("group");
        if (groups.isObject()) {
            for (var g : groups.properties()) {
                if (!g.getValue().path("optional").asBoolean(false)) {
                    poetryTable(g.getValue().path("dependencies"), g.getKey().equals("main") ? "runtime" : "dev", out);
                }
            }
        }
    }

    private void poetryTable(JsonNode table, String scope, List<Declared> out) {
        if (!table.isObject()) {
            return;
        }
        for (var e : table.properties()) {
            String name = e.getKey();
            if (name.equalsIgnoreCase("python")) {
                continue;
            }
            JsonNode v = e.getValue();
            if (v.isArray() && !v.isEmpty()) {
                v = v.get(0); // multiple-constraint dependency: the first alternative
            }
            String constraint;
            Set<String> extras = new LinkedHashSet<>();
            String marker = null;
            if (v.isObject()) {
                if (v.path("optional").asBoolean(false)) {
                    continue;
                }
                if (v.has("path") || v.has("git") || v.has("url") || v.has("file")) {
                    boolean local = v.has("path") || v.has("file");
                    out.add(new Declared(new Requirement(name, Set.of(), Pep440.ANY, null, "local"), name, scope,
                            local ? "local" : "git / URL dependency: install it with pip or poetry"));
                    continue;
                }
                constraint = v.path("version").asString("*");
                v.path("extras").forEach(x -> extras.add(Pep440.normalizeName(x.asString(""))));
                marker = InstallContext.text(v, "markers");
            } else {
                constraint = v.asString("*");
            }
            Specifier spec = Pep440.trySpecifier(poetryToPep440(constraint)).orElse(Pep440.ANY);
            out.add(new Declared(new Requirement(name, Set.copyOf(extras), spec, marker, null), name + " " + constraint, scope, null));
        }
    }

    private void pipfile(String text, List<Declared> out) {
        JsonNode t = InstallContext.toml(text).orElse(null);
        if (t == null) {
            return;
        }
        for (String section : List.of("packages", "dev-packages")) {
            JsonNode table = t.path(section);
            if (!table.isObject()) {
                continue;
            }
            String scope = section.equals("packages") ? "runtime" : "dev";
            for (var e : table.properties()) {
                JsonNode v = e.getValue();
                String version = "*";
                String marker = null;
                Set<String> extras = new LinkedHashSet<>();
                if (v.isObject()) {
                    if (v.has("path") || v.has("git") || v.has("file") || v.has("editable")) {
                        boolean local = v.has("path") || v.has("editable");
                        out.add(new Declared(new Requirement(e.getKey(), Set.of(), Pep440.ANY, null, "local"), e.getKey(), scope,
                                local ? "local" : "git / URL dependency: install it with pipenv"));
                        continue;
                    }
                    version = v.path("version").asString("*");
                    marker = InstallContext.text(v, "markers");
                    v.path("extras").forEach(x -> extras.add(Pep440.normalizeName(x.asString(""))));
                } else {
                    version = v.asString("*");
                }
                Specifier spec = version.trim().equals("*") ? Pep440.ANY : Pep440.trySpecifier(version).orElse(Pep440.ANY);
                out.add(new Declared(new Requirement(e.getKey(), Set.copyOf(extras), spec, marker, null),
                        e.getKey() + (version.equals("*") ? "" : " " + version), scope, null));
            }
        }
    }

    /** Poetry's {@code ^1.2}, {@code ~1.2.3}, {@code 1.2.*}, {@code 1.2.3} to a PEP 440 specifier. */
    static String poetryToPep440(String constraint) {
        String c = constraint.trim();
        if (c.contains("||")) {
            c = c.substring(0, c.indexOf("||")).trim();
        }
        if (c.isEmpty() || c.equals("*")) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String piece : c.split("\\s*,\\s*|\\s+(?=[<>=!~^])")) {
            String p = piece.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("^") || (p.startsWith("~") && !p.startsWith("~="))) {
                boolean caret = p.startsWith("^");
                String v = p.substring(1).trim();
                String[] segs = v.split("\\.");
                long[] n = new long[segs.length];
                try {
                    for (int i = 0; i < segs.length; i++) {
                        n[i] = Long.parseLong(segs[i].replaceAll("\\D.*$", ""));
                    }
                } catch (NumberFormatException e) {
                    parts.add(">=" + v);
                    continue;
                }
                String upper;
                if (caret) {
                    int first = 0;
                    while (first < n.length - 1 && n[first] == 0) {
                        first++;
                    }
                    upper = bump(n, first);
                } else {
                    upper = bump(n, n.length == 1 ? 0 : 1);
                }
                parts.add(">=" + v);
                parts.add("<" + upper);
            } else if (p.matches("^\\d.*")) {
                parts.add("==" + p);
            } else {
                parts.add(p);
            }
        }
        return String.join(",", parts);
    }

    private static String bump(long[] n, int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= index; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(i == index ? n[i] + 1 : n[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------ environment

    /** The virtual environment a unit uses: an existing one, or where setup will create one. */
    record Venv(Path dir, Path sitePackages, boolean exists, String pythonVersion) {
    }

    Venv venv(ProjectFiles files, UnitSpec unit, TargetPlatform platform) {
        String cur = unit.key();
        while (true) {
            for (String n : VENV_NAMES) {
                Path dir = files.root().resolve(ProjectFiles.join(cur, n));
                Optional<Path> site = sitePackagesOf(dir);
                if (site.isPresent()) {
                    return new Venv(dir, site.get(), true, venvPython(dir).orElse(null));
                }
            }
            // a virtual environment with another name ("myenv", "python-env" ...): any folder with a pyvenv.cfg
            Path here = files.root().resolve(cur.isEmpty() ? "." : cur).normalize();
            try (Stream<Path> s = Files.list(here)) {
                Optional<Path> other = s.filter(p -> Files.isRegularFile(p.resolve("pyvenv.cfg")))
                        .filter(p -> sitePackagesOf(p).isPresent()).sorted().findFirst();
                if (other.isPresent()) {
                    return new Venv(other.get(), sitePackagesOf(other.get()).get(), true, venvPython(other.get()).orElse(null));
                }
            } catch (IOException ignored) {
                // not listable
            }
            if (cur.isEmpty()) {
                break;
            }
            cur = ProjectFiles.dirOf(cur);
        }
        Path dir = files.root().resolve(ProjectFiles.join(unit.key(), ".venv"));
        String py = pythonVersionHint(files, unit).orElse(platform.pythonVersion());
        int[] mm = platform.withPython(py).pythonMajorMinor();
        Path site = platform.os() == TargetPlatform.Os.WINDOWS ? dir.resolve("Lib").resolve("site-packages")
                : dir.resolve("lib").resolve("python" + mm[0] + "." + mm[1]).resolve("site-packages");
        return new Venv(dir, site, false, py);
    }

    static Optional<Path> sitePackagesOf(Path venv) {
        Path win = venv.resolve("Lib").resolve("site-packages");
        if (Files.isDirectory(win)) {
            return Optional.of(win);
        }
        Path lib = venv.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (Stream<Path> s = Files.list(lib)) {
                return s.filter(p -> p.getFileName().toString().startsWith("python"))
                        .map(p -> p.resolve("site-packages")).filter(Files::isDirectory).sorted().reduce((a, b) -> b);
            } catch (IOException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<String> venvPython(Path venv) {
        Path cfg = venv.resolve("pyvenv.cfg");
        try {
            if (Files.isRegularFile(cfg)) {
                for (String line : Files.readAllLines(cfg)) {
                    Matcher m = Pattern.compile("^\\s*(?:version|version_info)\\s*=\\s*(\\d+\\.\\d+)").matcher(line);
                    if (m.find()) {
                        return Optional.of(m.group(1));
                    }
                }
            }
            Path site = sitePackagesOf(venv).orElse(null);
            if (site != null && site.getParent() != null && site.getParent().getFileName().toString().startsWith("python")) {
                return Optional.of(site.getParent().getFileName().toString().substring(6));
            }
        } catch (IOException ignored) {
            // fall through
        }
        return Optional.empty();
    }

    /** The venv's own Python interpreter ({@code Scripts/python.exe} or {@code bin/python}), when it has one. */
    static Optional<Path> venvInterpreter(Path venv) {
        for (String p : List.of("Scripts/python.exe", "bin/python", "bin/python3")) {
            Path exe = venv.resolve(p);
            if (Files.exists(exe, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return Optional.of(exe);
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------------------------------ creating the venv

    private static TargetPlatform.Os currentOs() {
        String n = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return n.contains("win") ? TargetPlatform.Os.WINDOWS : n.contains("mac") ? TargetPlatform.Os.MAC : TargetPlatform.Os.LINUX;
    }

    /**
     * Creates the project's virtual environment so packages never go into the computer's global Python. With a Python
     * interpreter of the user's operating system at hand (ORBIT running directly on the computer) this is a real
     * {@code python -m venv}. Otherwise (ORBIT in Docker) ORBIT writes the same layout - {@code pyvenv.cfg},
     * {@code site-packages}, a {@code Scripts} / {@code bin} folder - and {@code python -m venv <folder>} later adds the
     * interpreter while keeping the installed packages.
     */
    private Venv createVenv(ProjectFiles files, UnitSpec unit, Venv planned, InstallContext ctx) throws IOException {
        String rel = files.root().relativize(planned.dir()).toString().replace('\\', '/');
        if (currentOs() == ctx.platform().os()) {
            for (List<String> python : interpreters(planned.pythonVersion(), ctx.platform().os())) {
                ctx.checkCancelled();
                Optional<String> version = runPython(python, List.of("-c", "import sys;print('%d.%d' % sys.version_info[:2])"),
                        files.root(), 20, ctx);
                if (version.isEmpty() || !version.get().trim().matches("\\d+\\.\\d+")) {
                    continue;
                }
                ctx.progress().step("Creating the virtual environment " + rel + " (Python " + version.get().trim() + ")", 0, 0);
                List<String> args = new ArrayList<>(List.of("-m", "venv", planned.dir().toString()));
                Optional<String> made = runPython(python, args, files.root(), 300, ctx);
                if (made.isEmpty()) {
                    args.add(2, "--without-pip"); // ensurepip missing (Debian's python3 without python3-venv)
                    made = runPython(python, args, files.root(), 120, ctx);
                }
                if (made.isPresent() && sitePackagesOf(planned.dir()).isPresent()) {
                    Path ignore = planned.dir().resolve(".gitignore"); // Python 3.13+ writes one, older ones do not
                    if (!Files.exists(ignore)) {
                        Files.writeString(ignore, "# created by ORBIT (Setup Dependencies)\n*\n");
                    }
                    ctx.progress().log("Created the virtual environment " + rel + " with " + String.join(" ", python)
                            + " (Python " + version.get().trim() + ")");
                    return new Venv(planned.dir(), sitePackagesOf(planned.dir()).get(), true, venvPython(planned.dir()).orElse(version.get().trim()));
                }
            }
        }
        // no interpreter reachable from ORBIT: the venv layout, completed by `python -m venv` on the computer
        Files.createDirectories(planned.sitePackages());
        Files.createDirectories(planned.dir().resolve(ctx.platform().os() == TargetPlatform.Os.WINDOWS ? "Scripts" : "bin"));
        int[] mm = ctx.platform().withPython(planned.pythonVersion()).pythonMajorMinor();
        Files.writeString(planned.dir().resolve("pyvenv.cfg"), "include-system-site-packages = false\n"
                + "version = " + mm[0] + "." + mm[1] + ".0\n"
                + "created-by = ORBIT Setup Dependencies (run: python -m venv " + rel + "  - adds the interpreter, keeps the packages)\n");
        Path ignore = planned.dir().resolve(".gitignore");
        if (!Files.exists(ignore)) {
            Files.writeString(ignore, "# created by ORBIT (Setup Dependencies)\n*\n");
        }
        ctx.progress().log("Created the virtual environment " + rel + " for Python " + mm[0] + "." + mm[1] + " on "
                + ctx.platform().pyPlatformSystem() + ". Python is not reachable from ORBIT, so the interpreter is not in it yet: run "
                + "\"python -m venv " + rel + "\" in the project folder once (the packages stay)");
        return new Venv(planned.dir(), planned.sitePackages(), true, mm[0] + "." + mm[1]);
    }

    private static List<List<String>> interpreters(String version, TargetPlatform.Os os) {
        List<List<String>> out = new ArrayList<>();
        if (os == TargetPlatform.Os.WINDOWS) {
            if (version != null) {
                out.add(List.of("py", "-" + version));
            }
            out.add(List.of("py", "-3"));
            out.add(List.of("python"));
            out.add(List.of("python3"));
        } else {
            if (version != null) {
                out.add(List.of("python" + version));
            }
            out.add(List.of("python3"));
            out.add(List.of("python"));
        }
        return out;
    }

    /** Runs Python; the output when it exits with 0 within {@code timeoutSeconds}. Stopped at once on Cancel. */
    private static Optional<String> runPython(List<String> python, List<String> args, Path cwd, int timeoutSeconds,
                                              InstallContext ctx) throws IOException {
        List<String> cmd = new ArrayList<>(python);
        cmd.addAll(args);
        Path out = Files.createTempFile("orbit-python", ".txt");
        Process p;
        try {
            p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        } catch (IOException e) {
            Files.deleteIfExists(out);
            return Optional.empty(); // not installed
        }
        Runnable kill = () -> {
            p.descendants().forEach(ProcessHandle::destroyForcibly); // py.exe starts the real python.exe
            p.destroyForcibly();
        };
        try (AutoCloseable stop = ctx.onCancel(kill)) {
            long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
            while (!p.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (ctx.cancelled() || System.nanoTime() > deadline) {
                    kill.run();
                    ctx.checkCancelled();
                    return Optional.empty();
                }
            }
            String text = Files.readString(out, StandardCharsets.UTF_8);
            return p.exitValue() == 0 ? Optional.of(text) : Optional.empty();
        } catch (InterruptedException e) {
            kill.run();
            Thread.currentThread().interrupt();
            throw new IOException("Setup cancelled");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            return Optional.empty();
        } finally {
            try {
                Files.deleteIfExists(out);
            } catch (IOException e) {
                out.toFile().deleteOnExit(); // still open in a process that is shutting down (Windows)
            }
        }
    }

    /** The top-level modules an installed distribution provides (its {@code top_level.txt}). */
    static List<String> topLevel(Path site, String key) {
        List<String> out = new ArrayList<>();
        try {
            for (Path dist : distInfos(site, key)) {
                Path t = dist.resolve("top_level.txt");
                if (Files.isRegularFile(t)) {
                    for (String l : Files.readAllLines(t, StandardCharsets.UTF_8)) {
                        if (!l.isBlank()) {
                            out.add(l.trim().replace('/', '.'));
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // none
        }
        return out;
    }

    private static Optional<String> pythonVersionHint(ProjectFiles files, UnitSpec unit) {
        String cur = unit.key();
        while (true) {
            String t = files.read(ProjectFiles.join(cur, ".python-version"));
            if (t != null) {
                Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(t);
                if (m.find()) {
                    return Optional.of(m.group(1));
                }
            }
            if (cur.isEmpty()) {
                return Optional.empty();
            }
            cur = ProjectFiles.dirOf(cur);
        }
    }

    /** normalised name -> installed version, from the {@code *.dist-info} / {@code *.egg-info} folders. */
    static Map<String, String> installed(Path sitePackages) {
        Map<String, String> out = new HashMap<>();
        if (!Files.isDirectory(sitePackages)) {
            return out;
        }
        try (Stream<Path> s = Files.list(sitePackages)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                String stem;
                if (n.endsWith(".dist-info")) {
                    stem = n.substring(0, n.length() - ".dist-info".length());
                } else if (n.endsWith(".egg-info")) {
                    stem = n.substring(0, n.length() - ".egg-info".length()).replaceAll("-py\\d.*$", "");
                } else {
                    return;
                }
                int dash = stem.indexOf('-');
                if (dash > 0) {
                    out.put(Pep440.normalizeName(stem.substring(0, dash)), stem.substring(dash + 1));
                }
            });
        } catch (IOException ignored) {
            // empty
        }
        return out;
    }

    static Map<String, String> markerEnv(TargetPlatform p) {
        int[] mm = p.pythonMajorMinor();
        Map<String, String> env = new HashMap<>();
        env.put("python_version", mm[0] + "." + mm[1]);
        env.put("python_full_version", mm[0] + "." + mm[1] + ".0");
        env.put("sys_platform", p.pySysPlatform());
        env.put("platform_system", p.pyPlatformSystem());
        env.put("os_name", p.os() == TargetPlatform.Os.WINDOWS ? "nt" : "posix");
        env.put("platform_machine", p.pyMachine());
        env.put("implementation_name", "cpython");
        env.put("platform_python_implementation", "CPython");
        env.put("implementation_version", mm[0] + "." + mm[1] + ".0");
        return env;
    }

    // ------------------------------------------------------------------------------------------ check

    @Override
    public DependencyReport.Unit check(ProjectFiles files, UnitSpec unit, InstallContext ctx) {
        Venv venv = venv(files, unit, ctx.platform());
        TargetPlatform platform = ctx.platform().withPython(venv.pythonVersion());
        Map<String, String> env = markerEnv(platform);
        Map<String, String> installed = venv.exists() ? installed(venv.sitePackages()) : Map.of();
        CodeUsage usage = ctx.usage(files.root());
        List<Item> items = new ArrayList<>();
        int unused = 0;
        for (Declared d : declared(files, unit)) {
            Requirement r = d.req();
            if (!Pep440.marker(r.marker(), env, Set.of())) {
                continue; // not for this operating system / Python version
            }
            String key = Pep440.normalizeName(r.name());
            String have = installed.get(key);
            String required = r.specifier().isEmpty() ? null : r.specifier().toString();
            boolean used = "local".equals(d.unsupported())
                    || usage.pyUsed(unit.key(), r.name(), have == null ? List.of() : topLevel(venv.sitePackages(), key));
            if ("local".equals(d.unsupported())) {
                items.add(new Item(r.name(), d.raw(), have, d.scope(), DependencyState.LOCAL, "local path"));
            } else if (!used && (have == null || !(r.specifier().isEmpty()
                    || Pep440.parse(have).map(v -> r.specifier().test(v, true)).orElse(true)))) {
                unused++;
                items.add(new Item(r.name(), required == null ? d.raw() : required, have, d.scope(), DependencyState.UNUSED,
                        "not imported anywhere in the code - not downloaded"));
            } else if (have == null) {
                items.add(new Item(r.name(), required == null ? d.raw() : required, null, d.scope(),
                        d.unsupported() != null ? DependencyState.UNSUPPORTED : DependencyState.MISSING, d.unsupported()));
            } else {
                boolean ok = r.specifier().isEmpty() || Pep440.parse(have).map(v -> r.specifier().test(v, true)).orElse(true);
                items.add(new Item(r.name(), required, have, d.scope(), ok ? DependencyState.INSTALLED : DependencyState.OUTDATED,
                        ok ? null : "installed " + have + " does not match " + required));
            }
        }
        String location = files.root().relativize(venv.dir()).toString().replace('\\', '/');
        List<String> notes = new ArrayList<>();
        if (!venv.exists()) {
            notes.add("No virtual environment yet: Setup Dependencies creates " + location
                    + " so the packages are installed for this project only, not into the computer's Python");
        } else if (venvInterpreter(venv.dir()).isEmpty()) {
            notes.add("Packages are in the virtual environment " + location + "; run \"python -m venv " + location
                    + "\" once to add the Python interpreter (the packages are kept)");
        }
        if (unused > 0) {
            notes.add(unused + (unused == 1 ? " requirement is" : " requirements are") + " not used by the code and not downloaded");
        }
        String message = notes.isEmpty() ? null : String.join(". ", notes);
        return DependencyReport.Unit.of(Ecosystem.PYTHON, String.join(", ", unit.manifests()), location, message, items, false);
    }

    // ------------------------------------------------------------------------------------------ install

    /** One release file on PyPI. */
    record Wheel(String filename, String url, String sha256, int score) {
    }

    record Chosen(String name, String version, Wheel wheel, Set<String> extras, Specifier spec) {
    }

    @Override
    public void install(ProjectFiles files, UnitSpec unit, InstallContext ctx) throws IOException {
        Venv venv = venv(files, unit, ctx.platform());
        if (!ctx.claim("python:" + venv.dir())) {
            return;
        }
        if (!venv.exists()) {
            venv = createVenv(files, unit, venv, ctx);
        } else {
            ctx.progress().log("Installing into the virtual environment " + files.root().relativize(venv.dir()).toString().replace('\\', '/')
                    + (venv.pythonVersion() == null ? "" : " (Python " + venv.pythonVersion() + ")"));
        }
        TargetPlatform platform = ctx.platform().withPython(venv.pythonVersion());
        Map<String, String> env = markerEnv(platform);
        Map<String, String> installed = installed(venv.sitePackages());
        Files.createDirectories(venv.sitePackages());
        final Venv target = venv;

        record Want(String name, Specifier spec, Set<String> extras, String from, Declared origin) {
        }
        List<RequirementUpdate> updates = new ArrayList<>();
        Deque<Want> queue = new ArrayDeque<>();
        ctx.progress().step("Resolving versions on PyPI", 0, 0);
        CodeUsage usage = ctx.skipUnused() ? ctx.usage(files.root()) : CodeUsage.everything();
        // declared but never used by the code: not downloaded, but their pins still apply if another package needs them
        Map<String, Declared> unusedPins = new HashMap<>();
        for (Declared d : declared(files, unit)) {
            String dk = Pep440.normalizeName(d.req().name());
            if (!"local".equals(d.unsupported()) && Pep440.marker(d.req().marker(), env, Set.of())
                    && !usage.pyUsed(unit.key(), d.req().name(), topLevel(venv.sitePackages(), dk))) {
                unusedPins.put(dk, d);
                continue;
            }
            if (d.unsupported() != null) {
                if (!"local".equals(d.unsupported()) && !installed.containsKey(Pep440.normalizeName(d.req().name()))) {
                    ctx.fail(d.req().name() + ": " + d.unsupported());
                }
                continue;
            }
            if (Pep440.marker(d.req().marker(), env, Set.of())) {
                queue.add(new Want(d.req().name(), d.req().specifier(), d.req().extras(), "project", d));
            }
        }
        if (!unusedPins.isEmpty()) {
            List<String> names = unusedPins.values().stream().map(d -> d.req().name()).sorted(String.CASE_INSENSITIVE_ORDER).toList();
            ctx.progress().log("Not used by the code, not downloaded (" + names.size() + "): " + String.join(", ", names));
        }
        Map<String, Chosen> chosen = new LinkedHashMap<>();
        Map<String, JsonNode> projects = new HashMap<>();
        while (!queue.isEmpty()) {
            ctx.checkCancelled();
            Want w = queue.poll();
            String key = Pep440.normalizeName(w.name());
            Chosen have = chosen.get(key);
            Declared pin = unusedPins.get(key);
            // needed by a used package after all: the project's pin of it applies
            Specifier base = pin != null && have == null ? w.spec().and(pin.req().specifier()) : w.spec();
            Specifier spec = have == null ? base : have.spec().and(base);
            Set<String> extras = new LinkedHashSet<>(w.extras());
            if (have != null) {
                extras.addAll(have.extras());
                if (spec.test(Pep440.parse(have.version()).orElseThrow(), true) && extras.equals(have.extras())) {
                    continue;
                }
            }
            ctx.progress().step("Resolving versions on PyPI: " + w.name(), 0, 0);
            Chosen c;
            List<String> requires;
            String local = installed.get(key);
            if (local != null && Pep440.parse(local).map(v -> spec.test(v, true)).orElse(false)
                    && (have == null || have.wheel() == null)) {
                c = new Chosen(w.name(), local, null, extras, spec);
                requires = installedRequires(target.sitePackages(), key);
            } else {
                Optional<Chosen> pick;
                try {
                    pick = pick(w.name(), spec, extras, platform, projects, ctx);
                } catch (Downloader.NotFoundException e) {
                    ctx.fail(w.name() + ": not found on PyPI");
                    continue;
                } catch (IOException e) {
                    ctx.fail(w.name() + ": " + e.getMessage());
                    continue;
                }
                String why = "no ready-made package (wheel) for " + platform.pyPlatformSystem() + " / Python "
                        + platform.pythonVersion();
                if (pick.isEmpty() && w.origin() != null && have == null) {
                    // the version the project asks for cannot be installed here: take a newer one that can
                    Optional<Chosen> newer;
                    try {
                        newer = pickNewer(w.name(), spec, extras, platform, projects, ctx);
                    } catch (IOException e) {
                        newer = Optional.empty();
                    }
                    if (newer.isPresent()) {
                        Declared o = w.origin();
                        boolean editable = o.source() != null && o.line() >= 0;
                        if (editable && ctx.updateManifests()) {
                            pick = newer;
                            updates.add(new RequirementUpdate(o.source(), o.line(), w.name(), spec.toString(), newer.get().version(), why));
                            ctx.progress().log("Cannot install " + w.name() + " " + spec + " (" + why + ") - using "
                                    + newer.get().version() + " instead");
                        } else {
                            ctx.fail(w.name() + " " + spec + ": " + why + " - version " + newer.get().version() + " works: "
                                    + (editable ? "allow ORBIT to update requirements files, or change it yourself"
                                    : "change the version in " + (o.source() == null ? "the manifest" : o.source())));
                            continue;
                        }
                    }
                }
                if (pick.isEmpty()) {
                    ctx.fail(w.name() + (spec.isEmpty() ? "" : " " + spec) + ": " + why
                            + " and no newer version has one - it has to be built from source with pip");
                    continue;
                }
                c = pick.get();
                try {
                    requires = requiresDist(c.name(), c.version(), ctx);
                } catch (IOException e) {
                    ctx.fail(c.name() + " " + c.version() + ": " + e.getMessage());
                    continue;
                }
            }
            chosen.put(key, c);
            if (chosen.size() > MAX_PACKAGES) {
                throw new IOException("More than " + MAX_PACKAGES + " Python packages - stopped");
            }
            for (String line : requires) {
                Optional<Requirement> r = Pep440.requirement(line);
                if (r.isEmpty() || r.get().url() != null || !Pep440.marker(r.get().marker(), env, extras)) {
                    continue;
                }
                queue.add(new Want(r.get().name(), r.get().specifier(), r.get().extras(), c.name(), null));
            }
        }

        List<Chosen> todo = chosen.values().stream().filter(c -> c.wheel() != null).toList();
        java.util.Set<String> failedInstall = java.util.concurrent.ConcurrentHashMap.newKeySet();
        ctx.progress().log(todo.size() + " of " + chosen.size() + " Python packages to download");
        AtomicInteger done = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Chosen c : todo) {
            tasks.add(() -> {
                ctx.checkCancelled();
                try {
                    installWheel(target, c, ctx);
                    ctx.installed(c.name() + " " + c.version(), done.incrementAndGet(), todo.size());
                } catch (IOException e) {
                    failedInstall.add(Pep440.normalizeName(c.name()));
                    ctx.fail(c.name() + " " + c.version() + ": " + e.getMessage());
                    ctx.progress().step(c.name() + " " + c.version(), done.incrementAndGet(), todo.size());
                }
                return null;
            });
        }
        ctx.runAll(tasks);
        // the newer versions that were really installed are written into the requirements files
        List<RequirementUpdate> applied = updates.stream()
                .filter(u -> !failedInstall.contains(Pep440.normalizeName(u.name()))).toList();
        for (RequirementUpdate u : applied) {
            try {
                updateRequirementLine(files.root(), u);
                ctx.manifestChanged(u.file(), u.name(), u.from(), "==" + u.to(), u.reason());
            } catch (IOException e) {
                ctx.fail(u.name() + ": installed " + u.to() + " but " + u.file() + " could not be updated: " + e.getMessage());
            }
        }
    }

    /** A requirement whose pinned version could not be installed and was replaced by a newer one. */
    record RequirementUpdate(String file, int line, String name, String from, String to, String reason) {
    }

    /** Backup suffix of a requirements file ORBIT changed (written once, before the first change). */
    public static final String BACKUP_SUFFIX = ".orbit-backup";

    /** Rewrites one line of a requirements file to {@code name==version}, keeping extras, markers and comments. */
    static void updateRequirementLine(Path projectRoot, RequirementUpdate u) throws IOException {
        Path file = projectRoot.resolve(u.file()).normalize();
        if (!file.startsWith(projectRoot)) {
            throw new IOException("not inside the project");
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        boolean bom = !text.isEmpty() && text.charAt(0) == '\uFEFF';
        String body = bom ? text.substring(1) : text;
        String nl = body.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = body.split("\r?\n", -1);
        if (u.line() < 0 || u.line() >= lines.length) {
            throw new IOException("line " + (u.line() + 1) + " no longer exists");
        }
        String updated = rewriteRequirement(lines[u.line()], u.name(), u.to(), u.from(), u.reason());
        if (updated == null) {
            throw new IOException("line " + (u.line() + 1) + " no longer declares " + u.name());
        }
        Path backup = file.resolveSibling(file.getFileName() + BACKUP_SUFFIX);
        if (!Files.exists(backup)) {
            Files.copy(file, backup);
        }
        lines[u.line()] = updated;
        Files.writeString(file, (bom ? "\uFEFF" : "") + String.join(nl, lines), StandardCharsets.UTF_8);
    }

    private static final Pattern REQUIREMENT_LINE = Pattern.compile(
            "^(\\s*)([A-Za-z0-9][A-Za-z0-9._-]*)(\\s*\\[[^\\]]*\\])?\\s*([^;#]*?)\\s*(;[^#]*?)?\\s*(#.*)?$");

    /**
     * {@code numpy==1.21.0 ; python_version < "3.13"  # math} becomes
     * {@code numpy==1.26.4 ; python_version < "3.13"  # math (updated by ORBIT: was ==1.21.0, ...)}; null when the line
     * does not declare {@code name}.
     */
    static String rewriteRequirement(String line, String name, String version, String from, String reason) {
        Matcher m = REQUIREMENT_LINE.matcher(line);
        if (!m.matches() || !Pep440.normalizeName(m.group(2)).equals(Pep440.normalizeName(name))) {
            return null;
        }
        String note = "updated by ORBIT: was " + (from == null || from.isBlank() ? "unpinned" : from) + " - " + reason;
        String comment = m.group(6) == null ? "# " + note : m.group(6).trim() + " (" + note + ")";
        return m.group(1) + m.group(2) + (m.group(3) == null ? "" : m.group(3).trim()) + "==" + version
                + (m.group(5) == null ? "" : " " + m.group(5).trim()) + "  " + comment;
    }

    /** The installable releases of a package for the target: version -> best wheel. */
    private record Available(String canonical, Map<Pep440.Version, Wheel> wheels, Map<Pep440.Version, String> text) {
    }

    private Optional<Chosen> pick(String name, Specifier spec, Set<String> extras, TargetPlatform platform,
                                  Map<String, JsonNode> cache, InstallContext ctx) throws IOException {
        Available a = available(name, platform, cache, ctx);
        List<Pep440.Version> candidates = new ArrayList<>(a.wheels().keySet());
        candidates.sort((x, y) -> y.compareTo(x));
        // like pip: pre-releases only when no final release fits
        for (boolean pre : new boolean[]{false, true}) {
            for (Pep440.Version v : candidates) {
                if (spec.test(v, pre)) {
                    return Optional.of(new Chosen(a.canonical() == null ? name : a.canonical(), a.text().get(v), a.wheels().get(v), extras, spec));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A newer release that CAN be installed when the requested one cannot: the newest final release above the
     * requested version with the same major version (fewest breaking changes), otherwise the newest final release.
     */
    private Optional<Chosen> pickNewer(String name, Specifier spec, Set<String> extras, TargetPlatform platform,
                                       Map<String, JsonNode> cache, InstallContext ctx) throws IOException {
        Available a = available(name, platform, cache, ctx);
        Optional<Pep440.Version> v = newerVersion(a.wheels().keySet(), spec.lowerBound().orElse(null));
        return v.map(x -> new Chosen(a.canonical() == null ? name : a.canonical(), a.text().get(x), a.wheels().get(x), extras,
                Pep440.specifier("==" + a.text().get(x))));
    }

    static Optional<Pep440.Version> newerVersion(java.util.Collection<Pep440.Version> installable, Pep440.Version requested) {
        Pep440.Version sameMajor = null;
        Pep440.Version newest = null;
        for (Pep440.Version v : installable) {
            if (v.isPrerelease() || (requested != null && v.compareTo(requested) <= 0)) {
                continue;
            }
            if (newest == null || v.compareTo(newest) > 0) {
                newest = v;
            }
            if (requested != null && v.epoch() == requested.epoch() && v.release().get(0).equals(requested.release().get(0))
                    && (sameMajor == null || v.compareTo(sameMajor) > 0)) {
                sameMajor = v;
            }
        }
        return Optional.ofNullable(sameMajor != null ? sameMajor : newest);
    }

    private Available available(String name, TargetPlatform platform, Map<String, JsonNode> cache, InstallContext ctx)
            throws IOException {
        String key = Pep440.normalizeName(name);
        JsonNode project = cache.get(key);
        if (project == null) {
            // PEP 691 "simple" JSON index: only the file list, much smaller than the full /pypi/<name>/json document
            String text = ctx.downloader().text(ctx.settings().trimmed(ctx.settings().pypiUrl()) + "/simple/" + key + "/",
                    "application/vnd.pypi.simple.v1+json");
            project = InstallContext.json(text).orElseThrow(() -> new IOException("invalid PyPI answer"));
            cache.put(key, project);
        }
        String canonical = InstallContext.text(project, "name");
        int[] mm = platform.pythonMajorMinor();
        Pep440.Version python = Pep440.parse(mm[0] + "." + mm[1] + ".0").orElseThrow();
        // version -> best wheel of that version for the target
        Map<Pep440.Version, Wheel> wheels = new HashMap<>();
        Map<Pep440.Version, String> text = new HashMap<>();
        for (JsonNode f : project.path("files")) {
            String fn = InstallContext.text(f, "filename");
            JsonNode yanked = f.path("yanked");
            if (fn == null || !fn.endsWith(".whl") || (yanked.isBoolean() ? yanked.asBoolean() : yanked.isString())) {
                continue;
            }
            String rp = InstallContext.text(f, "requires-python");
            if (rp != null && !rp.isBlank() && !Pep440.trySpecifier(rp).map(s -> s.test(python, true)).orElse(true)) {
                continue;
            }
            String[] parts = fn.split("-");
            Optional<Pep440.Version> v = parts.length >= 5 ? Pep440.parse(parts[1]) : Optional.empty();
            int score = wheelScore(fn, platform);
            if (v.isEmpty() || score <= 0) {
                continue;
            }
            Wheel w = new Wheel(fn, InstallContext.text(f, "url"), InstallContext.text(f.path("hashes"), "sha256"), score);
            Wheel old = wheels.get(v.get());
            if (old == null || score > old.score()) {
                wheels.put(v.get(), w);
                text.put(v.get(), parts[1]);
            }
        }
        return new Available(canonical, wheels, text);
    }

    private static List<String> requiresDist(String name, String version, InstallContext ctx) throws IOException {
        String text = ctx.downloader().text(ctx.settings().trimmed(ctx.settings().pypiUrl()) + "/pypi/"
                + Pep440.normalizeName(name) + "/" + version + "/json", "application/json");
        JsonNode rd = InstallContext.json(text).orElseThrow(() -> new IOException("invalid PyPI answer"))
                .path("info").path("requires_dist");
        List<String> out = new ArrayList<>();
        if (rd.isArray()) {
            rd.forEach(x -> out.add(x.asString("")));
        }
        return out;
    }

    /** Requires-Dist of an installed package (its METADATA), so an installed package's dependencies are checked too. */
    private static List<String> installedRequires(Path site, String key) {
        try (Stream<Path> s = Files.list(site)) {
            Optional<Path> dist = s.filter(p -> {
                String n = p.getFileName().toString();
                int dash = n.indexOf('-');
                return n.endsWith(".dist-info") && dash > 0 && Pep440.normalizeName(n.substring(0, dash)).equals(key);
            }).findFirst();
            if (dist.isEmpty() || !Files.isRegularFile(dist.get().resolve("METADATA"))) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(dist.get().resolve("METADATA"), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    break;
                }
                if (line.startsWith("Requires-Dist:")) {
                    out.add(line.substring("Requires-Dist:".length()).trim());
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * How well a wheel fits the target (0 = not installable): pure-Python wheels fit everywhere, binary wheels must
     * match the Python version (or the stable ABI) and the user's operating system and processor.
     */
    static int wheelScore(String filename, TargetPlatform p) {
        if (filename == null || !filename.endsWith(".whl")) {
            return 0;
        }
        String[] parts = filename.substring(0, filename.length() - 4).split("-");
        if (parts.length < 5) {
            return 0;
        }
        String[] pys = parts[parts.length - 3].toLowerCase(Locale.ROOT).split("\\.");
        String[] abis = parts[parts.length - 2].toLowerCase(Locale.ROOT).split("\\.");
        String[] plats = parts[parts.length - 1].toLowerCase(Locale.ROOT).split("\\.");
        int[] mm = p.pythonMajorMinor();
        int best = 0;
        for (String py : pys) {
            for (String abi : abis) {
                for (String plat : plats) {
                    int pyScore = pyTag(py, abi, mm);
                    int platScore = platTag(plat, p);
                    if (pyScore > 0 && platScore > 0) {
                        best = Math.max(best, pyScore * 10 + platScore);
                    }
                }
            }
        }
        return best;
    }

    private static int pyTag(String py, String abi, int[] mm) {
        String cp = "cp" + mm[0] + mm[1];
        if (abi.equals("none")) {
            if (py.equals("py" + mm[0]) || py.equals("py" + mm[0] + mm[1])) {
                return 1;
            }
            if (py.matches("py" + mm[0] + "\\d+")) {
                return Integer.parseInt(py.substring(3)) <= mm[1] ? 1 : 0;
            }
            return py.equals(cp) ? 2 : 0;
        }
        if (abi.equals("abi3")) {
            if (py.matches("cp" + mm[0] + "\\d+")) {
                return Integer.parseInt(py.substring(3)) <= mm[1] ? 3 : 0;
            }
            return 0;
        }
        return py.equals(cp) && (abi.equals(cp) || abi.equals(cp + "m")) ? 4 : 0;
    }

    private static final Pattern MANYLINUX = Pattern.compile("^manylinux(?:1|2010|2014|_(\\d+)_(\\d+))_(x86_64|aarch64)$");
    private static final Pattern MACOS = Pattern.compile("^macosx_(\\d+)_(\\d+)_(\\w+)$");

    private static int platTag(String plat, TargetPlatform p) {
        if (plat.equals("any")) {
            return 1;
        }
        boolean arm = p.arch() == TargetPlatform.Arch.ARM64;
        switch (p.os()) {
            case WINDOWS:
                return plat.equals(arm ? "win_arm64" : "win_amd64") ? 2 : 0;
            case MAC: {
                Matcher m = MACOS.matcher(plat);
                if (!m.matches()) {
                    return 0;
                }
                String a = m.group(3);
                return a.equals("universal2") || a.equals(arm ? "arm64" : "x86_64") || (!arm && a.equals("intel")) ? 2 : 0;
            }
            default: {
                Matcher m = MANYLINUX.matcher(plat);
                if (!m.matches()) {
                    return 0;
                }
                return m.group(3).equals(arm ? "aarch64" : "x86_64") ? 2 : 0;
            }
        }
    }

    private void installWheel(Venv venv, Chosen c, InstallContext ctx) throws IOException {
        Path whl = Files.createTempFile("orbit-wheel", ".whl");
        try {
            ctx.downloader().file(c.wheel().url(), null, whl);
            Downloader.verifySha256(whl, c.wheel().sha256(), c.wheel().filename());
            String key = Pep440.normalizeName(c.name());
            removeInstalled(venv.dir(), venv.sitePackages(), key);
            Path site = venv.sitePackages();
            Path scripts = venv.dir().resolve(ctx.platform().os() == TargetPlatform.Os.WINDOWS ? "Scripts" : "bin");
            Archives.unzip(whl, venv.dir(), name -> {
                Matcher m = Pattern.compile("^[^/]+\\.data/(purelib|platlib|scripts|headers|data)/(.+)$").matcher(name);
                Path rel;
                if (m.matches()) {
                    rel = switch (m.group(1)) {
                        case "purelib", "platlib" -> site.resolve(m.group(2));
                        case "scripts" -> scripts.resolve(m.group(2));
                        case "headers" -> venv.dir().resolve("include").resolve(m.group(2));
                        default -> venv.dir().resolve(m.group(2));
                    };
                } else {
                    rel = site.resolve(name);
                }
                return venv.dir().relativize(rel.normalize()).toString().replace('\\', '/');
            });
            for (Path dist : distInfos(site, key)) {
                Files.writeString(dist.resolve("INSTALLER"), "orbit\n");
            }
        } finally {
            Files.deleteIfExists(whl);
        }
    }

    /** Uninstalls an older version: the files its RECORD lists, then its dist-info folder. */
    private static void removeInstalled(Path venvDir, Path site, String key) throws IOException {
        Path venv = venvDir.toAbsolutePath().normalize();
        for (Path dist : distInfos(site, key)) {
            Path record = dist.resolve("RECORD");
            if (Files.isRegularFile(record)) {
                for (String line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
                    String file = line.split(",", 2)[0].trim().replace("\"", "");
                    if (file.isEmpty()) {
                        continue;
                    }
                    Path p = site.resolve(file).toAbsolutePath().normalize();
                    if (p.startsWith(venv) && Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.deleteIfExists(p); // never outside the virtual environment
                    }
                }
            }
            Archives.deleteTree(dist);
        }
    }

    /** The {@code *.dist-info} folders of one package. */
    private static List<Path> distInfos(Path site, String key) throws IOException {
        if (!Files.isDirectory(site)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(site)) {
            return s.filter(p -> {
                String n = p.getFileName().toString();
                int dash = n.indexOf('-');
                return n.endsWith(".dist-info") && dash > 0 && Pep440.normalizeName(n.substring(0, dash)).equals(key);
            }).toList();
        }
    }
}
