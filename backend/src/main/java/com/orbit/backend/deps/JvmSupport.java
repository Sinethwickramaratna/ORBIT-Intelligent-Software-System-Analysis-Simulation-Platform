package com.orbit.backend.deps;

import com.orbit.backend.deps.DependencyReport.Item;
import com.orbit.backend.deps.JvmManifests.Catalog;
import com.orbit.backend.deps.JvmManifests.Coordinate;
import com.orbit.backend.deps.JvmManifests.GradleBuild;
import com.orbit.backend.deps.JvmManifests.Pom;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.repository.WorkspaceReader;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.transfer.TransferEvent;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Java / Kotlin projects: Maven ({@code pom.xml}, every module is a unit) and Gradle ({@code build.gradle(.kts)}, every
 * build script is a unit; {@code gradle/libs.versions.toml} catalogs and Spring Boot / {@code platform(...)} BOMs are
 * understood). Setup does not need Maven, Gradle or a JDK: Maven Resolver downloads the dependencies - with parents,
 * BOMs and transitive dependencies, checksums verified - into {@code .orbit/maven-repository}, a standard Maven
 * repository folder inside the project. Jars already in the user's {@code ~/.m2} or Gradle cache also count.
 */
public final class JvmSupport implements EcosystemSupport {

    public static final String REPOSITORY = InstallContext.ORBIT_DIR + "/maven-repository";

    private final Ecosystem ecosystem;

    private JvmSupport(Ecosystem ecosystem) {
        this.ecosystem = ecosystem;
    }

    public static JvmSupport maven() {
        return new JvmSupport(Ecosystem.MAVEN);
    }

    public static JvmSupport gradle() {
        return new JvmSupport(Ecosystem.GRADLE);
    }

    @Override
    public Ecosystem ecosystem() {
        return ecosystem;
    }

    @Override
    public List<UnitSpec> units(ProjectFiles files) {
        List<UnitSpec> out = new ArrayList<>();
        List<String> manifests = ecosystem == Ecosystem.MAVEN ? files.named("pom.xml")
                : files.named("build.gradle", "build.gradle.kts");
        for (String p : manifests) {
            out.add(new UnitSpec(ecosystem, p, List.of(p)));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------ project model

    /** All POMs of the project by groupId:artifactId:version, and the groupId:artifactId set the project builds. */
    private record Workspace(Map<String, Pom> byGav, Map<String, Pom> byPath, Set<String> ownGa) {
    }

    private static Workspace workspace(ProjectFiles files) {
        Map<String, Pom> byGav = new LinkedHashMap<>();
        Map<String, Pom> byPath = new LinkedHashMap<>();
        Set<String> ga = new LinkedHashSet<>();
        for (String p : files.named("pom.xml")) {
            JvmManifests.pom(p, files.read(p)).ifPresent(pom -> {
                byPath.put(p, pom);
                if (pom.group() != null && pom.artifact() != null) {
                    ga.add(pom.group() + ":" + pom.artifact());
                    if (pom.version() != null) {
                        byGav.put(pom.gav(), pom);
                    }
                }
            });
        }
        return new Workspace(byGav, byPath, ga);
    }

    /** groupIds/names of the Gradle (sub)projects, which are built by the project itself. */
    private static Set<String> gradleProjectNames(ProjectFiles files) {
        Set<String> names = new LinkedHashSet<>();
        for (String p : files.named("build.gradle", "build.gradle.kts")) {
            String dir = ProjectFiles.dirOf(p);
            names.add(dir.isEmpty() ? ProjectFiles.fileName(files.root().toString()) : ProjectFiles.fileName(dir));
        }
        return names;
    }

    private static Catalog catalogFor(ProjectFiles files, String buildFile) {
        String cur = ProjectFiles.dirOf(buildFile);
        while (true) {
            String t = files.read(ProjectFiles.join(cur, "gradle/libs.versions.toml"));
            if (t != null) {
                return JvmManifests.catalog(t);
            }
            if (cur.isEmpty()) {
                return Catalog.EMPTY;
            }
            cur = ProjectFiles.dirOf(cur);
        }
    }

    /**
     * The build with the versions React Native's Gradle plugin would fill in: versionless {@code com.facebook.react:*}
     * dependencies get the version of {@code node_modules/react-native} (searched from the build file's folder upwards).
     */
    static GradleBuild withReactNativeVersions(ProjectFiles files, String buildFile, GradleBuild build) {
        if (!build.reactNative()) {
            return build;
        }
        String version = null;
        String cur = ProjectFiles.dirOf(buildFile);
        while (version == null) {
            version = InstallContext.text(InstallContext.json(files.read(ProjectFiles.join(cur, "node_modules/react-native/package.json")))
                    .orElse(null), "version");
            if (cur.isEmpty()) {
                break;
            }
            cur = ProjectFiles.dirOf(cur);
        }
        if (version == null) {
            return build;
        }
        List<Coordinate> deps = new ArrayList<>();
        for (Coordinate c : build.dependencies()) {
            deps.add(c.version() == null && "com.facebook.react".equals(c.group())
                    ? new Coordinate(c.group(), c.artifact(), version, c.scope(), c.type(), c.classifier()) : c);
        }
        return new GradleBuild(deps, build.platforms(), build.repositories(), build.springBootVersion(), build.unresolved(), true);
    }

    // ------------------------------------------------------------------------------------------ check

    /** The local repositories whose jars count as installed (project first). */
    private static List<Path> repositories(ProjectFiles files, InstallContext ctx) {
        List<Path> out = new ArrayList<>();
        out.add(files.root().resolve(REPOSITORY));
        if (ctx.settings().userHome() != null) {
            out.add(ctx.settings().userHome().resolve(".m2").resolve("repository"));
        }
        return out;
    }

    private static Path gradleCache(InstallContext ctx) {
        return ctx.settings().userHome() == null ? null
                : ctx.settings().userHome().resolve(".gradle/caches/modules-2/files-2.1");
    }

    /** Installed version of a coordinate (the requested one, or any when the version is managed); null when absent. */
    static String installedVersion(Coordinate c, List<Path> repos, Path gradleCache) {
        String ext = "pom".equals(c.type()) ? ".pom" : null;
        for (Path repo : repos) {
            Path dir = repo.resolve(c.group().replace('.', '/')).resolve(c.artifact());
            String v = versionIn(dir, c, ext, false);
            if (v != null) {
                return v;
            }
        }
        if (gradleCache != null) {
            String v = versionIn(gradleCache.resolve(c.group()).resolve(c.artifact()), c, ext, true);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String versionIn(Path artifactDir, Coordinate c, String ext, boolean gradleLayout) {
        if (!Files.isDirectory(artifactDir)) {
            return null;
        }
        List<String> versions = new ArrayList<>();
        if (c.version() != null && !c.version().contains("[") && !c.version().contains("(") && !c.version().contains("+")) {
            versions.add(c.version());
        } else {
            try (Stream<Path> s = Files.list(artifactDir)) {
                s.filter(Files::isDirectory).forEach(p -> versions.add(p.getFileName().toString()));
            } catch (IOException e) {
                return null;
            }
        }
        for (String v : versions) {
            Path vdir = artifactDir.resolve(v);
            if (!Files.isDirectory(vdir)) {
                continue;
            }
            String prefix = c.artifact() + "-" + v;
            try (Stream<Path> s = gradleLayout ? Files.walk(vdir, 2) : Files.list(vdir)) {
                boolean hit = s.map(p -> p.getFileName().toString()).anyMatch(n -> n.startsWith(prefix)
                        && (ext != null ? n.endsWith(ext) : n.endsWith(".jar") || n.endsWith(".aar")
                        || (!"jar".equals(c.type()) && n.endsWith("." + c.type()))));
                if (hit) {
                    return v;
                }
            } catch (IOException e) {
                // try the next version
            }
        }
        return null;
    }

    @Override
    public DependencyReport.Unit check(ProjectFiles files, UnitSpec unit, InstallContext ctx) {
        return ecosystem == Ecosystem.MAVEN ? checkMaven(files, unit, ctx) : checkGradle(files, unit, ctx);
    }

    private DependencyReport.Unit checkMaven(ProjectFiles files, UnitSpec unit, InstallContext ctx) {
        Workspace ws = workspace(files);
        Pom pom = ws.byPath().get(unit.key());
        if (pom == null) {
            return DependencyReport.Unit.of(ecosystem, unit.key(), REPOSITORY, "pom.xml could not be read", List.of(), false);
        }
        List<Path> repos = repositories(files, ctx);
        Map<String, String> props = JvmManifests.effectiveProperties(pom, ws.byGav());
        Map<String, String> managed = JvmManifests.effectiveManaged(pom, ws.byGav());
        List<Item> items = new ArrayList<>();
        if (pom.parent() != null) {
            String ga = pom.parent().ga();
            if (ws.ownGa().contains(ga)) {
                items.add(new Item(ga, pom.parent().version(), pom.parent().version(), "parent", DependencyState.LOCAL, "module of this project"));
            } else {
                String have = installedVersion(pom.parent(), repos, null);
                items.add(new Item(ga, pom.parent().version(), have, "parent",
                        have == null ? DependencyState.MISSING : DependencyState.INSTALLED, null));
            }
        }
        for (Coordinate raw : pom.dependencies()) {
            String g = JvmManifests.interpolate(raw.group(), props);
            String a = JvmManifests.interpolate(raw.artifact(), props);
            if (g == null || a == null) {
                continue;
            }
            String v = raw.version() == null ? managed.get(g + ":" + a) : JvmManifests.interpolate(raw.version(), props);
            String shown = v == null ? (raw.version() == null ? "managed" : raw.version()) : v;
            Coordinate c = new Coordinate(g, a, v, raw.scope(), raw.type(), raw.classifier());
            if ("system".equals(raw.scope()) || ws.ownGa().contains(c.ga())) {
                items.add(new Item(c.ga(), shown, null, raw.scope(), DependencyState.LOCAL,
                        "system".equals(raw.scope()) ? "system scope file" : "module of this project"));
                continue;
            }
            String have = installedVersion(c, repos, null);
            items.add(new Item(c.ga(), shown, have, raw.scope(), have == null ? DependencyState.MISSING : DependencyState.INSTALLED, null));
        }
        return DependencyReport.Unit.of(ecosystem, unit.key(), REPOSITORY, null, items, false);
    }

    private DependencyReport.Unit checkGradle(ProjectFiles files, UnitSpec unit, InstallContext ctx) {
        GradleBuild build = withReactNativeVersions(files, unit.key(),
                JvmManifests.gradle(files.read(unit.key()), catalogFor(files, unit.key())));
        List<Path> repos = repositories(files, ctx);
        Path gc = gradleCache(ctx);
        List<Item> items = new ArrayList<>();
        for (Coordinate c : build.platforms()) {
            String have = installedVersion(c, repos, gc);
            items.add(new Item(c.ga(), c.version(), have, "platform", have == null ? DependencyState.MISSING : DependencyState.INSTALLED, null));
        }
        for (Coordinate c : build.dependencies()) {
            String have = installedVersion(c, repos, gc);
            items.add(new Item(c.ga(), c.version() == null ? "managed" : c.version(), have, c.scope(),
                    have == null ? DependencyState.MISSING : DependencyState.INSTALLED, null));
        }
        for (String ref : build.unresolved()) {
            items.add(new Item(ref, null, null, "compile", DependencyState.UNSUPPORTED, "not found in gradle/libs.versions.toml"));
        }
        return DependencyReport.Unit.of(ecosystem, unit.key(), REPOSITORY, null, items, false);
    }

    // ------------------------------------------------------------------------------------------ install

    /** Resolves the project's own POMs from the project folder, so parents and sibling modules need no download. */
    private static final class ProjectWorkspaceReader implements WorkspaceReader {
        private final WorkspaceRepository repo = new WorkspaceRepository("orbit-project");
        private final Map<String, File> poms = new HashMap<>();

        ProjectWorkspaceReader(ProjectFiles files, Workspace ws) {
            ws.byGav().forEach((gav, pom) -> poms.put(gav, files.root().resolve(pom.path()).toFile()));
        }

        @Override
        public WorkspaceRepository getRepository() {
            return repo;
        }

        @Override
        public File findArtifact(Artifact a) {
            return "pom".equals(a.getExtension()) ? poms.get(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getBaseVersion()) : null;
        }

        @Override
        public List<String> findVersions(Artifact a) {
            List<String> out = new ArrayList<>();
            poms.keySet().forEach(gav -> {
                if (gav.startsWith(a.getGroupId() + ":" + a.getArtifactId() + ":")) {
                    out.add(gav.substring(gav.lastIndexOf(':') + 1));
                }
            });
            return out;
        }
    }

    /** Gradle's conflict rule: the highest requested version wins (the nearest one when versions are equal). */
    static final class HighestVersionSelector extends org.eclipse.aether.util.graph.transformer.ConflictResolver.VersionSelector {
        @Override
        public void selectVersion(org.eclipse.aether.util.graph.transformer.ConflictResolver.ConflictContext context) {
            org.eclipse.aether.util.graph.transformer.ConflictResolver.ConflictItem winner = null;
            for (var item : context.getItems()) {
                if (winner == null) {
                    winner = item;
                    continue;
                }
                var v = item.getNode().getVersion();
                var w = winner.getNode().getVersion();
                int c = v == null || w == null ? 0 : v.compareTo(w);
                if (c > 0 || (c == 0 && item.getDepth() < winner.getDepth())) {
                    winner = item;
                }
            }
            context.setWinner(winner);
        }
    }

    private record Resolver(RepositorySystem system, DefaultRepositorySystemSession session) {
    }

    private static Resolver resolver(Path localRepo, WorkspaceReader workspace, InstallContext ctx, AtomicInteger downloads) {
        RepositorySystem system = new RepositorySystemSupplier().get();
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(localRepo.toFile())));
        if (workspace != null) {
            session.setWorkspaceReader(workspace);
        }
        Map<String, String> sys = new HashMap<>();
        System.getProperties().forEach((k, v) -> sys.put(String.valueOf(k), String.valueOf(v)));
        session.setSystemProperties(sys);
        session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        session.setIgnoreArtifactDescriptorRepositories(false);
        session.setConfigProperty("aether.connector.userAgent", "ORBIT-dependency-setup/1.0");
        session.setConfigProperty("aether.connector.basic.threads", Math.max(2, ctx.settings().parallelDownloads()));
        // a stalled repository fails instead of hanging (the defaults are 10 s connect, 30 min per request)
        session.setConfigProperty("aether.connector.connectTimeout", 20_000);
        session.setConfigProperty("aether.connector.requestTimeout", 60_000);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferInitiated(TransferEvent event) throws TransferCancelledException {
                stopIfCancelled();
                started.incrementAndGet();
                String name = event.getResource().getResourceName();
                ctx.progress().step("Downloading " + name.substring(name.lastIndexOf('/') + 1), finished.get(), started.get());
            }

            @Override
            public void transferStarted(TransferEvent event) throws TransferCancelledException {
                stopIfCancelled();
            }

            @Override
            public void transferProgressed(TransferEvent event) throws TransferCancelledException {
                stopIfCancelled(); // aborts a jar that is half downloaded
            }

            private void stopIfCancelled() throws TransferCancelledException {
                if (ctx.cancelled()) {
                    throw new TransferCancelledException("Setup cancelled");
                }
            }

            @Override
            public void transferFailed(TransferEvent event) {
                finished.incrementAndGet(); // e.g. a file looked for in a repository that does not have it
            }

            @Override
            public void transferSucceeded(TransferEvent event) {
                downloads.incrementAndGet();
                int n = finished.incrementAndGet();
                String name = event.getResource().getResourceName();
                String file = name.substring(name.lastIndexOf('/') + 1);
                if (name.endsWith(".jar") || name.endsWith(".aar")) {
                    ctx.installed(file, n, started.get());
                } else {
                    ctx.progress().step(file, n, started.get());
                }
            }
        });
        return new Resolver(system, session);
    }

    private static List<RemoteRepository> remotes(InstallContext ctx, List<String> extra) {
        List<RemoteRepository> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> urls = new ArrayList<>(ctx.settings().mavenRepositories());
        urls.addAll(extra);
        int i = 0;
        for (String url : urls) {
            String u = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            if (!u.toLowerCase(Locale.ROOT).startsWith("https://") || !seen.add(u)) {
                continue; // plain http repositories are blocked, like Maven 3.8+
            }
            String id = i == 0 ? "central" : "repo" + i;
            i++;
            out.add(new RemoteRepository.Builder(id, "default", u)
                    .setSnapshotPolicy(new RepositoryPolicy(false, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                    .build());
        }
        return out;
    }

    @Override
    public void install(ProjectFiles files, UnitSpec unit, InstallContext ctx) throws IOException {
        InstallContext.orbitDir(files.root());
        Path local = files.root().resolve(REPOSITORY);
        Files.createDirectories(local);
        AtomicInteger downloads = new AtomicInteger();
        if (ecosystem == Ecosystem.MAVEN) {
            installMaven(files, unit, ctx, local, downloads);
        } else {
            installGradle(files, unit, ctx, local, downloads);
        }
        ctx.progress().log(downloads.get() + " files downloaded into " + REPOSITORY);
    }

    private void installMaven(ProjectFiles files, UnitSpec unit, InstallContext ctx, Path local, AtomicInteger downloads)
            throws IOException {
        Workspace ws = workspace(files);
        Pom pom = ws.byPath().get(unit.key());
        if (pom == null || pom.group() == null || pom.artifact() == null || pom.version() == null) {
            throw new IOException("pom.xml could not be read (groupId, artifactId and version are needed)");
        }
        Resolver r = resolver(local, new ProjectWorkspaceReader(files, ws), ctx, downloads);
        List<RemoteRepository> repos = remotes(ctx, pom.repositories());
        ArtifactDescriptorResult descriptor;
        try {
            descriptor = r.system().readArtifactDescriptor(r.session(),
                    new ArtifactDescriptorRequest(new DefaultArtifact(pom.group(), pom.artifact(), "pom", pom.version()), repos, null));
        } catch (Exception e) {
            throw new IOException("The POM could not be read: " + rootMessage(e));
        }
        for (Exception e : descriptor.getExceptions()) {
            ctx.progress().log("warning: " + rootMessage(e));
        }
        List<RemoteRepository> all = new ArrayList<>(repos);
        for (RemoteRepository d : descriptor.getRepositories()) {
            if (d.getUrl().toLowerCase(Locale.ROOT).startsWith("https://") && all.stream().noneMatch(x -> x.getUrl().equals(d.getUrl()))) {
                all.add(d);
            }
        }
        List<Dependency> deps = descriptor.getDependencies().stream().filter(d -> !"system".equals(d.getScope())).toList();
        resolve(r, descriptor.getArtifact(), deps, descriptor.getManagedDependencies(), all, ws.ownGa(), ctx);
    }

    private void installGradle(ProjectFiles files, UnitSpec unit, InstallContext ctx, Path local, AtomicInteger downloads)
            throws IOException {
        GradleBuild build = withReactNativeVersions(files, unit.key(),
                JvmManifests.gradle(files.read(unit.key()), catalogFor(files, unit.key())));
        for (String ref : build.unresolved()) {
            ctx.fail(ref + ": not found in gradle/libs.versions.toml");
        }
        Resolver r = resolver(local, null, ctx, downloads);
        // Gradle settles a version conflict with the highest version; Maven's "nearest wins" may give up instead
        r.session().setDependencyGraphTransformer(new org.eclipse.aether.util.graph.transformer.ChainedDependencyGraphTransformer(
                new org.eclipse.aether.util.graph.transformer.ConflictResolver(new HighestVersionSelector(),
                        new org.eclipse.aether.util.graph.transformer.JavaScopeSelector(),
                        new org.eclipse.aether.util.graph.transformer.SimpleOptionalitySelector(),
                        new org.eclipse.aether.util.graph.transformer.JavaScopeDeriver()),
                new org.eclipse.aether.util.graph.transformer.JavaDependencyContextRefiner()));
        List<RemoteRepository> repos = remotes(ctx, build.repositories());
        List<Coordinate> boms = new ArrayList<>(build.platforms());
        if (build.springBootVersion() != null) {
            boms.add(new Coordinate("org.springframework.boot", "spring-boot-dependencies", build.springBootVersion(), "import", "pom", null));
        }
        Map<String, Dependency> managed = new LinkedHashMap<>();
        for (Coordinate bom : boms) {
            if (bom.version() == null) {
                ctx.fail(bom.ga() + ": platform without a version");
                continue;
            }
            try {
                ArtifactDescriptorResult d = r.system().readArtifactDescriptor(r.session(), new ArtifactDescriptorRequest(
                        new DefaultArtifact(bom.group(), bom.artifact(), "pom", bom.version()), repos, null));
                for (Dependency m : d.getManagedDependencies()) {
                    managed.putIfAbsent(m.getArtifact().getGroupId() + ":" + m.getArtifact().getArtifactId(), m);
                }
            } catch (Exception e) {
                ctx.fail(bom + ": " + rootMessage(e));
            }
        }
        Set<String> own = gradleProjectNames(files);
        List<Dependency> deps = new ArrayList<>();
        for (Coordinate c : build.dependencies()) {
            String version = c.version();
            if (version == null) {
                Dependency m = managed.get(c.ga());
                if (m == null) {
                    ctx.fail(c.ga() + ": no version (it is managed by a Gradle plugin or platform ORBIT cannot read)");
                    continue;
                }
                version = m.getArtifact().getVersion();
            }
            if (version.endsWith("+")) {
                version = "[" + version.substring(0, version.length() - 1) + ",)";
            }
            addGradleDependency(r, repos, local, new Coordinate(c.group(), c.artifact(), version, c.scope(), c.type(), c.classifier()),
                    deps, new LinkedHashSet<>(), ctx);
        }
        Set<String> ownGa = new LinkedHashSet<>();
        own.forEach(n -> ownGa.add("*:" + n));
        // Gradle fetches transitive Android libraries as .aar (or via their module metadata) even when a POM lists them
        // as plain dependencies; failed jars are looked at again the same way, a few rounds deep.
        Set<String> retried = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Dependency> round = deps;
        for (int pass = 0; pass < 5 && !round.isEmpty(); pass++) {
            List<Artifact> failed = resolve(r, null, round, new ArrayList<>(managed.values()), repos, ownGa, ctx, true);
            List<Dependency> next = new ArrayList<>();
            List<Artifact> unfixable = new ArrayList<>();
            for (Artifact a : failed) {
                String key = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion();
                if ("jar".equals(a.getExtension()) && a.getClassifier().isEmpty() && retried.add(key)) {
                    int before = next.size();
                    addGradleDependency(r, repos, local, new Coordinate(a.getGroupId(), a.getArtifactId(), a.getVersion(),
                            "compile", "jar", null), next, seen, ctx);
                    // still a plain jar: nothing else to try
                    if (next.size() > before && "jar".equals(next.get(next.size() - 1).getArtifact().getExtension())) {
                        next.remove(next.size() - 1);
                        unfixable.add(a);
                    }
                } else {
                    unfixable.add(a);
                }
            }
            for (Artifact a : unfixable) {
                ctx.fail(a + ": not found");
            }
            round = next;
        }
    }

    /** What the POM of a Gradle dependency says: its packaging, and whether the real files are in Gradle Module Metadata. */
    private record PomInfo(String packaging, boolean gradleMetadata) {
    }

    private static PomInfo pomInfo(Resolver r, List<RemoteRepository> repos, Path local, String g, String a, String v) {
        if (v.startsWith("[") || v.startsWith("(")) {
            return new PomInfo("jar", false);
        }
        try {
            r.system().resolveArtifact(r.session(), new org.eclipse.aether.resolution.ArtifactRequest(
                    new DefaultArtifact(g, a, "pom", v), repos, null));
            Path pom = local.resolve(g.replace('.', '/')).resolve(a).resolve(v).resolve(a + "-" + v + ".pom");
            if (!Files.isRegularFile(pom)) {
                return new PomInfo("jar", false);
            }
            String text = Files.readString(pom);
            String packaging = JvmManifests.pom(pom.toString(), text).map(Pom::packaging).orElse("jar");
            return new PomInfo(packaging, text.contains("published-with-gradle-metadata"));
        } catch (Exception e) {
            return new PomInfo("jar", false); // the resolve below reports the real problem
        }
    }

    /**
     * Adds a Gradle dependency the way Gradle would fetch it: {@code aar} for Android libraries
     * ({@code <packaging>aar</packaging>}), and - for libraries whose POM is only a stub of their Gradle Module
     * Metadata, such as React Native's {@code react-android} - the files and dependencies of the runtime variant.
     */
    private static void addGradleDependency(Resolver r, List<RemoteRepository> repos, Path local, Coordinate c,
                                            List<Dependency> deps, Set<String> seen, InstallContext ctx) {
        if (!seen.add(c.ga() + ":" + c.version()) || seen.size() > 500) {
            return;
        }
        String scope = "test".equals(c.scope()) ? "test" : "compile";
        String ext = c.type();
        if ("jar".equals(c.type()) && c.classifier() == null) {
            PomInfo info = pomInfo(r, repos, local, c.group(), c.artifact(), c.version());
            if (info.gradleMetadata() && "pom".equals(info.packaging())) {
                try {
                    gradleModule(r, repos, local, c, deps, seen, ctx);
                    return;
                } catch (IOException e) {
                    ctx.fail(c + ": " + e.getMessage());
                    return;
                }
            }
            ext = "aar".equals(info.packaging()) ? "aar" : "jar";
        }
        deps.add(new Dependency(new DefaultArtifact(c.group(), c.artifact(), c.classifier() == null ? "" : c.classifier(), ext,
                c.version()), scope));
    }

    /** Gradle Module Metadata ({@code .module}): downloads the runtime variant's files and queues its dependencies. */
    private static void gradleModule(Resolver r, List<RemoteRepository> repos, Path local, Coordinate c, List<Dependency> deps,
                                     Set<String> seen, InstallContext ctx) throws IOException {
        String rel = c.group().replace('.', '/') + "/" + c.artifact() + "/" + c.version() + "/";
        Path dir = local.resolve(rel);
        Path moduleFile = dir.resolve(c.artifact() + "-" + c.version() + ".module");
        String base = null;
        for (RemoteRepository repo : repos) {
            String url = repo.getUrl() + "/" + rel;
            try {
                ctx.downloader().file(url + c.artifact() + "-" + c.version() + ".module", "application/json", moduleFile);
                base = url;
                break;
            } catch (Downloader.NotFoundException e) {
                // next repository
            }
        }
        if (base == null) {
            throw new IOException("Gradle module metadata not found");
        }
        tools.jackson.databind.JsonNode module = InstallContext.json(Files.readString(moduleFile))
                .orElseThrow(() -> new IOException("invalid Gradle module metadata"));
        tools.jackson.databind.JsonNode variant = null;
        int best = -1;
        for (tools.jackson.databind.JsonNode v : module.path("variants")) {
            String usage = InstallContext.text(v.path("attributes"), "org.gradle.usage");
            String name = InstallContext.text(v, "name");
            int score = 0;
            if (usage != null && usage.endsWith("-runtime")) {
                score += 4;
            } else if (usage != null && usage.endsWith("-api")) {
                score += 2;
            } else {
                continue; // sources, javadoc, ...
            }
            if (name != null && name.toLowerCase(java.util.Locale.ROOT).startsWith("release")) {
                score += 2; // the variant an Android release build uses
            }
            if (score > best) {
                best = score;
                variant = v;
            }
        }
        if (variant == null) {
            throw new IOException("no runtime variant in the Gradle module metadata");
        }
        tools.jackson.databind.JsonNode at = variant.get("available-at");
        if (at != null && at.isObject()) {
            addGradleDependency(r, repos, local, new Coordinate(InstallContext.text(at, "group"), InstallContext.text(at, "module"),
                    InstallContext.text(at, "version"), c.scope(), "jar", null), deps, seen, ctx);
            return;
        }
        for (tools.jackson.databind.JsonNode f : variant.path("files")) {
            String url = InstallContext.text(f, "url");
            if (url == null || url.contains("/") || url.contains("\\")) {
                continue;
            }
            Path target = dir.resolve(url);
            if (Files.isRegularFile(target) && Downloader.hex(target, "SHA-256").equalsIgnoreCase(InstallContext.text(f, "sha256"))) {
                continue;
            }
            ctx.downloader().file(base + url, null, target);
            try {
                Downloader.verifySha256(target, InstallContext.text(f, "sha256"), url);
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
            ctx.progress().log("Downloaded " + url);
        }
        for (tools.jackson.databind.JsonNode d : variant.path("dependencies")) {
            tools.jackson.databind.JsonNode v = d.path("version");
            String version = null;
            for (String k : List.of("strictly", "requires", "prefer")) {
                if (version == null) {
                    version = InstallContext.text(v, k);
                }
            }
            String g = InstallContext.text(d, "group");
            String a = InstallContext.text(d, "module");
            if (g != null && a != null && version != null && !version.isBlank()) {
                if (version.endsWith("+")) {
                    version = "[" + version.substring(0, version.length() - 1) + ",)";
                }
                addGradleDependency(r, repos, local, new Coordinate(g, a, version, c.scope(), "jar", null), deps, seen, ctx);
            }
        }
    }

    private static void resolve(Resolver r, Artifact root, List<Dependency> deps, List<Dependency> managed,
                                List<RemoteRepository> repos, Set<String> ownGa, InstallContext ctx) throws IOException {
        for (Artifact a : resolve(r, root, deps, managed, repos, ownGa, ctx, false)) {
            ctx.fail(a + ": not found");
        }
    }

    /** Resolves and downloads; returns the artifacts that could not be downloaded (reported at once unless {@code collectFailures}). */
    private static List<Artifact> resolve(Resolver r, Artifact root, List<Dependency> deps, List<Dependency> managed,
                                          List<RemoteRepository> repos, Set<String> ownGa, InstallContext ctx,
                                          boolean collectFailures) throws IOException {
        List<Artifact> failed = new ArrayList<>();
        ctx.checkCancelled();
        ctx.progress().step("Resolving the dependency tree (parents, BOMs, transitive dependencies)", 0, 0);
        CollectRequest collect = new CollectRequest();
        if (root != null) {
            collect.setRootArtifact(root);
        }
        collect.setDependencies(deps);
        collect.setManagedDependencies(managed);
        collect.setRepositories(repos);
        DependencyFilter notOwn = (node, parents) -> {
            if (node.getArtifact() == null) {
                return true;
            }
            String ga = node.getArtifact().getGroupId() + ":" + node.getArtifact().getArtifactId();
            return !ownGa.contains(ga) && !ownGa.contains("*:" + node.getArtifact().getArtifactId());
        };
        DependencyRequest request = new DependencyRequest(collect, notOwn);
        DependencyResult result;
        try {
            result = r.system().resolveDependencies(r.session(), request);
        } catch (DependencyResolutionException e) {
            ctx.checkCancelled(); // transfers aborted by Cancel are not failures
            result = e.getResult();
            if (result == null) {
                throw new IOException(rootMessage(e));
            }
            if (result.getCollectExceptions() != null) {
                for (Exception ce : result.getCollectExceptions()) {
                    ctx.fail(rootMessage(ce));
                }
            }
        }
        ctx.checkCancelled();
        int ok = 0;
        for (ArtifactResult ar : result.getArtifactResults()) {
            if (ar.isResolved()) {
                ok++;
            } else if (collectFailures) {
                failed.add(ar.getRequest().getArtifact());
            } else {
                String what = ar.getRequest().getArtifact().toString();
                ctx.fail(what + ": " + (ar.getExceptions().isEmpty() ? "not found" : rootMessage(ar.getExceptions().get(0))));
            }
        }
        ctx.progress().log(ok + " artifacts resolved");
        return failed;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = Optional.ofNullable(t.getMessage()).orElse(t.getClass().getSimpleName());
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }
}
