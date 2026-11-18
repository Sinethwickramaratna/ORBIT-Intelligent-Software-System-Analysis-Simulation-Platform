package com.orbit.backend.deps;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.toml.TomlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Everything the checkers and installers share during one check or setup run. */
public final class InstallContext implements AutoCloseable {

    /** Where ORBIT keeps the downloads that have no conventional folder inside the project. */
    public static final String ORBIT_DIR = ".orbit";

    public static final ObjectMapper JSON = JsonMapper.builder().build();
    public static final ObjectMapper TOML = TomlMapper.builder().build();

    private final DependencySettings settings;
    private final TargetPlatform platform;
    private final Downloader downloader;
    private final SetupProgress progress;
    private final List<String> failures = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> done = Collections.synchronizedSet(new HashSet<>());
    private ExecutorService pool;
    private volatile boolean updateManifests;
    private final List<ManifestChange> changes = Collections.synchronizedList(new ArrayList<>());

    /** A dependency version ORBIT changed in a manifest of the project. */
    public record ManifestChange(String file, String dependency, String from, String to, String reason) {
    }

    public InstallContext(DependencySettings settings, TargetPlatform platform, SetupProgress progress) {
        this.settings = settings;
        this.platform = platform;
        this.progress = progress == null ? SetupProgress.NONE : progress;
        this.downloader = new Downloader(settings.maxDownloadBytes(), this.progress::cancelled);
        // Cancel takes effect at once: open connections are closed and the download threads are stopped
        this.progress.onCancel(this::abort);
    }

    private void abort() {
        downloader.abortAll();
        Runnable[] hooks;
        synchronized (cancelHooks) {
            hooks = cancelHooks.toArray(Runnable[]::new);
        }
        for (Runnable r : hooks) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        synchronized (this) {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    private final List<Runnable> cancelHooks = new ArrayList<>();

    /** Runs {@code action} when the user cancels (e.g. stop a child process); removed with the returned handle. */
    public AutoCloseable onCancel(Runnable action) {
        synchronized (cancelHooks) {
            cancelHooks.add(action);
        }
        if (progress.cancelled()) {
            action.run();
        }
        return () -> {
            synchronized (cancelHooks) {
                cancelHooks.remove(action);
            }
        };
    }

    public boolean cancelled() {
        return progress.cancelled();
    }

    /** Code usage of the project (imports and config mentions), scanned once per run. */
    private volatile CodeUsage usage;

    public CodeUsage usage(Path projectRoot) {
        CodeUsage u = usage;
        if (u == null) {
            synchronized (this) {
                if (usage == null) {
                    usage = CodeUsage.scan(projectRoot);
                }
                u = usage;
            }
        }
        return u;
    }

    private volatile boolean skipUnused = true;

    /** Whether setup leaves out declared dependencies the code does not use (default true). */
    public boolean skipUnused() {
        return skipUnused;
    }

    public void setSkipUnused(boolean skip) {
        this.skipUnused = skip;
    }

    public DependencySettings settings() {
        return settings;
    }

    public TargetPlatform platform() {
        return platform;
    }

    public Downloader downloader() {
        return downloader;
    }

    public SetupProgress progress() {
        return progress;
    }

    /** Records a package that could not be set up (the setup goes on with the others). */
    public void fail(String message) {
        if (progress.cancelled()) {
            return; // what was cut off by Cancel is not a failure
        }
        failures.add(message);
        progress.item(message, false);
        progress.log("FAILED: " + message);
    }

    /** A package was downloaded and saved: moves the bar and writes the activity log. */
    public void installed(String what, int done, int total) {
        progress.item(what, true);
        progress.log("Installed " + what);
        progress.step(what, done, total);
    }

    /** Whether setup may change a version in the project's requirements files when the requested one cannot be installed. */
    public boolean updateManifests() {
        return updateManifests;
    }

    public void setUpdateManifests(boolean allowed) {
        this.updateManifests = allowed;
    }

    public void manifestChanged(String file, String dependency, String from, String to, String reason) {
        changes.add(new ManifestChange(file, dependency, from, to, reason));
        progress.manifestChanged(file, dependency, from, to, reason);
        progress.log("Updated " + file + ": " + dependency + " " + (from == null || from.isBlank() ? "(unpinned)" : from) + " -> " + to);
    }

    public List<ManifestChange> changes() {
        synchronized (changes) {
            return List.copyOf(changes);
        }
    }

    public int failureCount() {
        return failures.size();
    }

    public List<String> failures() {
        synchronized (failures) {
            return List.copyOf(failures);
        }
    }

    /** True the first time {@code key} is claimed in this run (an install root shared by several units runs once). */
    public boolean claim(String key) {
        return done.add(key);
    }

    public void checkCancelled() throws IOException {
        if (progress.cancelled() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Setup cancelled");
        }
    }

    /** Runs {@code tasks} on the download pool and waits for all; the first task failure is rethrown. */
    public void runAll(List<java.util.concurrent.Callable<Void>> tasks) throws IOException {
        if (tasks.isEmpty()) {
            return;
        }
        synchronized (this) {
            if (pool == null) {
                pool = Executors.newFixedThreadPool(Math.max(1, settings.parallelDownloads()), r -> {
                    Thread t = new Thread(r, "orbit-download");
                    t.setDaemon(true);
                    return t;
                });
            }
        }
        checkCancelled();
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (var t : tasks) {
                futures.add(pool.submit(t));
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new IOException("Setup cancelled"); // the pool was stopped by Cancel
        }
        IOException first = null;
        for (Future<Void> f : futures) {
            try {
                // waits in slices: tasks that never started (the pool was stopped by Cancel) would block a plain get()
                while (true) {
                    try {
                        f.get(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        break;
                    } catch (java.util.concurrent.TimeoutException timeout) {
                        if (progress.cancelled()) {
                            futures.forEach(x -> x.cancel(true));
                            throw new IOException("Setup cancelled");
                        }
                    }
                }
            } catch (java.util.concurrent.CancellationException e) {
                throw new IOException("Setup cancelled");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                futures.forEach(x -> x.cancel(true));
                throw new IOException("Setup cancelled");
            } catch (ExecutionException e) {
                if (first == null) {
                    first = e.getCause() instanceof IOException io ? io : new IOException(e.getCause().getMessage(), e.getCause());
                }
            }
        }
        checkCancelled();
        if (first != null) {
            throw first;
        }
    }

    /** Creates {@code <project>/.orbit} with a .gitignore so ORBIT's downloads are never committed. */
    public static Path orbitDir(Path projectRoot) throws IOException {
        Path dir = projectRoot.resolve(ORBIT_DIR);
        Files.createDirectories(dir);
        Path ignore = dir.resolve(".gitignore");
        if (!Files.exists(ignore)) {
            Files.writeString(ignore, "# Dependencies downloaded by ORBIT (Setup Dependencies) - not part of the project\n*\n");
        }
        return dir;
    }

    // ------------------------------------------------------------------------------------------ parsing helpers

    public static Optional<JsonNode> json(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readTree(text));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static Optional<JsonNode> toml(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(TOML.readTree(text));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Text of a JSON field, or null. */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asString();
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
