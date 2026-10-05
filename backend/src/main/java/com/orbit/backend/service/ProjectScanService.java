package com.orbit.backend.service;

import com.orbit.backend.config.ScanProperties;
import com.orbit.backend.dto.response.ProjectScanResponse;
import com.orbit.backend.dto.response.ProjectScanResponse.Counts;
import com.orbit.backend.entity.Language;
import com.orbit.backend.entity.LanguageExtension;
import com.orbit.backend.entity.Project;
import com.orbit.backend.entity.ProjectLanguageDetail;
import com.orbit.backend.entity.ProjectScan;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import com.orbit.backend.repository.LanguageExtensionRepository;
import com.orbit.backend.repository.ProjectLanguageDetailRepository;
import com.orbit.backend.repository.ProjectRepository;
import com.orbit.backend.repository.ProjectScanRepository;
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
    private final ProjectPathResolver pathResolver;
    private final ScanProperties scanProperties;
    private final TransactionTemplate transaction;

    public ProjectScanService(ProjectRepository projectRepository,
                              LanguageExtensionRepository extensionRepository,
                              ProjectScanRepository scanRepository,
                              ProjectLanguageDetailRepository detailRepository,
                              ProjectPathResolver pathResolver,
                              ScanProperties scanProperties,
                              PlatformTransactionManager transactionManager) {
        this.projectRepository = projectRepository;
        this.extensionRepository = extensionRepository;
        this.scanRepository = scanRepository;
        this.detailRepository = detailRepository;
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
                    return ProjectScanResponse.of(scan.getScanId(), project.getProjectId(), scan.getScannedAt(), counts);
                });
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

        LanguageScanner.Result result;
        try {
            result = new LanguageScanner(languageByExtension, ignored, scanProperties.maxFileBytesOrDefault()).scan(folder);
        } catch (IOException | RuntimeException e) {
            log.warn("Scan of {} failed: {}", folder, e.toString());
            throw new ApiException(ErrorCode.PROJECT_SCAN_FAILED);
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
            return ProjectScanResponse.of(scan.getScanId(), project.getProjectId(), scan.getScannedAt(), counts);
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
