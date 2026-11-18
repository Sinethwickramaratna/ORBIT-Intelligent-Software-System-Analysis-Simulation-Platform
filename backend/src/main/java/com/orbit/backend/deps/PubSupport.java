package com.orbit.backend.deps;

import com.orbit.backend.deps.DependencyReport.Item;
import com.orbit.backend.deps.version.Semver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Dart and Flutter projects: every {@code pubspec.yaml} is a unit. Packages that come with the Flutter / Dart SDK
 * ({@code sdk: flutter}) are reported as SDK packages, path dependencies as local. Setup downloads the hosted
 * packages from pub.dev - the versions pinned in {@code pubspec.lock} when there is one, otherwise the newest versions
 * that satisfy the constraints - into {@code .orbit/pub-cache} (same layout as Dart's pub cache, archive checksums
 * verified). Packages already resolved by {@code dart pub get} ({@code .dart_tool/package_config.json}) or present in
 * the user's own pub cache count as installed.
 */
public final class PubSupport implements EcosystemSupport {

    public static final String CACHE = InstallContext.ORBIT_DIR + "/pub-cache";
    private static final String HOSTED = "hosted/pub.dev";
    private static final int MAX_PACKAGES = 2_000;

    @Override
    public Ecosystem ecosystem() {
        return Ecosystem.PUB;
    }

    @Override
    public List<UnitSpec> units(ProjectFiles files) {
        List<UnitSpec> out = new ArrayList<>();
        for (String p : files.named("pubspec.yaml")) {
            out.add(new UnitSpec(Ecosystem.PUB, ProjectFiles.dirOf(p), List.of(p)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ pubspec

    enum Kind { HOSTED, SDK, PATH, GIT }

    record Dep(String name, Kind kind, String constraint, String scope) {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> yaml(String text) {
        if (text == null) {
            return Map.of();
        }
        try {
            Object o = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
            return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    static List<Dep> deps(Map<String, Object> pubspec) {
        List<Dep> out = new ArrayList<>();
        for (String section : List.of("dependencies", "dev_dependencies")) {
            if (!(pubspec.get(section) instanceof Map<?, ?> map)) {
                continue;
            }
            String scope = section.equals("dependencies") ? "runtime" : "dev";
            for (var e : map.entrySet()) {
                out.add(dep(String.valueOf(e.getKey()), e.getValue(), scope));
            }
        }
        return out;
    }

    private static Dep dep(String name, Object v, String scope) {
        if (v == null) {
            return new Dep(name, Kind.HOSTED, "any", scope);
        }
        if (v instanceof Map<?, ?> m) {
            if (m.containsKey("sdk")) {
                return new Dep(name, Kind.SDK, null, scope);
            }
            if (m.containsKey("path")) {
                return new Dep(name, Kind.PATH, null, scope);
            }
            if (m.containsKey("git")) {
                return new Dep(name, Kind.GIT, null, scope);
            }
            Object ver = m.get("version");
            return new Dep(name, Kind.HOSTED, ver == null ? "any" : String.valueOf(ver), scope);
        }
        return new Dep(name, Kind.HOSTED, String.valueOf(v), scope);
    }

    static Optional<Semver.Range> constraint(String c) {
        if (c == null) {
            return Optional.empty();
        }
        String t = c.trim();
        return Semver.tryRange(t.equals("any") ? "*" : t);
    }

    // ------------------------------------------------------------------------------------------ check

    private static final Pattern NAME_VERSION = Pattern.compile("/([A-Za-z0-9_]+)-(\\d+\\.\\d+\\.\\d+[^/]*)/?$");

    /** name -> version of every package the project already resolved, and every package in the known caches. */
    private static Map<String, List<String>> available(ProjectFiles files, String dir, InstallContext ctx) {
        Map<String, List<String>> out = new HashMap<>();
        String cfg = files.read(ProjectFiles.join(dir, ".dart_tool/package_config.json"));
        InstallContext.json(cfg).ifPresent(j -> {
            for (JsonNode p : j.path("packages")) {
                String name = InstallContext.text(p, "name");
                String uri = InstallContext.text(p, "rootUri");
                if (name == null || uri == null) {
                    continue;
                }
                Matcher m = NAME_VERSION.matcher(uri.replace('\\', '/'));
                out.computeIfAbsent(name, k -> new ArrayList<>()).add(m.find() ? m.group(2) : "");
            }
        });
        List<Path> caches = new ArrayList<>();
        caches.add(files.root().resolve(CACHE).resolve(HOSTED));
        Path home = ctx.settings().userHome();
        if (home != null) {
            caches.add(home.resolve(".pub-cache").resolve(HOSTED));
            caches.add(home.resolve("AppData/Local/Pub/Cache").resolve(HOSTED));
        }
        for (Path c : caches) {
            if (!Files.isDirectory(c)) {
                continue;
            }
            try (Stream<Path> s = Files.list(c)) {
                s.filter(p -> Files.isRegularFile(p.resolve("pubspec.yaml"))).forEach(p -> {
                    Matcher m = NAME_VERSION.matcher("/" + p.getFileName());
                    if (m.find()) {
                        out.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(m.group(2));
                    }
                });
            } catch (IOException ignored) {
                // next cache
            }
        }
        return out;
    }

    @Override
    public DependencyReport.Unit check(ProjectFiles files, UnitSpec unit, InstallContext ctx) {
        Map<String, Object> pubspec = yaml(files.read(unit.manifests().get(0)));
        Map<String, List<String>> available = available(files, unit.key(), ctx);
        CodeUsage usage = ctx.usage(files.root());
        List<Item> items = new ArrayList<>();
        int unused = 0;
        for (Dep d : deps(pubspec)) {
            if ((d.kind() == Kind.HOSTED || d.kind() == Kind.GIT) && !available.containsKey(d.name())
                    && !usage.dartUsed(unit.key(), d.name())) {
                items.add(new Item(d.name(), d.constraint(), null, d.scope(), DependencyState.UNUSED,
                        "not imported anywhere in the code - not downloaded"));
                unused++;
                continue;
            }
            switch (d.kind()) {
                case SDK -> items.add(new Item(d.name(), "sdk", null, d.scope(), DependencyState.SDK, "comes with the Flutter / Dart SDK"));
                case PATH -> items.add(new Item(d.name(), "path", null, d.scope(), DependencyState.LOCAL, "local path"));
                default -> {
                    List<String> versions = available.getOrDefault(d.name(), List.of());
                    Optional<Semver.Range> range = constraint(d.constraint());
                    String ok = versions.stream().filter(v -> v.isEmpty() || range.map(r -> r.test(v)).orElse(true))
                            .findFirst().orElse(null);
                    if (ok != null) {
                        items.add(new Item(d.name(), d.constraint(), ok.isEmpty() ? null : ok, d.scope(), DependencyState.INSTALLED, null));
                    } else if (!versions.isEmpty()) {
                        items.add(new Item(d.name(), d.constraint(), versions.get(0), d.scope(), DependencyState.OUTDATED,
                                "installed version does not match " + d.constraint()));
                    } else if (d.kind() == Kind.GIT) {
                        items.add(new Item(d.name(), "git", null, d.scope(), DependencyState.UNSUPPORTED,
                                "git dependency: run dart pub get / flutter pub get"));
                    } else {
                        items.add(new Item(d.name(), d.constraint(), null, d.scope(), DependencyState.MISSING, null));
                    }
                }
            }
        }
        String message = unused == 0 ? null
                : unused + (unused == 1 ? " dependency is" : " dependencies are") + " not used by the code and not downloaded";
        return DependencyReport.Unit.of(Ecosystem.PUB, unit.manifests().get(0), CACHE, message, items, false);
    }

    // ------------------------------------------------------------------------------------------ install

    record Pick(String name, String version, String url, String sha256) {
    }

    @Override
    public void install(ProjectFiles files, UnitSpec unit, InstallContext ctx) throws IOException {
        InstallContext.orbitDir(files.root());
        Path cache = files.root().resolve(CACHE).resolve(HOSTED);
        Files.createDirectories(cache);
        Map<String, Object> pubspec = yaml(files.read(unit.manifests().get(0)));
        CodeUsage usage = ctx.skipUnused() ? ctx.usage(files.root()) : CodeUsage.everything();
        Set<String> unused = new TreeSet<>();
        Set<String> used = new TreeSet<>();
        for (Dep d : deps(pubspec)) {
            if (d.kind() == Kind.HOSTED || d.kind() == Kind.GIT) {
                (usage.dartUsed(unit.key(), d.name()) ? used : unused).add(d.name());
            }
        }
        if (!unused.isEmpty()) {
            ctx.progress().log("Not used by the code, not downloaded (" + unused.size() + "): " + String.join(", ", unused));
        }
        for (Dep d : deps(pubspec)) {
            if (d.kind() == Kind.GIT && !unused.contains(d.name()) && !available(files, unit.key(), ctx).containsKey(d.name())) {
                ctx.fail(d.name() + ": git dependency - run dart pub get / flutter pub get");
            }
        }
        String api = ctx.settings().trimmed(ctx.settings().pubUrl()) + "/api/packages/";
        Map<String, JsonNode> meta = new HashMap<>();
        Map<String, Pick> picks = new LinkedHashMap<>();

        Map<String, Object> lock = yaml(files.read(ProjectFiles.join(unit.key(), "pubspec.lock")));
        if (lock.get("packages") instanceof Map<?, ?> locked && !locked.isEmpty()) {
            ctx.progress().log("Installing the versions pinned in pubspec.lock");
            Map<String, String> hosted = new LinkedHashMap<>();
            for (var e : locked.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> p && "hosted".equals(String.valueOf(p.get("source")))) {
                    hosted.put(String.valueOf(e.getKey()), String.valueOf(p.get("version")));
                }
            }
            // leave out what only the unused dependencies need (packages the SDK needs are kept)
            Set<String> drop = new HashSet<>();
            if (!unused.isEmpty()) {
                ctx.progress().step("Working out what only the unused dependencies need", 0, 0);
                drop.addAll(closure(unused, hosted, api, meta, ctx));
                drop.removeAll(closure(used, hosted, api, meta, ctx));
            }
            for (var e : hosted.entrySet()) {
                String name = e.getKey();
                String version = e.getValue();
                if (drop.contains(name)) {
                    continue;
                }
                try {
                    Pick pick = exact(api, name, version, meta, ctx);
                    if (pick != null) {
                        picks.put(name, pick);
                    } else {
                        ctx.fail(name + " " + version + ": not found on pub.dev");
                    }
                } catch (IOException ex) {
                    ctx.fail(name + ": " + ex.getMessage());
                }
            }
        } else {
            resolve(pubspec, api, meta, picks, ctx, unused);
        }

        List<Pick> todo = picks.values().stream()
                .filter(p -> !Files.isRegularFile(cache.resolve(p.name() + "-" + p.version()).resolve("pubspec.yaml"))).toList();
        ctx.progress().log(todo.size() + " of " + picks.size() + " pub packages to download");
        AtomicInteger done = new AtomicInteger();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Pick p : todo) {
            tasks.add(() -> {
                ctx.checkCancelled();
                Path dest = cache.resolve(p.name() + "-" + p.version());
                Path tgz = Files.createTempFile("orbit-pub", ".tar.gz");
                try {
                    ctx.downloader().file(p.url(), null, tgz);
                    Downloader.verifySha256(tgz, p.sha256(), p.name() + " " + p.version());
                    Archives.deleteTree(dest);
                    Archives.untarGz(tgz, dest, n -> n.startsWith("./") ? n.substring(2) : n);
                    ctx.installed(p.name() + " " + p.version(), done.incrementAndGet(), todo.size());
                } catch (IOException e) {
                    ctx.fail(p.name() + " " + p.version() + ": " + e.getMessage());
                    ctx.progress().step(p.name() + " " + p.version(), done.incrementAndGet(), todo.size());
                } finally {
                    Files.deleteIfExists(tgz);
                }
                return null;
            });
        }
        ctx.runAll(tasks);
    }

    private static JsonNode metadata(String api, String name, Map<String, JsonNode> cache, InstallContext ctx) throws IOException {
        JsonNode m = cache.get(name);
        if (m == null) {
            m = InstallContext.json(ctx.downloader().text(api + name, "application/vnd.pub.v2+json"))
                    .orElseThrow(() -> new IOException("invalid pub.dev answer"));
            cache.put(name, m);
        }
        return m;
    }

    private static Pick exact(String api, String name, String version, Map<String, JsonNode> cache, InstallContext ctx) throws IOException {
        for (JsonNode v : metadata(api, name, cache, ctx).path("versions")) {
            if (version.equals(InstallContext.text(v, "version"))) {
                return new Pick(name, version, InstallContext.text(v, "archive_url"), InstallContext.text(v, "archive_sha256"));
            }
        }
        return null;
    }

    /** Newest version satisfying every constraint seen so far for each package (SDK packages are left to the SDK). */
    /** The locked packages reachable from {@code roots} (themselves included), following each locked version's pubspec. */
    private static Set<String> closure(Set<String> roots, Map<String, String> locked, String api, Map<String, JsonNode> meta,
                                       InstallContext ctx) throws IOException {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String r : roots) {
            if (locked.containsKey(r) && seen.add(r)) {
                queue.add(r);
            }
        }
        while (!queue.isEmpty()) {
            ctx.checkCancelled();
            String name = queue.poll();
            JsonNode m;
            try {
                m = metadata(api, name, meta, ctx);
            } catch (Downloader.CancelledException e) {
                throw e;
            } catch (IOException e) {
                continue;
            }
            for (JsonNode v : m.path("versions")) {
                if (locked.get(name).equals(InstallContext.text(v, "version"))) {
                    for (var d : v.path("pubspec").path("dependencies").properties()) {
                        if (locked.containsKey(d.getKey()) && seen.add(d.getKey())) {
                            queue.add(d.getKey());
                        }
                    }
                    break;
                }
            }
        }
        return seen;
    }

    private static void resolve(Map<String, Object> pubspec, String api, Map<String, JsonNode> meta, Map<String, Pick> picks,
                                InstallContext ctx, Set<String> unused) throws IOException {
        record Want(String name, String constraint) {
        }
        Deque<Want> queue = new ArrayDeque<>();
        for (Dep d : deps(pubspec)) {
            if (d.kind() == Kind.HOSTED && !unused.contains(d.name())) {
                queue.add(new Want(d.name(), d.constraint()));
            }
        }
        Map<String, List<String>> constraints = new HashMap<>();
        while (!queue.isEmpty()) {
            ctx.checkCancelled();
            Want w = queue.poll();
            List<String> all = constraints.computeIfAbsent(w.name(), k -> new ArrayList<>());
            all.add(w.constraint());
            Pick have = picks.get(w.name());
            if (have != null && constraint(w.constraint()).map(r -> r.test(have.version())).orElse(true)) {
                continue;
            }
            JsonNode m;
            try {
                m = metadata(api, w.name(), meta, ctx);
            } catch (Downloader.NotFoundException e) {
                ctx.fail(w.name() + ": not found on pub.dev");
                continue;
            } catch (IOException e) {
                ctx.fail(w.name() + ": " + e.getMessage());
                continue;
            }
            JsonNode best = null;
            Semver.Version bestV = null;
            for (boolean pre : new boolean[]{false, true}) {
                for (JsonNode v : m.path("versions")) {
                    String ver = InstallContext.text(v, "version");
                    Optional<Semver.Version> sv = Semver.parse(ver);
                    if (sv.isEmpty() || v.path("retracted").asBoolean(false) || (sv.get().isPrerelease() && !pre)) {
                        continue;
                    }
                    boolean fits = all.stream().allMatch(c -> constraint(c).map(r -> sv.get().isPrerelease()
                            ? r.test(new Semver.Version(sv.get().major(), sv.get().minor(), sv.get().patch(), List.of())) : r.test(sv.get()))
                            .orElse(true));
                    if (fits && (bestV == null || sv.get().compareTo(bestV) > 0)) {
                        bestV = sv.get();
                        best = v;
                    }
                }
                if (best != null) {
                    break;
                }
            }
            if (best == null) {
                ctx.fail(w.name() + " " + String.join(", ", all) + ": no version on pub.dev satisfies every constraint");
                continue;
            }
            String version = InstallContext.text(best, "version");
            picks.put(w.name(), new Pick(w.name(), version, InstallContext.text(best, "archive_url"),
                    InstallContext.text(best, "archive_sha256")));
            if (picks.size() > MAX_PACKAGES) {
                throw new IOException("More than " + MAX_PACKAGES + " pub packages - stopped");
            }
            if (best.path("pubspec").path("dependencies").isObject()) {
                for (var e : best.path("pubspec").path("dependencies").properties()) {
                    JsonNode v = e.getValue();
                    if (v.isObject() && (v.has("sdk") || v.has("path") || v.has("git"))) {
                        continue;
                    }
                    String c = v.isObject() ? v.path("version").asString("any") : v.isNull() ? "any" : v.asString("any");
                    queue.add(new Want(e.getKey(), c));
                }
            }
        }
    }
}
