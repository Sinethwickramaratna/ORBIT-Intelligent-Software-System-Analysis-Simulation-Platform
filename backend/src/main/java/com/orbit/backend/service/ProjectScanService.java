package com.orbit.backend.service;

import com.orbit.backend.config.ScanProperties;
import com.orbit.backend.dto.response.ProjectScanResponse;
import com.orbit.backend.dto.response.ProjectScanResponse.BuildSystemFinding;
import com.orbit.backend.dto.response.ProjectScanResponse.ConfigurationFileFinding;
import com.orbit.backend.dto.response.ProjectScanResponse.Counts;
import com.orbit.backend.entity.ConfigurationFile;
import com.orbit.backend.entity.ProjectConfigurationDetail;
import com.orbit.backend.entity.BuildFileType;
import com.orbit.backend.entity.BuildSystem;
import com.orbit.backend.entity.ProjectBuildDetail;
import com.orbit.backend.entity.ProjectBuildEvidence;
import com.orbit.backend.entity.Language;
import com.orbit.backend.entity.LanguageExtension;
import com.orbit.backend.entity.Project;
import com.orbit.backend.entity.ProjectLanguageDetail;
import com.orbit.backend.entity.ProjectScan;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.repository.BuildFileTypeRepository;
import com.orbit.backend.repository.ConfigurationFileRepository;
import com.orbit.backend.repository.ProjectConfigurationDetailRepository;
import com.orbit.backend.repository.LanguageExtensionRepository;
import com.orbit.backend.repository.ProjectBuildDetailRepository;
import com.orbit.backend.repository.ProjectBuildEvidenceRepository;
import com.orbit.backend.repository.ProjectLanguageDetailRepository;
import com.orbit.backend.repository.ProjectRepository;
import com.orbit.backend.repository.ProjectScanRepository;
import com.orbit.backend.scan.BuildSystemScanner;
import com.orbit.backend.scan.ConfigFileScanner;
import com.orbit.backend.scan.FileCounter;
import com.orbit.backend.scan.LanguageScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Language detection for a project: walks the project folder, counts source-code lines per language and stores the
 * result (one {@code project_scan} row plus one {@code project_language_detail} row per language found). Every scan
 * is kept. The folder is read first and the rows are written afterwards in one short transaction, so a slow scan
 * never holds a database transaction open.
 */
@Service
public class ProjectScanService {

    private static final Logger log = LoggerFactory.getLogger(ProjectScanService.class);

    private final ProjectRepository projectRepository;
    private final LanguageExtensionRepository extensionRepository;
    private final ProjectScanRepository scanRepository;
    private final ProjectLanguageDetailRepository detailRepository;
    private final BuildFileTypeRepository buildFileTypeRepository;
    private final ProjectBuildDetailRepository buildDetailRepository;
    private final ProjectBuildEvidenceRepository buildEvidenceRepository;
    private final ConfigurationFileRepository configurationFileRepository;
    private final ProjectConfigurationDetailRepository configurationDetailRepository;
    private final ScanProgressTracker progressTracker;
    private final ProjectPathResolver pathResolver;
    private final ScanProperties scanProperties;
    private final TransactionTemplate transaction;

    public ProjectScanService(ProjectRepository projectRepository,
                              LanguageExtensionRepository extensionRepository,
                              ProjectScanRepository scanRepository,
                              ProjectLanguageDetailRepository detailRepository,
                              BuildFileTypeRepository buildFileTypeRepository,
                              ProjectBuildDetailRepository buildDetailRepository,
                              ProjectBuildEvidenceRepository buildEvidenceRepository,
                              ConfigurationFileRepository configurationFileRepository,
                              ProjectConfigurationDetailRepository configurationDetailRepository,
                              ScanProgressTracker progressTracker,
                              ProjectPathResolver pathResolver,
                              ScanProperties scanProperties,
                              PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.extensionRepository = extensionRepository;
        this.scanRepository = scanRepository;
        this.detailRepository = detailRepository;
        this.buildFileTypeRepository = buildFileTypeRepository;
        this.buildDetailRepository = buildDetailRepository;
        this.buildEvidenceRepository = buildEvidenceRepository;
        this.configurationFileRepository = configurationFileRepository;
        this.configurationDetailRepository = configurationDetailRepository;
        this.progressTracker = progressTracker;
        this.pathResolver = pathResolver;
        this.scanProperties = scanProperties;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** The Scan button: scans the project now and returns the new result. */
    public ProjectScanResponse scan(UUID userId, UUID projectId) {
        return scanProject(find(userId, projectId));
    }

    /** The newest stored scan of the project, or empty when it has never been scanned. */
    public Optional<ProjectScanResponse> latest(UUID userId, UUID projectId) {
        Project project = find(userId, projectId);
        return scanRepository.findFirstByProject_ProjectIdOrderByScannedAtDesc(project.getProjectId())
                .map(scan -> {
                    List<Counts> counts = detailRepository.findByScanIdWithLanguage(scan.getScanId()).stream()
                            .map(d -> new Counts(d.getLanguage().getLanguageName(), d.getNumberOfFiles(), d.getLinesOfCode()))
                            .toList();
                    return ProjectScanResponse.of(scan.getScanId(), project.getProjectId(), scan.getScannedAt(), counts,
                            storedBuildSystems(scan.getScanId()), storedConfigurationFiles(scan.getScanId()));
                });
    }

    /** How far the running scan of the project is (idle when none is running). */
    public ScanProgressTracker.Progress progress(UUID userId, UUID projectId) {
        return progressTracker.current(find(userId, projectId).getProjectId());
    }

    private List<ConfigurationFileFinding> storedConfigurationFiles(UUID scanId) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (ProjectConfigurationDetail d : configurationDetailRepository.findByScanIdWithFile(scanId)) {
            byName.computeIfAbsent(d.getConfigurationFile().getFileName(), k -> new ArrayList<>()).add(d.getFilePath());
        }
        List<ConfigurationFileFinding> result = new ArrayList<>();
        byName.forEach((name, paths) -> result.add(new ConfigurationFileFinding(name, sortedEvidence(paths))));
        return result;
    }

    private List<BuildSystemFinding> storedBuildSystems(UUID scanId) {
        Map<UUID, List<String>> evidence = new LinkedHashMap<>();
        for (ProjectBuildEvidence e : buildEvidenceRepository.findByScanId(scanId)) {
            evidence.computeIfAbsent(e.getId().getBuildSystemId(), k -> new ArrayList<>()).add(e.getId().getFilePath());
        }
        return buildDetailRepository.findByScanIdWithBuildSystem(scanId).stream()
                .map(d -> new BuildSystemFinding(d.getBuildSystem().getName(),
                        sortedEvidence(evidence.getOrDefault(d.getBuildSystem().getBuildSystemId(), List.of()))))
                .toList();
    }

    /** Shallowest path first (root build file before module build files), then alphabetical. */
    private static List<String> sortedEvidence(List<String> paths) {
        return paths.stream()
                .sorted(java.util.Comparator.comparingInt((String p) -> (int) p.chars().filter(c -> c == '/').count())
                        .thenComparing(java.util.Comparator.naturalOrder()))
                .toList();
    }

    /**
     * Automatic scan right after a project was created or cloned. The project is already saved, so a failing scan
     * must never fail the creation: the problem is logged and the user can press Scan later.
     */
    public void scanQuietly(Project project) {
        try {
            scanProject(project);
        } catch (RuntimeException e) {
            log.warn("Automatic scan of project {} failed; it can be scanned again with the Scan button: {}",
                    project.getProjectId(), e.toString());
        }
    }

    ProjectScanResponse scanProject(Project project) {
        Path folder;
        try {
            // the real folder: the project location itself may be a link, and links are never followed while scanning
            folder = pathResolver.resolve(project.getLocation()).path().toRealPath();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
        }
        if (!Files.isDirectory(folder)) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
        }

        // language name -> entity, and extension -> language name, both straight from the database
        Map<String, Language> languages = new LinkedHashMap<>();
        Map<String, String> languageByExtension = new LinkedHashMap<>();
        for (LanguageExtension e : extensionRepository.findAllWithLanguage()) {
            languages.putIfAbsent(e.getLanguage().getLanguageName(), e.getLanguage());
            languageByExtension.putIfAbsent(LanguageScanner.normalizeExtension(e.getExtensionType()),
                    e.getLanguage().getLanguageName());
        }
        Set<String> ignored = new HashSet<>(scanProperties.ignoredFoldersOrDefault());

        // build system name -> entity, and evidence file type -> build system name, both straight from the database
        Map<String, BuildSystem> buildSystems = new LinkedHashMap<>();
        Map<String, String> buildSystemByFileType = new LinkedHashMap<>();
        for (BuildFileType f : buildFileTypeRepository.findAllWithBuildSystem()) {
            buildSystems.putIfAbsent(f.getBuildSystem().getName(), f.getBuildSystem());
            buildSystemByFileType.putIfAbsent(f.getFileType(), f.getBuildSystem().getName());
        }

        // configuration file name (as stored) -> entity, straight from the database
        Map<String, ConfigurationFile> configurationFiles = new LinkedHashMap<>();
        for (ConfigurationFile c : configurationFileRepository.findAllByOrderByFileNameAsc()) {
            configurationFiles.putIfAbsent(c.getFileName(), c);
        }

        ScanProgressTracker.Run run = progressTracker.start(project.getProjectId());
        LanguageScanner.Result result;
        BuildSystemScanner.Result builds;
        ConfigFileScanner.Result configs;
        try {
            // 1. count the files (so the progress bar knows its end), 2. languages, 3. build systems (after the languages)
            run.stage(ScanProgressTracker.COUNTING, 0, 0, 1);
            int fileCount = FileCounter.count(folder, ignored);
            run.stage(ScanProgressTracker.LANGUAGES, 0, 70, fileCount);
            result = new LanguageScanner(languageByExtension, ignored, scanProperties.maxFileBytesOrDefault())
                    .scan(folder, run::fileVisited);
            run.stage(ScanProgressTracker.BUILD_SYSTEMS, 70, 15, fileCount);
            builds = new BuildSystemScanner(buildSystemByFileType, ignored).scan(folder, run::fileVisited);
            run.stage(ScanProgressTracker.CONFIGURATION, 85, 15, fileCount);
            configs = new ConfigFileScanner(configurationFiles.keySet(), ignored).scan(folder, run::fileVisited);
        } catch (IOException | RuntimeException e) {
            log.warn("Scan of {} failed: {}", folder, e.toString());
            throw new ApiException(ErrorCode.PROJECT_SCAN_FAILED);
        } finally {
            // the stored result is written below; the bar is only a hint and ends with the file work
            run.finish();
        }

        ProjectScanResponse response = transaction.execute(status -> {
            ProjectScan scan = scanRepository.save(new ProjectScan(projectRepository.getReferenceById(project.getProjectId())));
            List<ProjectLanguageDetail> details = new ArrayList<>();
            List<Counts> counts = new ArrayList<>();
            result.byLanguage().forEach((name, stat) -> {
                details.add(new ProjectLanguageDetail(scan, languages.get(name), stat.lines(), stat.files()));
                counts.add(new Counts(name, stat.files(), stat.lines()));
            });
            detailRepository.saveAll(details);

            List<ProjectBuildDetail> buildDetails = new ArrayList<>();
            List<ProjectBuildEvidence> evidence = new ArrayList<>();
            List<BuildSystemFinding> findings = new ArrayList<>();
            builds.evidenceByBuildSystem().forEach((name, paths) -> {
                BuildSystem system = buildSystems.get(name);
                buildDetails.add(new ProjectBuildDetail(scan, system));
                paths.forEach(p -> evidence.add(new ProjectBuildEvidence(scan.getScanId(), system.getBuildSystemId(), p)));
                findings.add(new BuildSystemFinding(name, paths));
            });
            buildDetailRepository.saveAll(buildDetails);
            buildEvidenceRepository.saveAll(evidence);

            List<ProjectConfigurationDetail> configDetails = new ArrayList<>();
            List<ConfigurationFileFinding> configFindings = new ArrayList<>();
            configs.locationsByFileName().forEach((name, paths) -> {
                ConfigurationFile file = configurationFiles.get(name);
                paths.forEach(p -> configDetails.add(new ProjectConfigurationDetail(scan, file, p)));
                configFindings.add(new ConfigurationFileFinding(name, paths));
            });
            configurationDetailRepository.saveAll(configDetails);
            return ProjectScanResponse.of(scan.getScanId(), project.getProjectId(), scan.getScannedAt(), counts, findings,
                    configFindings);
        });
        log.info("Scanned project {} ({}): {} files, {} lines, {} skipped", project.getProjectId(), folder,
                response.totalFiles(), response.totalLines(), result.skippedFiles());
        return response;
    }

    private Project find(UUID userId, UUID projectId) {
        // someone else's project looks exactly like a missing one
        return projectRepository.findByProjectIdAndUser_UserId(projectId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.PROJECT_NOT_FOUND));
    }
}
