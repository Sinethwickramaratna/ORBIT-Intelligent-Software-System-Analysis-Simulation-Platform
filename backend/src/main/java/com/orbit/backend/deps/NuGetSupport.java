package com.orbit.backend.deps;

import com.orbit.backend.deps.DependencyReport.Item;
import com.orbit.backend.deps.version.NuGetVersion;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * .NET projects: every {@code *.csproj} / {@code *.fsproj} / {@code *.vbproj} is a unit ({@code PackageReference},
 * versions from the project or from central package management in {@code Directory.Packages.props}), a lone
 * {@code packages.config} too. Setup downloads the packages and their dependencies for the project's target framework
 * from nuget.org into {@code .orbit/nuget-packages}, laid out like NuGet's global packages folder
 * ({@code <id>/<version>/}). Packages in the user's {@code ~/.nuget/packages} also count as installed.
 */
public final class NuGetSupport implements EcosystemSupport {

    public static final String FOLDER = InstallContext.ORBIT_DIR + "/nuget-packages";

    private static final Pattern PACKAGE_REF = Pattern.compile(
            "<PackageReference\\b([^>]*?)(/>|>(.*?)</PackageReference>)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKAGE_VERSION = Pattern.compile(
            "<PackageVersion\\b([^>]*?)/?>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIG_PACKAGE = Pattern.compile("<package\\b([^>]*?)/?>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TFM = Pattern.compile("<TargetFrameworks?>\\s*([^<]+?)\\s*</TargetFrameworks?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    @Override
    public Ecosystem ecosystem() {
        return Ecosystem.NUGET;
    }

    @Override
    public List<UnitSpec> units(ProjectFiles files) {
        List<UnitSpec> out = new ArrayList<>();
        List<String> projects = files.all().stream().filter(p -> {
            String n = ProjectFiles.fileName(p).toLowerCase(Locale.ROOT);
            return n.endsWith(".csproj") || n.endsWith(".fsproj") || n.endsWith(".vbproj");
        }).toList();
        for (String p : projects) {
            List<String> manifests = new ArrayList<>(List.of(p));
            String config = ProjectFiles.join(ProjectFiles.dirOf(p), "packages.config");
            if (files.all().contains(config)) {
                manifests.add(config);
            }
            out.add(new UnitSpec(Ecosystem.NUGET, p, manifests));
        }
        for (String c : files.named("packages.config")) {
            String dir = ProjectFiles.dirOf(c);
            if (projects.stream().noneMatch(p -> ProjectFiles.dirOf(p).equals(dir))) {
                out.add(new UnitSpec(Ecosystem.NUGET, c, List.of(c)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ manifests

    record Ref(String id, String version) {
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(attrs);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String element(String body, String name) {
        if (body == null) {
            return null;
        }
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">", Pattern.CASE_INSENSITIVE).matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Central package versions from the nearest Directory.Packages.props. */
    private static Map<String, String> centralVersions(ProjectFiles files, String projectPath) {
        String cur = ProjectFiles.dirOf(projectPath);
        while (true) {
            String t = files.read(ProjectFiles.join(cur, "Directory.Packages.props"));
            if (t != null) {
                Map<String, String> out = new HashMap<>();
                Matcher m = PACKAGE_VERSION.matcher(COMMENT.matcher(t).replaceAll(""));
                while (m.find()) {
                    String id = attr(m.group(1), "Include");
                    String v = attr(m.group(1), "Version");
                    if (id != null && v != null) {
                        out.put(id.toLowerCase(Locale.ROOT), v);
                    }
                }
                return out;
            }
            if (cur.isEmpty()) {
                return Map.of();
            }
            cur = ProjectFiles.dirOf(cur);
        }
    }

    List<Ref> references(ProjectFiles files, UnitSpec unit) {
        List<Ref> out = new ArrayList<>();
        for (String m : unit.manifests()) {
            String text = files.read(m);
            if (text == null) {
                continue;
            }
            text = COMMENT.matcher(text).replaceAll("");
            if (ProjectFiles.fileName(m).equalsIgnoreCase("packages.config")) {
                Matcher pm = CONFIG_PACKAGE.matcher(text);
                while (pm.find()) {
                    String id = attr(pm.group(1), "id");
                    if (id != null) {
                        out.add(new Ref(id, attr(pm.group(1), "version")));
                    }
                }
                continue;
            }
            Map<String, String> central = null;
            Matcher pr = PACKAGE_REF.matcher(text);
            while (pr.find()) {
                String id = attr(pr.group(1), "Include");
                if (id == null || id.contains("$(")) {
                    continue;
                }
                String v = attr(pr.group(1), "Version");
                if (v == null) {
                    v = element(pr.group(3), "Version");
                }
                if (v == null) {
                    v = attr(pr.group(1), "VersionOverride");
                }
                if (v == null) {
                    if (central == null) {
                        central = centralVersions(files, m);
                    }
                    v = central.get(id.toLowerCase(Locale.ROOT));
                }
                out.add(new Ref(id, v));
            }
        }
        return out;
    }

    static List<String> targetFrameworks(String projectText) {
        if (projectText == null) {
            return List.of();
        }
        Matcher m = TFM.matcher(projectText);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            for (String t : m.group(1).split(";")) {
                if (!t.isBlank() && !t.contains("$(")) {
                    out.add(t.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ check

    private static List<Path> folders(ProjectFiles files, InstallContext ctx) {
        List<Path> out = new ArrayList<>();
        out.add(files.root().resolve(FOLDER));
        if (ctx.settings().userHome() != null) {
            out.add(ctx.settings().userHome().resolve(".nuget").resolve("packages"));
        }
        return out;
    }

    static String installedVersion(List<Path> folders, String id, NuGetVersion.Range range) {
        String best = null;
        for (Path f : folders) {
            Path dir = f.resolve(id.toLowerCase(Locale.ROOT));
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                for (Path v : s.filter(Files::isDirectory).toList()) {
                    String name = v.getFileName().toString();
                    boolean complete = Files.exists(v.resolve(".nupkg.metadata"))
                            || Files.exists(v.resolve(id.toLowerCase(Locale.ROOT) + "." + name + ".nupkg"));
                    if (complete && (range == null || range.test(name))) {
                        return name;
                    }
                    if (complete) {
                        best = name;
                    }
                }
            } catch (IOException ignored) {
                // next folder
            }
        }
        return range == null ? best : null;
    }

    @Override
    public DependencyReport.Unit check(ProjectFiles files, UnitSpec unit, InstallContext ctx) {
        List<Path> folders = folders(files, ctx);
        List<Item> items = new ArrayList<>();
        for (Ref r : references(files, unit)) {
            Optional<NuGetVersion.Range> range = NuGetVersion.range(r.version());
            String have = installedVersion(folders, r.id(), range.orElse(null));
            if (have == null && range.isPresent()) {
                String other = installedVersion(folders, r.id(), null);
                if (other != null) {
                    items.add(new Item(r.id(), r.version(), other, "package", DependencyState.OUTDATED,
                            "installed " + other + " does not match " + r.version()));
                    continue;
                }
            }
            items.add(new Item(r.id(), r.version(), have, "package", have == null ? DependencyState.MISSING : DependencyState.INSTALLED,
                    r.version() == null && have == null ? "no version given" : null));
        }
        return DependencyReport.Unit.of(Ecosystem.NUGET, String.join(", ", unit.manifests()), FOLDER, null, items, false);
    }

    // ------------------------------------------------------------------------------------------ install

    @Override
    public void install(ProjectFiles files, UnitSpec unit, InstallContext ctx) throws IOException {
        InstallContext.orbitDir(files.root());
        Path folder = files.root().resolve(FOLDER);
        Files.createDirectories(folder);
        List<String> tfms = targetFrameworks(files.read(unit.manifests().get(0)));
        String tfm = tfms.isEmpty() ? null : tfms.get(0);
        List<Path> folders = folders(files, ctx);
        String base = ctx.settings().trimmed(ctx.settings().nugetFlatContainer());

        record Want(String id, NuGetVersion.Range range, String text) {
        }
        Deque<Want> queue = new ArrayDeque<>();
        for (Ref r : references(files, unit)) {
            Optional<NuGetVersion.Range> range = NuGetVersion.range(r.version());
            if (range.isEmpty()) {
                ctx.fail(r.id() + ": version '" + r.version() + "' cannot be read");
                continue;
            }
            queue.add(new Want(r.id(), range.get(), r.version()));
        }
        Map<String, String> chosen = new LinkedHashMap<>();
        Map<String, List<String>> versionCache = new HashMap<>();
        AtomicInteger done = new AtomicInteger();
        while (!queue.isEmpty()) {
            ctx.checkCancelled();
            Want w = queue.poll();
            String key = w.id().toLowerCase(Locale.ROOT);
            String have = chosen.get(key);
            if (have != null && (w.range().test(have) || NuGetVersion.parse(have).map(h -> w.range().min() != null
                    && h.compareTo(w.range().min()) > 0).orElse(false))) {
                continue; // the higher version already chosen serves this request too
            }
            String version = installedVersion(List.of(folder), w.id(), w.range());
            if (version == null) {
                List<String> versions;
                try {
                    versions = versionCache.get(key);
                    if (versions == null) {
                        JsonNode idx = InstallContext.json(ctx.downloader().text(base + "/" + key + "/index.json", "application/json"))
                                .orElseThrow(() -> new IOException("invalid NuGet answer"));
                        versions = new ArrayList<>();
                        for (JsonNode v : idx.path("versions")) {
                            versions.add(v.asString());
                        }
                        versionCache.put(key, versions);
                    }
                } catch (Downloader.NotFoundException e) {
                    ctx.fail(w.id() + ": not found on nuget.org");
                    continue;
                } catch (IOException e) {
                    ctx.fail(w.id() + ": " + e.getMessage());
                    continue;
                }
                Optional<String> pick = w.range().pick(versions);
                if (pick.isEmpty()) {
                    ctx.fail(w.id() + " " + (w.text() == null ? "" : w.text()) + ": no matching version on nuget.org");
                    continue;
                }
                version = NuGetVersion.parse(pick.get()).map(NuGetVersion.Version::normalized).orElse(pick.get().toLowerCase(Locale.ROOT));
                ctx.progress().step("Downloading " + w.id() + " " + version, done.get(), done.get() + queue.size() + 1);
                try {
                    download(base, key, version, folder, ctx);
                    ctx.installed(w.id() + " " + version, done.incrementAndGet(), done.get() + queue.size());
                } catch (IOException e) {
                    ctx.fail(w.id() + " " + version + ": " + e.getMessage());
                    continue;
                }
            }
            chosen.put(key, version);
            for (var dep : dependencies(folder.resolve(key).resolve(version), tfm)) {
                NuGetVersion.range(dep.version()).ifPresent(r -> queue.add(new Want(dep.id(), r, dep.version())));
            }
            if (installedVersion(folders, w.id(), w.range()) == null) {
                ctx.fail(w.id() + ": not installed");
            }
        }
        ctx.progress().log(chosen.size() + " NuGet packages in " + FOLDER);
    }

    private static void download(String base, String id, String version, Path folder, InstallContext ctx) throws IOException {
        Path dir = folder.resolve(id).resolve(version);
        Path nupkg = Files.createTempFile("orbit-nuget", ".nupkg");
        try {
            String url = base + "/" + id + "/" + version + "/" + id + "." + version + ".nupkg";
            ctx.downloader().file(url, null, nupkg);
            Archives.deleteTree(dir);
            Archives.unzip(nupkg, dir, name -> name.startsWith("_rels/") || name.startsWith("package/")
                    || name.equals("[Content_Types].xml") ? null : name);
            Files.copy(nupkg, dir.resolve(id + "." + version + ".nupkg"));
            String hash = Base64.getEncoder().encodeToString(Downloader.digest(nupkg, "SHA-512"));
            Files.writeString(dir.resolve(".nupkg.metadata"),
                    "{\n  \"version\": 2,\n  \"contentHash\": \"" + hash + "\",\n  \"source\": \"" + base + "\"\n}\n");
        } finally {
            Files.deleteIfExists(nupkg);
        }
    }

    // ------------------------------------------------------------------------------------------ nuspec dependencies

    private static final Pattern GROUP = Pattern.compile("<group\\b([^>]*?)(/>|>(.*?)</group>)", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPENDENCY = Pattern.compile("<dependency\\b([^>]*?)/?>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    static List<Ref> dependencies(Path packageDir, String projectTfm) throws IOException {
        Path nuspec;
        try (Stream<Path> s = Files.list(packageDir)) {
            nuspec = s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nuspec")).findFirst().orElse(null);
        }
        if (nuspec == null) {
            return List.of();
        }
        String text = COMMENT.matcher(Files.readString(nuspec)).replaceAll("");
        int start = text.indexOf("<dependencies");
        int end = text.indexOf("</dependencies>");
        if (start < 0 || end < 0) {
            return List.of();
        }
        String deps = text.substring(start, end);
        Matcher g = GROUP.matcher(deps);
        String bestBody = null;
        int bestRank = -1;
        boolean anyGroup = false;
        while (g.find()) {
            anyGroup = true;
            int rank = rank(projectTfm, attr(g.group(1), "targetFramework"));
            if (rank > bestRank) {
                bestRank = rank;
                bestBody = g.group(3) == null ? "" : g.group(3);
            }
        }
        String body = anyGroup ? bestBody : deps;
        List<Ref> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        Matcher d = DEPENDENCY.matcher(body);
        while (d.find()) {
            String id = attr(d.group(1), "id");
            String exclude = attr(d.group(1), "exclude");
            if (id != null && !(exclude != null && exclude.toLowerCase(Locale.ROOT).contains("compile")
                    && exclude.toLowerCase(Locale.ROOT).contains("runtime"))) {
                out.add(new Ref(id, attr(d.group(1), "version")));
            }
        }
        return out;
    }

    /** Framework family and version: net8.0 -> core 8.0, netstandard2.0 -> standard 2.0, net48 -> framework 4.8. */
    record Tfm(String family, double version) {
    }

    static Tfm tfm(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim().toLowerCase(Locale.ROOT).replaceAll("-.*$", "");
        Matcher m;
        if ((m = Pattern.compile("^\\.?net(?:coreapp|core)?(\\d+)\\.(\\d+)").matcher(t)).find() && !t.startsWith(".netframework")
                && !t.startsWith(".netstandard") && !t.startsWith("netstandard")) {
            return new Tfm("core", Double.parseDouble(m.group(1) + "." + m.group(2)));
        }
        if ((m = Pattern.compile("^\\.?netstandard(\\d+)\\.(\\d+)").matcher(t)).find()) {
            return new Tfm("standard", Double.parseDouble(m.group(1) + "." + m.group(2)));
        }
        if ((m = Pattern.compile("^\\.netframework(\\d+)\\.(\\d+)").matcher(t)).find()) {
            return new Tfm("framework", Double.parseDouble(m.group(1) + "." + m.group(2)));
        }
        if ((m = Pattern.compile("^net(\\d)(\\d+)$").matcher(t)).find()) {
            return new Tfm("framework", Double.parseDouble(m.group(1) + "." + m.group(2)));
        }
        return null;
    }

    /** How well a nuspec dependency group fits the project: higher is better, -1 = not compatible. */
    static int rank(String projectTfm, String groupTfm) {
        Tfm g = tfm(groupTfm);
        if (g == null) {
            return groupTfm == null || groupTfm.isBlank() ? 1 : -1;
        }
        Tfm p = tfm(projectTfm);
        if (p == null) {
            p = new Tfm("core", 8.0);
        }
        int v = (int) Math.round(g.version() * 100);
        if (g.family().equals(p.family())) {
            return g.version() <= p.version() + 1e-9 ? 20_000 + v : -1;
        }
        if (g.family().equals("standard")) {
            boolean ok = switch (p.family()) {
                case "core" -> p.version() >= 3.0 || g.version() <= 2.0;
                case "framework" -> p.version() >= 4.61 - 1e-9 && g.version() <= 2.0;
                default -> false;
            };
            return ok ? 10_000 + v : -1;
        }
        return -1;
    }
}
