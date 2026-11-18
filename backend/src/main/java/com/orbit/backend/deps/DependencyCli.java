package com.orbit.backend.deps;

import java.nio.file.Path;

/**
 * Command-line access to the dependency check and setup, for diagnostics (also inside the Docker image):
 * <pre>
 * java -cp app.jar -Dloader.main=com.orbit.backend.deps.DependencyCli \
 *      org.springframework.boot.loader.launch.PropertiesLauncher check|setup &lt;project folder&gt; [windows|mac|linux]
 * </pre>
 * Prints the report and ends with {@code check: <STATUS>} or {@code setup: <STATUS>}. Exit code 0 when everything is
 * installed, 1 when something is still missing, 2 on wrong usage.
 */
public final class DependencyCli {

    private DependencyCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !(args[0].equals("check") || args[0].equals("setup"))) {
            System.err.println("usage: check|setup <project folder> [windows|mac|linux] [--no-update] [--all]");
            System.exit(2);
            return;
        }
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        TargetPlatform platform = TargetPlatform.current();
        if (args.length > 2) {
            platform = new TargetPlatform(TargetPlatform.osOf(args[2]), platform.arch(), platform.pythonVersion());
        }
        DependencyEngine engine = new DependencyEngine(DependencySettings.defaults(), platform);
        DependencyReport report;
        if (args[0].equals("check")) {
            report = engine.check(root);
        } else {
            DependencyEngine.SetupResult r = engine.setup(root, new SetupProgress() {
                @Override
                public void unit(String title, int index, int total) {
                    System.out.println("[" + (index + 1) + "/" + total + "] " + title);
                }

                @Override
                public void step(String text, int done, int total) {
                }

                @Override
                public void log(String line) {
                    System.out.println("  " + line);
                }
            }, !java.util.Arrays.asList(args).contains("--no-update"), !java.util.Arrays.asList(args).contains("--all"));
            report = r.after();
            r.changes().forEach(c -> System.out.println("  changed " + c.file() + ": " + c.dependency() + " " + c.from() + " -> " + c.to()));
            r.failures().forEach(f -> System.out.println("  failed: " + f));
        }
        print(report);
        System.out.println(args[0] + ": " + report.status());
        System.exit(report.status() == DependencyReport.Status.MISSING ? 1 : 0);
    }

    static void print(DependencyReport report) {
        for (DependencyReport.Unit u : report.units()) {
            System.out.println(u.ecosystem().label() + "  " + u.manifest() + "  -> " + u.status()
                    + (u.message() == null ? "" : "  (" + u.message() + ")"));
            for (DependencyReport.Item i : u.items()) {
                System.out.println("    " + i.state() + "  " + i.name() + " " + (i.required() == null ? "" : i.required())
                        + (i.installed() == null ? "" : "  [installed " + i.installed() + "]"));
            }
        }
    }
}
