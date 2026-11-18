package com.orbit.backend.deps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Entry point of the dependency check and Setup Dependencies, independent of Spring (the REST service and the
 * {@link DependencyCli} both use it).
 */
public final class DependencyEngine {

    /** The outcome of a setup run. */
    public record SetupResult(DependencyReport before, DependencyReport after, List<String> failures) {
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
        ProjectFiles files = ProjectFiles.scan(projectRoot);
        try (InstallContext ctx = new InstallContext(settings, platform, SetupProgress.NONE)) {
            return check(files, ctx);
        }
    }

    private DependencyReport check(ProjectFiles files, InstallContext ctx) {
        List<DependencyReport.Unit> units = new ArrayList<>();
        for (EcosystemSupport s : supports) {
            for (EcosystemSupport.UnitSpec u : s.units(files)) {
                try {
                    units.add(s.check(files, u, ctx));
                } catch (RuntimeException e) {
                    units.add(DependencyReport.Unit.of(s.ecosystem(), String.join(", ", u.manifests()), null,
                            "Could not be checked: " + e.getMessage(), List.of(), false));
                }
            }
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

    /** Checks, downloads everything that is missing, and checks again. */
    public SetupResult setup(Path projectRoot, SetupProgress progress) throws IOException {
        ProjectFiles files = ProjectFiles.scan(projectRoot);
        try (InstallContext ctx = new InstallContext(settings, platform, progress)) {
            DependencyReport before = check(files, ctx);
            List<Runnable> work = new ArrayList<>();
            List<String[]> titles = new ArrayList<>();
            for (EcosystemSupport s : supports) {
                for (EcosystemSupport.UnitSpec u : s.units(files)) {
                    boolean missing = before.units().stream().anyMatch(r -> r.ecosystem() == u.ecosystem()
                            && r.manifest().equals(String.join(", ", u.manifests())) && r.status() == DependencyReport.Status.MISSING);
                    if (missing) {
                        titles.add(new String[]{u.title()});
                        work.add(() -> {
                            try {
                                s.install(files, u, ctx);
                            } catch (IOException | RuntimeException e) {
                                ctx.fail(u.title() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                            }
                        });
                    }
                }
            }
            for (int i = 0; i < work.size(); i++) {
                ctx.checkCancelled();
                progress.unit(titles.get(i)[0], i, work.size());
                progress.log("== " + titles.get(i)[0]);
                work.get(i).run();
            }
            DependencyReport after = check(ProjectFiles.scan(projectRoot), ctx);
            return new SetupResult(before, after, ctx.failures());
        }
    }
}
