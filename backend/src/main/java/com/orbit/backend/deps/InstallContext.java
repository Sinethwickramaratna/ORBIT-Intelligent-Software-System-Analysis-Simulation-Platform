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

    public InstallContext(DependencySettings settings, TargetPlatform platform, SetupProgress progress) {
        this.settings = settings;
        this.platform = platform;
        this.downloader = new Downloader(settings.maxDownloadBytes());
        this.progress = progress == null ? SetupProgress.NONE : progress;
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
        failures.add(message);
        progress.log("FAILED: " + message);
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
        List<Future<Void>> futures = new ArrayList<>();
        for (var t : tasks) {
            futures.add(pool.submit(t));
        }
        IOException first = null;
        for (Future<Void> f : futures) {
            try {
                f.get();
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
