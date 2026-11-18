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

    /** A declared requirement and where it came from; {@code raw} is shown in the UI. */
    record Declared(Requirement req, String raw, String scope, String unsupported) {
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
        String joined = text.replace("\r", "").replaceAll("\\\\\n", " ");
        for (String rawLine : joined.split("\n")) {
            String line = rawLine.replaceAll("(^|\\s)#.*$", "").trim();
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
            Pep440.requirement(l).ifPresent(r -> out.add(new Declared(r, l, scope,
                    r.url() != null ? "URL requirement: install it with pip" : null)));
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
        List<Item> items = new ArrayList<>();
        for (Declared d : declared(files, unit)) {
            Requirement r = d.req();
            if (!Pep440.marker(r.marker(), env, Set.of())) {
                continue; // not for this operating system / Python version
            }
            String have = installed.get(Pep440.normalizeName(r.name()));
            String required = r.specifier().isEmpty() ? null : r.specifier().toString();
            if ("local".equals(d.unsupported())) {
                items.add(new Item(r.name(), d.raw(), have, d.scope(), DependencyState.LOCAL, "local path"));
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
        String message = venv.exists() ? null : "No virtual environment yet: Setup Dependencies creates " + location;
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
        TargetPlatform platform = ctx.platform().withPython(venv.pythonVersion());
        Map<String, String> env = markerEnv(platform);
        Map<String, String> installed = venv.exists() ? installed(venv.sitePackages()) : new HashMap<>();
        Files.createDirectories(venv.sitePackages());
        if (!venv.exists()) {
            Files.writeString(venv.dir().resolve(".gitignore"), "# created by ORBIT (Setup Dependencies)\n*\n");
            ctx.progress().log("Created " + files.root().relativize(venv.dir()) + " for Python " + platform.pythonVersion()
                    + " on " + platform.pyPlatformSystem());
        }

        record Want(String name, Specifier spec, Set<String> extras, String from) {
        }
        Deque<Want> queue = new ArrayDeque<>();
        for (Declared d : declared(files, unit)) {
            if (d.unsupported() != null) {
                if (!"local".equals(d.unsupported()) && !installed.containsKey(Pep440.normalizeName(d.req().name()))) {
                    ctx.fail(d.req().name() + ": " + d.unsupported());
                }
                continue;
            }
            if (Pep440.marker(d.req().marker(), env, Set.of())) {
                queue.add(new Want(d.req().name(), d.req().specifier(), d.req().extras(), "project"));
            }
        }
        Map<String, Chosen> chosen = new LinkedHashMap<>();
        Map<String, JsonNode> projects = new HashMap<>();
        while (!queue.isEmpty()) {
            ctx.checkCancelled();
            Want w = queue.poll();
            String key = Pep440.normalizeName(w.name());
            Chosen have = chosen.get(key);
            Specifier spec = have == null ? w.spec() : have.spec().and(w.spec());
            Set<String> extras = new LinkedHashSet<>(w.extras());
            if (have != null) {
                extras.addAll(have.extras());
                if (spec.test(Pep440.parse(have.version()).orElseThrow(), true) && extras.equals(have.extras())) {
                    continue;
                }
            }
            ctx.progress().step("resolving " + w.name(), chosen.size(), chosen.size() + queue.size() + 1);
            Chosen c;
            List<String> requires;
            String local = installed.get(key);
            if (local != null && Pep440.parse(local).map(v -> spec.test(v, true)).orElse(false)
                    && (have == null || have.wheel() == null)) {
                c = new Chosen(w.name(), local, null, extras, spec);
                requires = installedRequires(venv.sitePackages(), key);
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
                if (pick.isEmpty()) {
                    ctx.fail(w.name() + (spec.isEmpty() ? "" : " " + spec) + ": no ready-made package (wheel) for "
                            + platform.pyPlatformSystem() + " / Python " + platform.pythonVersion()
                            + " - it has to be built from source with pip");
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
                queue.add(new Want(r.get().name(), r.get().specifier(), r.get().extras(), c.name()));
            }
        }

        List<Chosen> todo = chosen.values().stream().filter(c -> c.wheel() != null).toList();
        ctx.progress().log(todo.size() + " of " + chosen.size() + " Python packages to download");
        AtomicInteger done = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Chosen c : todo) {
            tasks.add(() -> {
                ctx.checkCancelled();
                try {
                    installWheel(venv, c, ctx);
                } catch (IOException e) {
                    ctx.fail(c.name() + " " + c.version() + ": " + e.getMessage());
                }
                ctx.progress().step(c.name() + " " + c.version(), done.incrementAndGet(), todo.size());
                return null;
            });
        }
        ctx.runAll(tasks);
    }

    private Optional<Chosen> pick(String name, Specifier spec, Set<String> extras, TargetPlatform platform,
                                  Map<String, JsonNode> cache, InstallContext ctx) throws IOException {
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
        List<Pep440.Version> candidates = new ArrayList<>(wheels.keySet());
        candidates.sort((a, b) -> b.compareTo(a));
        // like pip: pre-releases only when no final release fits
        for (boolean pre : new boolean[]{false, true}) {
            for (Pep440.Version v : candidates) {
                if (spec.test(v, pre)) {
                    return Optional.of(new Chosen(canonical == null ? name : canonical, text.get(v), wheels.get(v), extras, spec));
                }
            }
        }
        return Optional.empty();
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
