package com.orbit.backend.deps;

import com.orbit.backend.deps.DependencyReport.Item;
import com.orbit.backend.deps.version.Semver;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Node.js projects. Every {@code package.json} is a unit; packages of an npm/Yarn/pnpm workspace install into the
 * workspace root. Setup writes {@code node_modules} exactly where npm would: from {@code package-lock.json} when
 * there is one (same versions, integrity checked), otherwise by resolving the semver ranges against the npm registry
 * and hoisting like npm. Platform-specific optional packages are chosen for the user's computer. Install scripts
 * ({@code postinstall}, ...) are never run.
 */
public final class NpmSupport implements EcosystemSupport {

    private static final int MAX_PACKAGES = 25_000;
    private static final Pattern GIT_LIKE = Pattern.compile(
            "^(git\\+|git:|github:|gitlab:|bitbucket:|gist:|https?://|[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(#.*)?$)");

    @Override
    public Ecosystem ecosystem() {
        return Ecosystem.NPM;
    }

    // ------------------------------------------------------------------------------------------ discovery

    @Override
    public List<UnitSpec> units(ProjectFiles files) {
        List<UnitSpec> out = new ArrayList<>();
        for (String p : files.named("package.json")) {
            out.add(new UnitSpec(Ecosystem.NPM, ProjectFiles.dirOf(p), List.of(p)));
        }
        return out;
    }

    private record Manifest(String dir, JsonNode json) {
        String name() {
            return InstallContext.text(json, "name");
        }

        List<String> workspaces() {
            JsonNode w = json.get("workspaces");
            if (w != null && w.isObject()) {
                w = w.get("packages");
            }
            List<String> out = new ArrayList<>();
            if (w != null && w.isArray()) {
                w.forEach(x -> {
                    if (x.isString()) {
                        out.add(x.asString());
                    }
                });
            }
            return out;
        }
    }

    private static Map<String, Manifest> manifests(ProjectFiles files) {
        Map<String, Manifest> out = new LinkedHashMap<>();
        for (String p : files.named("package.json")) {
            InstallContext.json(files.read(p)).filter(JsonNode::isObject)
                    .ifPresent(j -> out.put(ProjectFiles.dirOf(p), new Manifest(ProjectFiles.dirOf(p), j)));
        }
        return out;
    }

    /** The folder whose node_modules serves {@code dir}: the workspace root that lists it, or the folder itself. */
    static String installRoot(Map<String, Manifest> all, String dir) {
        String best = dir;
        String cur = dir;
        while (!cur.isEmpty()) {
            cur = ProjectFiles.dirOf(cur);
            Manifest m = all.get(cur);
            if (m != null && matchesWorkspace(m, dir)) {
                best = cur;
            }
        }
        return best;
    }

    private static boolean matchesWorkspace(Manifest root, String dir) {
        String rel = root.dir().isEmpty() ? dir : dir.substring(root.dir().length() + 1);
        for (String pattern : root.workspaces()) {
            String p = pattern.replace('\\', '/');
            while (p.startsWith("./")) {
                p = p.substring(2);
            }
            if (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            String regex = Pattern.quote(p).replace("**", "\\E.*\\Q").replace("*", "\\E[^/]*\\Q");
            if (rel.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ check

    /** How a dependency is declared. */
    enum Kind { REGISTRY, TAG, ALIAS, LOCAL, REMOTE }

    record Spec(Kind kind, String target, String range) {
    }

    static Spec spec(String raw) {
        String s = raw == null ? "" : raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file:") || lower.startsWith("link:") || lower.startsWith("workspace:")
                || lower.startsWith("portal:") || s.startsWith(".") || s.startsWith("/") || s.startsWith("~/")) {
            return new Spec(Kind.LOCAL, null, null);
        }
        if (lower.startsWith("npm:")) {
            String rest = s.substring(4);
            int at = rest.lastIndexOf('@');
            if (at > 0) {
                return new Spec(Kind.ALIAS, rest.substring(0, at), rest.substring(at + 1));
            }
            return new Spec(Kind.ALIAS, rest, "*");
        }
        if (Semver.tryRange(s).isPresent()) {
            return new Spec(Kind.REGISTRY, null, s);
        }
        if (GIT_LIKE.matcher(s).find()) {
            return new Spec(Kind.REMOTE, null, null);
        }
        if (s.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            return new Spec(Kind.TAG, null, s);
        }
        return new Spec(Kind.REMOTE, null, null);
    }

    @Override
    public DependencyReport.Unit check(ProjectFiles files, UnitSpec unit, InstallContext ctx) {
        Map<String, Manifest> all = manifests(files);
        Manifest m = all.get(unit.key());
        String manifest = unit.manifests().get(0);
        if (m == null) {
            return DependencyReport.Unit.of(Ecosystem.NPM, manifest, null, "package.json could not be read", List.of(), false);
        }
        String root = installRoot(all, unit.key());
        Set<String> localNames = new LinkedHashSet<>();
        all.values().forEach(x -> {
            if (x.name() != null) {
                localNames.add(x.name());
            }
        });
        boolean workspaceRoot = !all.get(root).workspaces().isEmpty();
        List<Item> items = new ArrayList<>();
        for (String section : List.of("dependencies", "devDependencies")) {
            JsonNode deps = m.json().get(section);
            if (deps == null || !deps.isObject()) {
                continue;
            }
            String scope = section.equals("dependencies") ? "runtime" : "dev";
            for (var e : deps.properties()) {
                String name = e.getKey();
                String raw = e.getValue().isString() ? e.getValue().asString() : "";
                items.add(checkOne(files.root(), unit.key(), name, raw, scope,
                        workspaceRoot && localNames.contains(name)));
            }
        }
        String message = null;
        boolean extra = false;
        if (root.equals(unit.key())) {
            LockStats stats = lockStats(files.root(), root, ctx.platform());
            if (stats != null && stats.missing() > 0) {
                extra = true;
                message = stats.missing() + " of " + stats.total() + " packages listed in package-lock.json are not installed";
            }
        } else {
            message = "Installed with the workspace root " + (root.isEmpty() ? "package.json" : root + "/package.json");
        }
        return DependencyReport.Unit.of(Ecosystem.NPM, manifest, ProjectFiles.join(root, "node_modules"), message, items, extra);
    }

    private static Item checkOne(Path projectRoot, String dir, String name, String raw, String scope, boolean workspacePackage) {
        Spec spec = spec(raw);
        if (spec.kind() == Kind.LOCAL || workspacePackage) {
            return new Item(name, raw, null, scope, DependencyState.LOCAL, workspacePackage ? "workspace package" : "local path");
        }
        Optional<String> installed = findInstalled(projectRoot, dir, name);
        if (installed.isEmpty()) {
            return new Item(name, raw, null, scope,
                    spec.kind() == Kind.REMOTE ? DependencyState.UNSUPPORTED : DependencyState.MISSING,
                    spec.kind() == Kind.REMOTE ? "git / URL dependency: install it with npm install" : null);
        }
        String range = spec.kind() == Kind.REGISTRY || spec.kind() == Kind.ALIAS ? spec.range() : null;
        boolean ok = range == null || Semver.tryRange(range).map(r -> r.test(installed.get())).orElse(true)
                || Semver.parse(installed.get()).isEmpty();
        return new Item(name, raw, installed.get(), scope, ok ? DependencyState.INSTALLED : DependencyState.OUTDATED,
                ok ? null : "installed version does not match " + raw);
    }

    /** Node's lookup: {@code <dir>/node_modules/<name>}, then the same in every parent folder up to the project. */
    static Optional<String> findInstalled(Path projectRoot, String dir, String name) {
        String cur = dir;
        while (true) {
            Path pkg = projectRoot.resolve(ProjectFiles.join(ProjectFiles.join(cur, "node_modules"), name)).resolve("package.json");
            if (Files.isRegularFile(pkg)) {
                try {
                    String v = InstallContext.text(InstallContext.json(Files.readString(pkg)).orElse(null), "version");
                    return Optional.of(v == null ? "" : v);
                } catch (IOException e) {
                    return Optional.of("");
                }
            }
            if (cur.isEmpty()) {
                return Optional.empty();
            }
            cur = ProjectFiles.dirOf(cur);
        }
    }

    // ------------------------------------------------------------------------------------------ lockfile

    /** One package of a lockfile: where it goes (relative to the install root) and what to download. */
    record LockEntry(String path, String name, String version, String resolved, String integrity) {
    }

    record LockStats(int total, int missing) {
    }

    private static Path lockFile(Path projectRoot, String root) {
        for (String n : List.of("npm-shrinkwrap.json", "package-lock.json")) {
            Path p = projectRoot.resolve(ProjectFiles.join(root, n));
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    static LockStats lockStats(Path projectRoot, String root, TargetPlatform platform) {
        Optional<List<LockEntry>> entries = lockEntries(projectRoot, root, platform);
        if (entries.isEmpty()) {
            return null;
        }
        int missing = 0;
        Path base = projectRoot.resolve(root);
        for (LockEntry e : entries.get()) {
            if (!installedVersionIs(base.resolve(e.path()), e.version())) {
                missing++;
            }
        }
        return new LockStats(entries.get().size(), missing);
    }

    private static boolean installedVersionIs(Path dir, String version) {
        Path pkg = dir.resolve("package.json");
        if (!Files.isRegularFile(pkg)) {
            return false;
        }
        if (version == null) {
            return true;
        }
        try {
            return version.equals(InstallContext.text(InstallContext.json(Files.readString(pkg)).orElse(null), "version"));
        } catch (IOException e) {
            return false;
        }
    }

    /** The packages of package-lock.json (v1, v2 and v3) that belong on this platform; empty without a lockfile. */
    static Optional<List<LockEntry>> lockEntries(Path projectRoot, String root, TargetPlatform platform) {
        Path lock = lockFile(projectRoot, root);
        if (lock == null) {
            return Optional.empty();
        }
        JsonNode json;
        try {
            json = InstallContext.json(Files.readString(lock)).orElse(null);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (json == null) {
            return Optional.empty();
        }
        List<LockEntry> out = new ArrayList<>();
        JsonNode packages = json.get("packages");
        if (packages != null && packages.isObject()) {
            Set<String> needed = reachable(packages, platform);
            for (var e : packages.properties()) {
                String path = e.getKey();
                JsonNode p = e.getValue();
                if (path.isEmpty() || !path.contains("node_modules/") || p.path("link").asBoolean(false)
                        || p.path("inBundle").asBoolean(false) || p.path("extraneous").asBoolean(false)) {
                    continue;
                }
                if (!platformMatches(p, platform) || (needed != null && !needed.contains(path))) {
                    continue; // for another platform, or only needed by such a package (npm skips both)
                }
                String name = InstallContext.text(p, "name");
                if (name == null) {
                    name = path.substring(path.lastIndexOf("node_modules/") + "node_modules/".length());
                }
                out.add(new LockEntry(path, name, InstallContext.text(p, "version"), InstallContext.text(p, "resolved"),
                        InstallContext.text(p, "integrity")));
            }
            return Optional.of(out);
        }
        JsonNode deps = json.get("dependencies");
        if (deps != null && deps.isObject()) {
            v1(deps, "", out);
            return Optional.of(out);
        }
        return Optional.empty();
    }

    private static void v1(JsonNode deps, String prefix, List<LockEntry> out) {
        for (var e : deps.properties()) {
            JsonNode d = e.getValue();
            if (d.path("bundled").asBoolean(false)) {
                continue;
            }
            String path = prefix + "node_modules/" + e.getKey();
            String version = InstallContext.text(d, "version");
            String name = e.getKey();
            if (version != null && version.startsWith("npm:")) {
                Spec s = spec(version);
                name = s.target();
                version = s.range();
            }
            if (version != null && Semver.parse(version).isPresent()) {
                out.add(new LockEntry(path, name, version, InstallContext.text(d, "resolved"), InstallContext.text(d, "integrity")));
            }
            JsonNode nested = d.get("dependencies");
            if (nested != null && nested.isObject()) {
                v1(nested, path + "/", out);
            }
        }
    }

    /**
     * The lockfile paths npm really installs on this platform: everything reachable from the project (and its workspace
     * packages) through dependencies whose os / cpu / libc fit. A package that only another platform's binary needs
     * (e.g. {@code @emnapi/runtime}, used by a {@code wasm32} build) is not reachable and therefore not needed.
     * Null when the lockfile has no dependency information to follow.
     */
    static Set<String> reachable(JsonNode packages, TargetPlatform platform) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        for (var e : packages.properties()) {
            if (!e.getKey().contains("node_modules/")) {
                queue.add(e.getKey()); // the project itself ("") and its workspace packages
                seen.add(e.getKey());
            }
        }
        if (!packages.has("")) {
            return null;
        }
        while (!queue.isEmpty()) {
            String path = queue.poll();
            JsonNode p = packages.path(path);
            if (p.path("link").asBoolean(false)) {
                String target = InstallContext.text(p, "resolved");
                if (target != null && packages.has(target) && seen.add(target)) {
                    queue.add(target);
                }
                continue;
            }
            boolean isRoot = !path.contains("node_modules/");
            for (String section : isRoot
                    ? List.of("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
                    : List.of("dependencies", "optionalDependencies", "peerDependencies")) {
                JsonNode deps = p.get(section);
                if (deps == null || !deps.isObject()) {
                    continue;
                }
                for (var d : deps.properties()) {
                    String target = resolveInLock(packages, path, d.getKey());
                    if (target != null && platformMatches(packages.path(target), platform) && seen.add(target)) {
                        queue.add(target);
                    }
                }
            }
        }
        return seen;
    }

    /** Node's lookup inside the lockfile: {@code <path>/node_modules/<name>}, then each parent level, then the root. */
    private static String resolveInLock(JsonNode packages, String from, String name) {
        String cur = from;
        while (true) {
            String candidate = (cur.isEmpty() ? "" : cur + "/") + "node_modules/" + name;
            if (packages.has(candidate)) {
                return candidate;
            }
            if (cur.isEmpty()) {
                return null;
            }
            int idx = cur.lastIndexOf("/node_modules/");
            if (idx >= 0) {
                cur = cur.substring(0, idx);
            } else if (cur.startsWith("node_modules/")) {
                cur = "";
            } else {
                cur = ProjectFiles.dirOf(cur); // workspace package folder: its parents up to the project root
            }
        }
    }

    /** npm's os / cpu / libc fields: {@code ["win32"]}, {@code ["!win32"]}; Linux is assumed to use glibc. */
    static boolean platformMatches(JsonNode p, TargetPlatform platform) {
        String libc = platform.os() == TargetPlatform.Os.LINUX ? "glibc" : null;
        return listAllows(p.get("os"), platform.npmOs()) && listAllows(p.get("cpu"), platform.npmCpu())
                && (libc == null ? !p.has("libc") || p.path("libc").isEmpty() : listAllows(p.get("libc"), libc));
    }

    private static boolean listAllows(JsonNode list, String value) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return true;
        }
        boolean anyPositive = false;
        boolean matched = false;
        for (JsonNode n : list) {
            String s = n.asString();
            if (s.startsWith("!")) {
                if (s.substring(1).equals(value)) {
                    return false;
                }
            } else {
                anyPositive = true;
                if (s.equals(value)) {
                    matched = true;
                }
            }
        }
        return !anyPositive || matched;
    }

    // ------------------------------------------------------------------------------------------ install

    @Override
    public void install(ProjectFiles files, UnitSpec unit, InstallContext ctx) throws IOException {
        Map<String, Manifest> all = manifests(files);
        String root = installRoot(all, unit.key());
        if (!ctx.claim("npm:" + root)) {
            return; // the workspace root was already set up in this run
        }
        Manifest rootManifest = all.get(root);
        if (rootManifest == null) {
            throw new IOException("package.json could not be read");
        }
        Optional<List<LockEntry>> lock = lockEntries(files.root(), root, ctx.platform());
        List<LockEntry> plan;
        if (lock.isPresent()) {
            ctx.progress().log("Installing from " + (root.isEmpty() ? "" : root + "/") + "package-lock.json");
            plan = lock.get();
        } else {
            ctx.progress().log("No package-lock.json: resolving versions with the npm registry");
            plan = resolve(all, rootManifest, ctx);
        }
        Path base = files.root().resolve(root);
        List<LockEntry> todo = plan.stream().filter(e -> !installedVersionIs(base.resolve(e.path()), e.version())).toList();
        ctx.progress().log(todo.size() + " of " + plan.size() + " packages to download");
        AtomicInteger done = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (LockEntry e : todo) {
            tasks.add(() -> {
                ctx.checkCancelled();
                try {
                    fetch(base, e, ctx);
                } catch (IOException ex) {
                    ctx.fail(e.name() + "@" + e.version() + ": " + ex.getMessage());
                }
                ctx.progress().step(e.name() + "@" + e.version(), done.incrementAndGet(), todo.size());
                return null;
            });
        }
        ctx.runAll(tasks);
    }

    private void fetch(Path base, LockEntry e, InstallContext ctx) throws IOException {
        String url = e.resolved();
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            if (e.version() == null) {
                throw new IOException("no download address in the lockfile");
            }
            url = tarballUrl(ctx.settings(), e.name(), e.version());
        }
        Path dest = base.resolve(e.path()).normalize();
        if (!dest.startsWith(base)) {
            throw new IOException("invalid package path " + e.path());
        }
        Path tgz = Files.createTempFile("orbit-npm", ".tgz");
        try {
            ctx.downloader().file(url, null, tgz);
            Downloader.verifyIntegrity(tgz, e.integrity(), null, e.name() + "@" + e.version());
            Archives.deleteTree(dest);
            Archives.untarGz(tgz, dest, Archives::stripFirst);
        } finally {
            Files.deleteIfExists(tgz);
        }
    }

    static String tarballUrl(DependencySettings s, String name, String version) {
        String base = name.contains("/") ? name.substring(name.indexOf('/') + 1) : name;
        return s.trimmed(s.npmRegistry()) + "/" + name + "/-/" + base + "-" + version + ".tgz";
    }

    // ------------------------------------------------------------------------------------------ registry resolution

    private record Want(String parentPath, String name, String target, String range, boolean optional) {
    }

    /**
     * Resolves the dependency tree like npm without a lockfile: the newest version in range (dist-tag latest when it
     * fits), placed as high up in node_modules as possible and nested below its dependent on a version conflict.
     */
    private List<LockEntry> resolve(Map<String, Manifest> all, Manifest root, InstallContext ctx) throws IOException {
        Map<String, JsonNode> packuments = new ConcurrentHashMap<>();
        // node_modules folder (relative to the install root) -> package name -> chosen version
        Map<String, Map<String, String>> placed = new HashMap<>();
        Map<String, LockEntry> plan = new LinkedHashMap<>();
        Deque<Want> queue = new ArrayDeque<>();

        List<Manifest> members = new ArrayList<>();
        members.add(root);
        Set<String> localNames = new LinkedHashSet<>();
        for (Manifest m : all.values()) {
            if (!m.dir().equals(root.dir()) && installRoot(all, m.dir()).equals(root.dir())) {
                members.add(m);
            }
        }
        for (Manifest m : members) {
            if (m.name() != null) {
                localNames.add(m.name());
            }
        }
        for (Manifest m : members) {
            for (String section : List.of("dependencies", "devDependencies", "optionalDependencies")) {
                JsonNode deps = m.json().get(section);
                if (deps == null || !deps.isObject()) {
                    continue;
                }
                for (var e : deps.properties()) {
                    if (members.size() > 1 && localNames.contains(e.getKey())) {
                        continue;
                    }
                    enqueue(queue, "", e.getKey(), e.getValue().asString(""), section.startsWith("optional"), ctx);
                }
            }
        }
        int processed = 0;
        while (!queue.isEmpty()) {
            ctx.checkCancelled();
            Want w = queue.poll();
            if (++processed % 25 == 0) {
                ctx.progress().step("resolving " + w.target(), plan.size(), Math.max(plan.size() + queue.size(), 1));
            }
            if (alreadySatisfied(placed, w)) {
                continue;
            }
            JsonNode packument;
            try {
                packument = packument(w.target(), packuments, ctx);
            } catch (IOException e) {
                if (!w.optional()) {
                    ctx.fail(w.target() + ": " + e.getMessage());
                }
                continue;
            }
            String version = choose(packument, w.range());
            if (version == null) {
                if (!w.optional()) {
                    ctx.fail(w.target() + "@" + w.range() + ": no published version matches");
                }
                continue;
            }
            JsonNode meta = packument.path("versions").path(version);
            if (!platformMatches(meta, ctx.platform())) {
                continue; // a package for another operating system / processor
            }
            String folder = placement(placed, w);
            placed.computeIfAbsent(folder, k -> new HashMap<>()).put(w.name(), version);
            String path = ProjectFiles.join(folder, "node_modules/" + w.name());
            JsonNode dist = meta.path("dist");
            plan.put(path, new LockEntry(path, w.target(), version, InstallContext.text(dist, "tarball"),
                    InstallContext.text(dist, "integrity")));
            if (plan.size() > MAX_PACKAGES) {
                throw new IOException("More than " + MAX_PACKAGES + " packages - stopped");
            }
            JsonNode peerMeta = meta.path("peerDependenciesMeta");
            for (String section : List.of("dependencies", "optionalDependencies", "peerDependencies")) {
                JsonNode deps = meta.get(section);
                if (deps == null || !deps.isObject()) {
                    continue;
                }
                for (var e : deps.properties()) {
                    boolean optional = section.equals("optionalDependencies")
                            || (section.equals("peerDependencies") && peerMeta.path(e.getKey()).path("optional").asBoolean(false));
                    if (section.equals("dependencies") && meta.path("optionalDependencies").has(e.getKey())) {
                        continue; // listed in both: npm treats it as optional
                    }
                    enqueue(queue, path, e.getKey(), e.getValue().asString(""), optional, ctx);
                }
            }
        }
        return new ArrayList<>(plan.values());
    }

    private void enqueue(Deque<Want> queue, String parent, String name, String raw, boolean optional, InstallContext ctx) {
        Spec s = spec(raw);
        switch (s.kind()) {
            case REGISTRY, TAG -> queue.add(new Want(parent, name, name, s.range(), optional));
            case ALIAS -> queue.add(new Want(parent, name, s.target(), s.range(), optional));
            case LOCAL -> {
                // part of the project
            }
            case REMOTE -> {
                if (!optional) {
                    ctx.fail(name + ": git / URL dependency (" + raw + ") - install it with npm install");
                }
            }
        }
    }

    /** The folders whose node_modules a package in {@code parentPath} sees, nearest first. */
    private static List<String> lookupChain(String parentPath) {
        List<String> chain = new ArrayList<>();
        String cur = parentPath;
        while (!cur.isEmpty()) {
            chain.add(cur);
            int idx = cur.lastIndexOf("/node_modules/");
            cur = idx < 0 ? "" : cur.substring(0, idx);
        }
        chain.add("");
        return chain;
    }

    private static boolean alreadySatisfied(Map<String, Map<String, String>> placed, Want w) {
        for (String folder : lookupChain(w.parentPath())) {
            String v = placed.getOrDefault(folder, Map.of()).get(w.name());
            if (v != null) {
                return w.range() == null || Semver.tryRange(w.range()).map(r -> r.test(v)).orElse(true)
                        || Semver.tryRange(w.range()).isEmpty();
            }
        }
        return false;
    }

    /** Highest free folder on the lookup chain; nested right below the dependent when a different version is in the way. */
    private static String placement(Map<String, Map<String, String>> placed, Want w) {
        List<String> chain = lookupChain(w.parentPath());
        for (String folder : chain) {
            if (placed.getOrDefault(folder, Map.of()).containsKey(w.name())) {
                return chain.get(0);
            }
        }
        return "";
    }

    private static JsonNode packument(String name, Map<String, JsonNode> cache, InstallContext ctx) throws IOException {
        JsonNode cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        String encoded = name.startsWith("@") ? "@" + URLEncoder.encode(name.substring(1), StandardCharsets.UTF_8) : name;
        String text = ctx.downloader().text(ctx.settings().trimmed(ctx.settings().npmRegistry()) + "/" + encoded,
                "application/vnd.npm.install-v1+json; q=1.0, application/json; q=0.8");
        JsonNode json = InstallContext.json(text).orElseThrow(() -> new IOException("invalid registry answer"));
        cache.put(name, json);
        return json;
    }

    /** npm's choice: the dist-tag, or {@code latest} when it fits the range, otherwise the highest match. */
    static String choose(JsonNode packument, String range) {
        JsonNode versions = packument.path("versions");
        JsonNode tags = packument.path("dist-tags");
        if (range != null && !range.isBlank() && Semver.tryRange(range).isEmpty()) {
            String tagged = InstallContext.text(tags, range);
            return tagged != null && versions.has(tagged) ? tagged : null;
        }
        Semver.Range r = Semver.range(range == null ? "" : range);
        String latest = InstallContext.text(tags, "latest");
        if (latest != null && versions.has(latest) && r.test(latest)) {
            return latest;
        }
        List<String> names = new ArrayList<>();
        for (var e : versions.properties()) {
            names.add(e.getKey());
        }
        return r.maxSatisfying(names).orElse(null);
    }
}
