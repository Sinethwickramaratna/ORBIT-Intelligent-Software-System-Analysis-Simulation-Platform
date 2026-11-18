package com.orbit.backend.service;

import com.orbit.backend.config.DependencyProperties;
import com.orbit.backend.deps.DependencyEngine;
import com.orbit.backend.deps.DependencyReport;
import com.orbit.backend.deps.DependencySettings;
import com.orbit.backend.deps.SetupProgress;
import com.orbit.backend.deps.TargetPlatform;
import com.orbit.backend.dto.response.DependencyCheckResponse;
import com.orbit.backend.dto.response.DependencySetupProgress;
import com.orbit.backend.entity.Project;
import com.orbit.backend.entity.ProjectDependencyCheck;
import com.orbit.backend.entity.ProjectDependencyItem;
import com.orbit.backend.entity.ProjectDependencyUnit;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.repository.ProjectDependencyCheckRepository;
import com.orbit.backend.repository.ProjectDependencyItemRepository;
import com.orbit.backend.repository.ProjectDependencyUnitRepository;
import com.orbit.backend.repository.ProjectRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Dependency check and Setup Dependencies of a project (Architecture Reconstruction, step 1). The check is quick (it
 * only reads the project folder) and is stored; the newest check is what the Dependency Check button shows. Setup
 * runs in the background - the UI polls its progress - and stores a fresh check when it ends.
 */
@Service
public class ProjectDependencyService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDependencyService.class);
    private static final int LOG_LINES = 200;

    private final ProjectRepository projectRepository;
    private final ProjectDependencyCheckRepository checkRepository;
    private final ProjectDependencyUnitRepository unitRepository;
    private final ProjectDependencyItemRepository itemRepository;
    private final ProjectPathResolver pathResolver;
    private final DependencyProperties properties;
    private final DependencySettings settings;
    private final TransactionTemplate transaction;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "orbit-dependency-setup");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, Job> checks = new ConcurrentHashMap<>();

    public ProjectDependencyService(ProjectRepository projectRepository, ProjectDependencyCheckRepository checkRepository,
                                    ProjectDependencyUnitRepository unitRepository, ProjectDependencyItemRepository itemRepository,
                                    ProjectPathResolver pathResolver, DependencyProperties properties,
                                    PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.checkRepository = checkRepository;
        this.unitRepository = unitRepository;
        this.itemRepository = itemRepository;
        this.pathResolver = pathResolver;
        this.properties = properties == null
                ? new DependencyProperties(null, null, null, null, null, null, null, null, null, null, null) : properties;
        this.settings = settingsFrom(this.properties);
        this.transaction = new TransactionTemplate(transactionManager);
    }

    private static DependencySettings settingsFrom(DependencyProperties p) {
        DependencySettings d = DependencySettings.defaults();
        return new DependencySettings(
                blank(p.npmRegistry()) ? d.npmRegistry() : p.npmRegistry(),
                blank(p.pypiUrl()) ? d.pypiUrl() : p.pypiUrl(),
                p.mavenRepositories() == null || p.mavenRepositories().isEmpty() ? d.mavenRepositories() : p.mavenRepositories(),
                blank(p.nugetUrl()) ? d.nugetFlatContainer() : p.nugetUrl(),
                blank(p.pubUrl()) ? d.pubUrl() : p.pubUrl(),
                Boolean.FALSE.equals(p.userCaches()) ? null : d.userHome(),
                p.maxDownloadMb() == null || p.maxDownloadMb() <= 0 ? d.maxDownloadBytes() : p.maxDownloadMb() * 1024L * 1024L,
                p.parallelDownloads() == null || p.parallelDownloads() <= 0 ? d.parallelDownloads() : p.parallelDownloads());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * The user's computer. In Docker the project location is the HOST path the user typed ({@code E:\...} = Windows),
     * otherwise the backend runs on that computer itself. {@code orbit.dependencies.host-os/host-arch} override both.
     */
    TargetPlatform platformFor(Project project) {
        TargetPlatform p = pathResolver.confined() ? TargetPlatform.fromHostPath(project.getLocation()) : TargetPlatform.current();
        TargetPlatform.Os os = blank(properties.hostOs()) ? p.os() : TargetPlatform.osOf(properties.hostOs());
        TargetPlatform.Arch arch = blank(properties.hostArch()) ? p.arch() : TargetPlatform.archOf(properties.hostArch());
        String py = blank(properties.pythonVersion()) ? p.pythonVersion() : properties.pythonVersion().trim();
        return new TargetPlatform(os, arch, py);
    }

    private static String describe(TargetPlatform p) {
        String os = switch (p.os()) {
            case WINDOWS -> "Windows";
            case MAC -> "macOS";
            case LINUX -> "Linux";
        };
        return os + " " + (p.arch() == TargetPlatform.Arch.ARM64 ? "arm64" : "x64");
    }

    // ------------------------------------------------------------------------------------------ check

    /** The Dependency Check button: checks the project folder now and stores the result. */
    public DependencyCheckResponse check(UUID userId, UUID projectId) {
        Project project = find(userId, projectId);
        Job job = new Job("CHECK");
        job.phase = "Checking dependencies";
        checks.put(project.getProjectId(), job);
        try {
            DependencyCheckResponse r = checkProject(project, job);
            job.state = "MISSING".equals(r.status()) ? "FAILED" : "SUCCEEDED";
            job.phase = "MISSING".equals(r.status()) ? r.missingDependencies() + " dependencies are missing"
                    : "All dependencies are installed";
            return r;
        } catch (RuntimeException e) {
            job.state = "FAILED";
            job.phase = "The check failed";
            throw e;
        } finally {
            job.percent = 100;
            job.finishedAt = Instant.now();
        }
    }

    /** How far the running (or the last) Dependency Check is; the UI polls it while the check request is open. */
    public DependencySetupProgress checkProgress(UUID userId, UUID projectId) {
        Job job = checks.get(find(userId, projectId).getProjectId());
        return job == null ? idle("CHECK") : job.snapshot();
    }

    /** The stored check of the project, or empty when it was never checked. */
    public Optional<DependencyCheckResponse> latest(UUID userId, UUID projectId) {
        Project project = find(userId, projectId);
        return checkRepository.findFirstByProjectIdOrderByCheckedAtDesc(project.getProjectId()).map(this::load);
    }

    /** Automatic check right after a project was added; a failure never fails the creation. */
    public void checkQuietly(Project project) {
        try {
            checkProject(project);
        } catch (RuntimeException e) {
            log.warn("Automatic dependency check of project {} failed; it can be run with the Dependency Check button: {}",
                    project.getProjectId(), e.toString());
        }
    }

    DependencyCheckResponse checkProject(Project project) {
        return checkProject(project, SetupProgress.NONE);
    }

    DependencyCheckResponse checkProject(Project project, SetupProgress progress) {
        Path folder = folder(project);
        TargetPlatform platform = platformFor(project);
        DependencyReport report;
        try {
            report = new DependencyEngine(settings, platform).check(folder, progress);
        } catch (IOException | RuntimeException e) {
            log.warn("Dependency check of {} failed: {}", folder, e.toString());
            throw new ApiException(ErrorCode.DEPENDENCY_CHECK_FAILED);
        }
        return store(project.getProjectId(), report, describe(platform));
    }

    private Path folder(Project project) {
        try {
            Path folder = pathResolver.resolve(project.getLocation()).path().toRealPath();
            if (!Files.isDirectory(folder)) {
                throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
            }
            return folder;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
        }
    }

    private DependencyCheckResponse store(UUID projectId, DependencyReport report, String platform) {
        UUID checkId = transaction.execute(status -> {
            ProjectDependencyCheck check = checkRepository.saveAndFlush(new ProjectDependencyCheck(projectId,
                    report.status().name(), (int) report.totalCount(), (int) report.missingCount(), platform));
            List<ProjectDependencyUnit> units = new ArrayList<>();
            List<ProjectDependencyItem> items = new ArrayList<>();
            int up = 0;
            for (DependencyReport.Unit u : report.units()) {
                ProjectDependencyUnit unit = new ProjectDependencyUnit(check.getCheckId(), up++, u.ecosystem().id(),
                        cut(u.manifest(), 2000), cut(u.installLocation(), 1000), u.status().name(), cut(u.message(), 1000));
                units.add(unit);
                int ip = 0;
                for (DependencyReport.Item i : u.items()) {
                    items.add(new ProjectDependencyItem(unit.getUnitId(), ip++, cut(i.name(), 500), cut(i.required(), 500),
                            cut(i.installed(), 255), cut(i.scope(), 50), i.state().name(), cut(i.note(), 1000)));
                }
            }
            unitRepository.saveAllAndFlush(units);
            itemRepository.saveAll(items);
            checkRepository.deleteOthers(projectId, check.getCheckId());
            return check.getCheckId();
        });
        return load(checkRepository.findById(checkId).orElseThrow());
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private DependencyCheckResponse load(ProjectDependencyCheck check) {
        List<ProjectDependencyUnit> units = unitRepository.findByCheckIdOrderByPositionAsc(check.getCheckId());
        Map<UUID, List<DependencyCheckResponse.Dependency>> byUnit = new LinkedHashMap<>();
        if (!units.isEmpty()) {
            for (ProjectDependencyItem i : itemRepository.findByUnitIdInOrderByPositionAsc(units.stream().map(ProjectDependencyUnit::getUnitId).toList())) {
                byUnit.computeIfAbsent(i.getUnitId(), k -> new ArrayList<>()).add(new DependencyCheckResponse.Dependency(
                        i.getDependencyName(), i.getRequiredVersion(), i.getInstalledVersion(), i.getScope(), i.getState(), i.getNote()));
            }
        }
        List<DependencyCheckResponse.Unit> out = new ArrayList<>();
        for (ProjectDependencyUnit u : units) {
            List<DependencyCheckResponse.Dependency> deps = byUnit.getOrDefault(u.getUnitId(), List.of());
            int missing = (int) deps.stream().filter(d -> needsSetup(d.state())).count();
            String label;
            try {
                label = com.orbit.backend.deps.Ecosystem.fromId(u.getEcosystem()).label();
            } catch (IllegalArgumentException e) {
                label = u.getEcosystem();
            }
            out.add(new DependencyCheckResponse.Unit(u.getEcosystem(), label, u.getManifestPath(), u.getInstallLocation(),
                    u.getStatus(), u.getMessage(), deps.size(), missing, deps));
        }
        return new DependencyCheckResponse(check.getCheckId(), check.getProjectId(), check.getCheckedAt(), check.getStatus(),
                check.getTotalCount(), check.getMissingCount(), check.getTargetPlatform(), out);
    }

    private static boolean needsSetup(String state) {
        try {
            return com.orbit.backend.deps.DependencyState.valueOf(state).needsSetup();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------ setup

    /**
     * A running or finished check / setup. Fields are written by the worker thread (and the download threads) and read
     * by the progress requests.
     */
    private static final class Job implements SetupProgress {
        final String kind;
        volatile String state = "RUNNING";
        volatile String phase = "Starting";
        volatile String current;
        volatile int unitIndex;
        volatile int unitCount;
        volatile int done;
        volatile int total;
        volatile int percent;
        volatile boolean cancelled;
        volatile Instant finishedAt;
        volatile List<String> failures = List.of();
        final java.util.concurrent.atomic.AtomicInteger installed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        final Instant startedAt = Instant.now();
        final Deque<String> lines = new ArrayDeque<>();
        final List<String[]> steps = new ArrayList<>(); // title, state, summary
        final List<DependencySetupProgress.Change> changes = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile Future<?> future;

        Job(String kind) {
            this.kind = kind;
        }

        @Override
        public void plan(List<String> titles) {
            synchronized (steps) {
                steps.clear();
                titles.forEach(t -> steps.add(new String[]{t, "PENDING", null}));
            }
            unitCount = titles.size();
            if (titles.isEmpty()) {
                percent = Math.max(percent, 95);
            }
        }

        @Override
        public void unit(String title, int index, int count) {
            unitIndex = index;
            unitCount = count;
            done = 0;
            total = 0;
            phase = title;
            current = null;
            synchronized (steps) {
                if (index < steps.size()) {
                    steps.get(index)[1] = "RUNNING";
                }
            }
            bump();
        }

        @Override
        public void unitDone(int index, boolean ok, String summary) {
            synchronized (steps) {
                if (index < steps.size()) {
                    steps.get(index)[1] = ok ? "DONE" : "FAILED";
                    steps.get(index)[2] = summary;
                }
            }
            done = 1;
            total = 1;
            bump();
        }

        @Override
        public void step(String text, int d, int t) {
            done = d;
            total = t;
            current = text;
            bump();
        }

        @Override
        public void item(String what, boolean ok) {
            (ok ? installed : failed).incrementAndGet();
        }

        @Override
        public void manifestChanged(String file, String dependency, String from, String to, String reason) {
            changes.add(new DependencySetupProgress.Change(file, dependency, from, to, reason));
        }

        @Override
        public void log(String line) {
            synchronized (lines) {
                lines.addLast(line);
                while (lines.size() > LOG_LINES) {
                    lines.removeFirst();
                }
            }
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        private final List<Runnable> onCancel = new ArrayList<>();

        @Override
        public void onCancel(Runnable action) {
            boolean now;
            synchronized (onCancel) {
                onCancel.add(action);
                now = cancelled;
            }
            if (now) {
                action.run();
            }
        }

        /** Marks the job cancelled and aborts what is in flight (downloads, download threads, child processes). */
        void cancel() {
            List<Runnable> actions;
            synchronized (onCancel) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                actions = List.copyOf(onCancel);
            }
            log("Cancel requested - stopping the downloads in progress");
            for (Runnable r : actions) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log.debug("Cancel action failed: {}", e.toString());
                }
            }
        }

        private void bump() {
            if (unitCount <= 0) {
                return;
            }
            double inUnit = total <= 0 ? 0 : Math.min(1.0, (double) done / total);
            int p = (int) Math.floor((unitIndex + inUnit) * 100.0 / unitCount);
            percent = Math.max(percent, Math.min(99, Math.max(1, p)));
        }

        DependencySetupProgress snapshot() {
            List<String> copy;
            synchronized (lines) {
                copy = List.copyOf(lines);
            }
            List<DependencySetupProgress.Step> stepCopy = new ArrayList<>();
            synchronized (steps) {
                steps.forEach(x -> stepCopy.add(new DependencySetupProgress.Step(x[0], x[1], x[2])));
            }
            List<DependencySetupProgress.Change> changeCopy;
            synchronized (changes) {
                changeCopy = List.copyOf(changes);
            }
            return new DependencySetupProgress(kind, state, phase, current, percent, unitIndex, unitCount, stepCopy,
                    installed.get(), failed.get(), changeCopy, startedAt, finishedAt, failures, copy);
        }
    }

    private static DependencySetupProgress idle(String kind) {
        return new DependencySetupProgress(kind, "IDLE", null, null, 0, 0, 0, List.of(), 0, 0, List.of(), null, null, List.of(), List.of());
    }

    /** Setup Dependencies: starts downloading what the check found missing; answers at once (poll the progress). */
    public DependencySetupProgress startSetup(UUID userId, UUID projectId) {
        return startSetup(userId, projectId, true);
    }

    /**
     * Setup Dependencies. With {@code updateManifests} a version pinned in a requirements file that cannot be installed
     * on the user's computer is replaced by the newest working one (same major version first) and the file is updated
     * (the original is kept as {@code <file>.orbit-backup}).
     */
    public DependencySetupProgress startSetup(UUID userId, UUID projectId, boolean updateManifests) {
        return startSetup(userId, projectId, updateManifests, true);
    }

    /** As above; with {@code skipUnused} declared dependencies the source code never uses are not downloaded. */
    public DependencySetupProgress startSetup(UUID userId, UUID projectId, boolean updateManifests, boolean skipUnused) {
        Project project = find(userId, projectId);
        Path folder = folder(project);
        TargetPlatform platform = platformFor(project);
        Job job = new Job("SETUP");
        Job running = jobs.compute(project.getProjectId(), (id, old) -> old != null && "RUNNING".equals(old.state) ? old : job);
        if (running != job) {
            throw new ApiException(ErrorCode.DEPENDENCY_SETUP_RUNNING);
        }
        job.phase = "Checking what is missing";
        job.log("Target: " + describe(platform) + " - files are saved in " + project.getLocation());
        job.log(updateManifests ? "Versions that cannot be installed may be replaced by newer ones in requirements files"
                : "Project files are not changed (version updates are switched off)");
        job.log(skipUnused ? "Only the dependencies the source code uses are downloaded"
                : "Every declared dependency is downloaded, also those the code does not use");
        job.future = executor.submit(() -> runSetup(project.getProjectId(), folder, platform, job, updateManifests, skipUnused));
        return job.snapshot();
    }

    private void runSetup(UUID projectId, Path folder, TargetPlatform platform, Job job, boolean updateManifests,
                          boolean skipUnused) {
        try {
            DependencyEngine.SetupResult r = new DependencyEngine(settings, platform).setup(folder, job, updateManifests, skipUnused);
            job.failures = r.failures();
            job.phase = "Checking again";
            store(projectId, r.after(), describe(platform));
            boolean ok = r.after().status() != DependencyReport.Status.MISSING;
            job.state = job.cancelled ? "CANCELLED" : ok ? "SUCCEEDED" : "FAILED";
            job.phase = job.cancelled ? "Cancelled" : ok ? "All dependencies are installed"
                    : r.after().missingCount() + " dependencies are still missing";
            log.info("Dependency setup of project {} finished: {} ({} failures)", projectId, r.after().status(), r.failures().size());
        } catch (IOException | RuntimeException e) {
            job.state = job.cancelled ? "CANCELLED" : "FAILED";
            job.phase = job.cancelled ? "Cancelled" : "Setup failed: " + e.getMessage();
            if (job.cancelled) {
                job.log("Setup cancelled - the packages downloaded so far stay in the project");
            }
            List<String> f = new ArrayList<>(job.failures);
            if (!job.cancelled) {
                f.add(String.valueOf(e.getMessage()));
            }
            job.failures = f;
            log.warn("Dependency setup of project {} failed: {}", projectId, e.toString());
            try {
                store(projectId, new DependencyEngine(settings, platform).check(folder), describe(platform));
            } catch (IOException | RuntimeException ignored) {
                // the old stored check stays
            }
        } finally {
            job.percent = 100;
            job.finishedAt = Instant.now();
        }
    }

    public DependencySetupProgress progress(UUID userId, UUID projectId) {
        Job job = jobs.get(find(userId, projectId).getProjectId());
        return job == null ? idle("SETUP") : job.snapshot();
    }

    /** Stops a running setup after the downloads in flight (what is complete stays installed). */
    public DependencySetupProgress cancel(UUID userId, UUID projectId) {
        Job job = jobs.get(find(userId, projectId).getProjectId());
        if (job == null) {
            return idle("SETUP");
        }
        if ("RUNNING".equals(job.state)) {
            job.phase = "Cancelling...";
            job.cancel();
        }
        return job.snapshot();
    }

    @PreDestroy
    void shutdown() {
        jobs.values().forEach(Job::cancel);
        executor.shutdownNow();
    }

    private Project find(UUID userId, UUID projectId) {
        return projectRepository.findByProjectIdAndUser_UserId(projectId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROJECT_NOT_FOUND));
    }
}
