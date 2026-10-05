package com.orbit.backend.service;

import com.orbit.backend.dto.response.RepositoryInspectResponse;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.errors.NoRemoteRepositoryException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Looks at and clones PUBLIC git repositories over http(s). Uses JGit (pure Java) instead of the {@code git} program,
 * so it works the same inside the slim backend container (which has no git) as on the user's computer.
 * <p>
 * Authentication (SSH key / access token), shallow clones, submodules and LFS are deliberately not handled yet.
 */
@Slf4j
@Service
public class GitCloneService {

    /** Seconds without any network activity before a remote call is given up. */
    static final int REMOTE_TIMEOUT_SECONDS = 30;
    static final int CLONE_TIMEOUT_SECONDS = 300;

    /** The parts of a repository URL ORBIT cares about. */
    public record RepoRef(String url, String owner, String name) {
    }

    /** Accepts only {@code http(s)://host/owner/repo[.git]} without embedded credentials. */
    public RepoRef parse(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (url.isEmpty()) {
            throw new ApiException(ErrorCode.GIT_URL_INVALID, "Enter the repository URL");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new ApiException(ErrorCode.GIT_URL_INVALID, "That is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ApiException(ErrorCode.GIT_URL_INVALID,
                    "Only http(s) repository URLs are supported for now, e.g. https://github.com/user/project.git");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new ApiException(ErrorCode.GIT_URL_INVALID, "The URL has no host name");
        }
        if (uri.getUserInfo() != null) {
            throw new ApiException(ErrorCode.GIT_URL_INVALID,
                    "Remove the user name / password from the URL - authentication is not supported yet");
        }
        List<String> parts = new ArrayList<>();
        for (String seg : (uri.getPath() == null ? "" : uri.getPath()).split("/")) {
            if (!seg.isBlank()) {
                parts.add(seg);
            }
        }
        if (parts.isEmpty()) {
            throw new ApiException(ErrorCode.GIT_URL_INVALID, "The URL does not point to a repository");
        }
        String name = parts.get(parts.size() - 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            throw new ApiException(ErrorCode.GIT_URL_INVALID, "The URL does not point to a repository");
        }
        String owner = parts.size() >= 2 ? parts.get(parts.size() - 2) : uri.getHost();
        return new RepoRef(url, owner, name);
    }

    /** A repository name made safe to use as a folder name. */
    public static String folderName(String repoName) {
        String s = repoName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "repository" : s;
    }

    /** Reads the remote's branches without downloading anything. */
    public RepositoryInspectResponse inspect(String rawUrl) {
        return inspect(parse(rawUrl));
    }

    /** {@code repo} must come from {@link #parse(String)} - that is what restricts the URL to http(s). */
    public RepositoryInspectResponse inspect(RepoRef repo) {
        Collection<Ref> refs;
        try {
            refs = Git.lsRemoteRepository().setRemote(repo.url()).setHeads(true).setTags(false)
                    .setTimeout(REMOTE_TIMEOUT_SECONDS).call();
        } catch (GitAPIException | RuntimeException e) {
            throw unavailable(e);
        }

        List<String> branches = new ArrayList<>();
        Ref head = null;
        for (Ref ref : refs) {
            if (Constants.HEAD.equals(ref.getName())) {
                head = ref;
            } else if (ref.getName().startsWith(Constants.R_HEADS)) {
                branches.add(ref.getName().substring(Constants.R_HEADS.length()));
            }
        }
        if (branches.isEmpty()) {
            throw new ApiException(ErrorCode.GIT_REPOSITORY_UNAVAILABLE, "The repository has no branches (it is empty)");
        }
        branches.sort(String.CASE_INSENSITIVE_ORDER);
        String defaultBranch = defaultBranch(head, refs, branches);
        branches.remove(defaultBranch);
        branches.add(0, defaultBranch); // the default branch first

        return new RepositoryInspectResponse(true, "Repository found", repo.name(), repo.owner(), defaultBranch,
                branches.size(), "Public", List.copyOf(branches));
    }

    private static String defaultBranch(Ref head, Collection<Ref> refs, List<String> branches) {
        if (head != null) {
            if (head.isSymbolic() && head.getTarget().getName().startsWith(Constants.R_HEADS)) {
                return head.getTarget().getName().substring(Constants.R_HEADS.length());
            }
            ObjectId id = head.getObjectId();
            if (id != null) { // server did not say where HEAD points: the branch at the same commit, main/master first
                List<String> same = new ArrayList<>();
                for (Ref r : refs) {
                    if (r.getName().startsWith(Constants.R_HEADS) && id.equals(r.getObjectId())) {
                        same.add(r.getName().substring(Constants.R_HEADS.length()));
                    }
                }
                for (String preferred : new String[]{"main", "master"}) {
                    if (same.contains(preferred)) {
                        return preferred;
                    }
                }
                if (!same.isEmpty()) {
                    return same.get(0);
                }
            }
        }
        for (String preferred : new String[]{"main", "master"}) {
            if (branches.contains(preferred)) {
                return preferred;
            }
        }
        return branches.get(0);
    }

    /**
     * Clones {@code branch} (history of all branches, like {@code git clone -b}) into {@code target}, which must be
     * missing or empty. Blocks until the clone is complete. On failure nothing is left behind in {@code target}.
     */
    public void cloneRepository(RepoRef repo, String branch, Path target) {
        if (branch == null || branch.isBlank() || !Repository.isValidRefName(Constants.R_HEADS + branch.trim())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "That is not a valid branch name");
        }
        boolean existed = Files.isDirectory(target);
        try {
            try (Git ignored = Git.cloneRepository()
                    .setURI(repo.url())
                    .setDirectory(target.toFile())
                    .setBranch(Constants.R_HEADS + branch.trim())
                    .setTimeout(CLONE_TIMEOUT_SECONDS)
                    .call()) {
                log.info("Cloned {} ({}) into {}", repo.url(), branch, target);
            }
        } catch (GitAPIException | RuntimeException e) {
            cleanup(target, existed);
            log.warn("Clone of {} failed: {}", repo.url(), e.toString());
            if (e instanceof ApiException api) {
                throw api;
            }
            throw new ApiException(ErrorCode.GIT_CLONE_FAILED, "Clone failed: " + reason(e));
        }
    }

    /** Removes a failed clone: the whole folder if we created it, otherwise just what was put into it. */
    public static void cleanup(Path target, boolean keepFolder) {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                if (keepFolder && p.equals(target)) {
                    return;
                }
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    p.toFile().setWritable(true); // read-only pack files on Windows
                    p.toFile().delete();
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private ApiException unavailable(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NoRemoteRepositoryException) {
                return new ApiException(ErrorCode.GIT_REPOSITORY_UNAVAILABLE, "Repository not found - check the URL");
            }
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (e instanceof InvalidRemoteException && msg.contains("not found")) {
            return new ApiException(ErrorCode.GIT_REPOSITORY_UNAVAILABLE, "Repository not found - check the URL");
        }
        if (e instanceof TransportException && (msg.contains("authoriz") || msg.contains("authenticat")
                || msg.contains("403") || msg.contains("401"))) {
            return new ApiException(ErrorCode.GIT_REPOSITORY_UNAVAILABLE,
                    "Repository not found, or it is private. Only public repositories can be cloned for now.");
        }
        log.warn("ls-remote failed: {}", e.toString());
        return new ApiException(ErrorCode.GIT_REPOSITORY_UNAVAILABLE,
                "Could not reach the repository: " + reason(e));
    }

    private static String reason(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
