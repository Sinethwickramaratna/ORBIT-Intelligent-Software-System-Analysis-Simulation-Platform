package com.orbit.backend.deps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Entry point of the dependency check and Setup Dependencies, independent of Spring (the REST service and the
 * {@link DependencyCli} both use it). Both report what they do to a {@link SetupProgress}: the plan (one step per
 * manifest group), when each step starts and ends, and every package installed or failed.
 */
public final class DependencyEngine {

    /** The outcome of a setup run. */
    public record SetupResult(DependencyReport before, DependencyReport after, List<String> failures,
                              List<InstallContext.ManifestChange> changes) {
    }

    private final DependencySettings settings;
    private final TargetPlatform platform;
    private final List<EcosystemSupport> supports = List.of(new NpmSupport(), new PythonSupport(), JvmSupport.maven(),
            JvmSupport.gradle(), new NuGetSupport(), new PubSupport());

    public DependencyEngine(DependencySettings settings, TargetPlatform platform) {
        this.settings = settings;
        this.platform = platform;
    }

    public TargetPlatform platform() {
        return platform;
    }

    public DependencyReport check(Path projectRoot) throws IOException {
        return check(projectRoot, SetupProgress.NONE);
    }

    /** The Dependency Check: reads the manifests and what is installed, reporting each manifest group as a step. */
    public DependencyReport check(Path projectRoot, SetupProgress progress) throws IOException {
        progress.step("Looking for dependency manifests", 0, 0);
        ProjectFiles files = ProjectFiles.scan(projectRoot);
        try (InstallContext ctx = new InstallContext(settings, platform, SetupProgress.NONE)) {
            return check(files, ctx, progress);
        }
    }

    private record Work(EcosystemSupport support, EcosystemSupport.UnitSpec unit) {
    }

    private List<Work> work(ProjectFiles files) {
        List<Work> out = new ArrayList<>();
        for (EcosystemSupport s : supports) {
            for (EcosystemSupport.UnitSpec u : s.units(files)) {
                out.add(new Work(s, u));
            }
        }
        return out;
    }

    private DependencyReport check(ProjectFiles files, InstallContext ctx, SetupProgress progress) {
        List<Work> all = work(files);
        progress.plan(all.stream().map(w -> "Check " + w.unit().title()).toList());
        List<DependencyReport.Unit> units = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Work w = all.get(i);
            progress.unit("Check " + w.unit().title(), i, all.size());
            DependencyReport.Unit result;
            try {
                result = w.support().check(files, w.unit(), ctx);
            } catch (RuntimeException e) {
                result = DependencyReport.Unit.of(w.support().ecosystem(), String.join(", ", w.unit().manifests()), null,
                        "Could not be checked: " + e.getMessage(), List.of(), false);
            }
            units.add(result);
            long missing = result.missingCount();
            long unused = result.items().stream().filter(x -> x.state() == DependencyState.UNUSED).count();
            long counted = result.items().size() - unused;
            String summary = (result.items().isEmpty() ? "no dependencies"
                    : missing == 0 && result.status() != DependencyReport.Status.MISSING ? counted + " installed"
                    : missing > 0 ? missing + " of " + counted + " missing" : "incomplete: " + result.message())
                    + (unused > 0 ? ", " + unused + " not used by the code" : "");
            progress.unitDone(i, result.status() != DependencyReport.Status.MISSING, summary);
            progress.log((result.status() == DependencyReport.Status.MISSING ? "Missing: " : "OK: ")
                    + w.unit().title() + " (" + summary + ")");
        }
        units.addAll(nativeUnits(files));
        return DependencyReport.of(units);
    }

    /** C / C++ build files: reported, nothing to download (their libraries come from the system). */
    private static List<DependencyReport.Unit> nativeUnits(ProjectFiles files) {
        List<DependencyReport.Unit> out = new ArrayList<>();
        for (String p : files.all()) {
            String n = ProjectFiles.fileName(p).toLowerCase(Locale.ROOT);
            if (n.equals("cmakelists.txt") || n.equals("makefile") || n.equals("gnumakefile") || n.equals("meson.build")) {
                out.add(new DependencyReport.Unit(Ecosystem.NATIVE, p, null, DependencyReport.Status.NO_DEPENDENCIES,
                        "C / C++ libraries are installed on the system (or with vcpkg / Conan); there is no package registry to download from",
                        List.of()));
            }
        }
        return out;
    }

    /** Checks, downloads everything that is missing, and checks again (manifests are never changed). */
    public SetupResult setup(Path projectRoot, SetupProgress progress) throws IOException {
        return setup(projectRoot, progress, false);
    }

    /**
     * Checks, downloads everything that is missing, and checks again. With {@code updateManifests}, a version pinned in
     * a requirements file that cannot be installed is replaced by a newer one that can, and the file is updated.
     */
    public SetupResult setup(Path projectRoot, SetupProgress progress, boolean updateManifests) throws IOException {
        return setup(projectRoot, progress, updateManifests, true);
    }

    /**
     * As {@link #setup(Path, SetupProgress, boolean)}; with {@code skipUnused} (the default) declared dependencies that
     * the code never uses are not downloaded, nor what only they need.
     */
    public SetupResult setup(Path projectRoot, SetupProgress progress, boolean updateManifests, boolean skipUnused) throws IOException {
        progress.step("Checking what is missing", 0, 0);
        ProjectFiles files = ProjectFiles.scan(projectRoot);
        try (InstallContext ctx = new InstallContext(settings, platform, progress)) {
            ctx.setUpdateManifests(updateManifests);
            ctx.setSkipUnused(skipUnused);
            progress.step("Reading the source code to see which dependencies it uses", 0, 0);
            DependencyReport before = check(files, ctx, SetupProgress.NONE);
            long unusedTotal = before.units().stream().flatMap(u -> u.items().stream())
                    .filter(i -> i.state() == DependencyState.UNUSED).count();
            if (unusedTotal > 0) {
                progress.log(unusedTotal + " declared dependencies are not used by the code: "
                        + (skipUnused ? "they are not downloaded" : "downloading them anyway (as asked)"));
            }
            List<Work> todo = new ArrayList<>();
            for (Work w : work(files)) {
                boolean missing = before.units().stream().anyMatch(r -> r.ecosystem() == w.unit().ecosystem()
                        && r.manifest().equals(String.join(", ", w.unit().manifests()))
                        && (r.status() == DependencyReport.Status.MISSING
                        || (!skipUnused && r.items().stream().anyMatch(i -> i.state() == DependencyState.UNUSED && i.installed() == null))));
                if (missing) {
                    todo.add(w);
                }
            }
            progress.plan(todo.stream().map(w -> w.unit().title()).toList());
            progress.log(todo.isEmpty() ? "Nothing is missing" : todo.size() + " of the project's manifests need dependencies: "
                    + before.missingCount() + " missing");
            for (int i = 0; i < todo.size(); i++) {
                ctx.checkCancelled();
                Work w = todo.get(i);
                progress.unit(w.unit().title(), i, todo.size());
                progress.log("== " + w.unit().title());
                int failuresBefore = ctx.failureCount();
                String error = null;
                try {
                    w.support().install(files, w.unit(), ctx);
                } catch (IOException | RuntimeException e) {
                    if (ctx.cancelled()) {
                        progress.unitDone(i, false, "cancelled");
                        throw new IOException("Setup cancelled", e);
                    }
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    ctx.fail(w.unit().title() + ": " + error);
                }
                int failed = ctx.failureCount() - failuresBefore;
                progress.unitDone(i, failed == 0, error != null ? "stopped: " + error
                        : failed == 0 ? "done" : failed + (failed == 1 ? " package failed" : " packages failed"));
            }
            ctx.checkCancelled();
            progress.step("Checking again", 0, 0);
            DependencyReport after = check(ProjectFiles.scan(projectRoot), ctx, SetupProgress.NONE);
            progress.log(after.status() == DependencyReport.Status.MISSING
                    ? "Finished: " + after.missingCount() + " dependencies are still missing" : "Finished: all dependencies are installed");
            return new SetupResult(before, after, ctx.failures(), ctx.changes());
        }
    }
}
