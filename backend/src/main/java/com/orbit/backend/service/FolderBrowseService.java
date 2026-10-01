package com.orbit.backend.service;

import com.orbit.backend.dto.response.FolderBrowseResponse;
import com.orbit.backend.dto.response.FolderBrowseResponse.Folder;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Backs the "choose a folder" window of Create New Project. Only folders are listed. With a mounted projects
 * root (Docker) browsing is confined to it; otherwise (backend on the user's computer) it starts in the home
 * folder and can reach the drives.
 */
@Service
@RequiredArgsConstructor
public class FolderBrowseService {

    static final int MAX_FOLDERS = 1000;
    private static final Pattern BAD_NAME = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    private final ProjectPathResolver resolver;

    public FolderBrowseResponse browse(String rawPath) {
        boolean confined = resolver.confined();
        boolean blank = rawPath == null || rawPath.isBlank();
        if (confined && blank && resolver.mounts().size() > 1) {
            return mountedFolders(); // "This PC": the drives/folders that were mounted
        }
        ProjectPathResolver.Resolved at;
        if (blank) {
            at = resolver.resolve(confined ? resolver.visibleRoot() : ProjectPathResolver.tidy(System.getProperty("user.home")));
        } else {
            at = resolver.resolve(rawPath);
        }
        Path dir = at.path();
        requireDirectory(dir);
        if (confined) {
            requireInsideRealRoot(dir);
        }

        List<Folder> folders = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (name.startsWith(".") || !Files.isDirectory(child)) {
                    continue;
                }
                if (confined && Files.isSymbolicLink(child)) {
                    continue; // could point outside the mounted root
                }
                if (folders.size() >= MAX_FOLDERS) {
                    truncated = true;
                    break;
                }
                folders.add(new Folder(name, join(at.location(), name)));
            }
        } catch (IOException | SecurityException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "That folder cannot be read");
        }
        folders.sort(Comparator.comparing(f -> f.name().toLowerCase()));

        return new FolderBrowseResponse(at.location(), parentOf(at), folders, shortcuts(), truncated);
    }

    private FolderBrowseResponse mountedFolders() {
        List<Folder> roots = resolver.mounts().stream()
                .map(m -> new Folder(m.display(), m.display()))
                .toList();
        return new FolderBrowseResponse("", null, roots, List.of(), false);
    }

    /** Creates {@code name} inside {@code parent}; returns the new folder's location. */
    public String createFolder(String parent, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..") || BAD_NAME.matcher(n).find() || n.endsWith(".")) {
            throw new ApiException(ErrorCode.PROJECT_LOCATION_INVALID,
                    "Folder name cannot be empty or contain \\ / : * ? \" < > |");
        }
        ProjectPathResolver.Resolved at = resolver.resolve(parent);
        requireDirectory(at.path());
        if (resolver.confined()) {
            requireInsideRealRoot(at.path());
        }
        Path target = at.path().resolve(n);
        try {
            Files.createDirectory(target);
        } catch (FileAlreadyExistsException e) {
            throw new ApiException(ErrorCode.PROJECT_ALREADY_EXISTS, "A file or folder named '" + n + "' already exists here");
        } catch (IOException | SecurityException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The folder could not be created - check permissions");
        }
        return join(at.location(), n);
    }

    private List<Folder> shortcuts() {
        List<Folder> list = new ArrayList<>();
        if (resolver.confined()) {
            if (resolver.mounts().size() > 1) {
                return list; // the top level already lists them
            }
            list.add(new Folder("Projects", resolver.visibleRoot()));
            return list;
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            list.add(new Folder("Home", ProjectPathResolver.tidy(home)));
        }
        File[] roots = File.listRoots();
        if (roots != null) {
            for (File r : roots) {
                String p = ProjectPathResolver.tidy(r.getPath());
                list.add(new Folder(p, p));
            }
        }
        return list;
    }

    private String parentOf(ProjectPathResolver.Resolved at) {
        if (resolver.confined()) {
            ProjectPathResolver.Mount m = resolver.mountOf(at.path());
            if (m != null && ProjectPathResolver.sameLocation(at.location(), m.host())) {
                return resolver.mounts().size() > 1 ? "" : null; // "" = the list of mounted folders
            }
            return up(at.location());
        }
        Path parent = at.path().getParent();
        return parent == null ? null : ProjectPathResolver.tidy(parent.toString());
    }

    private static String up(String location) {
        int i = location.lastIndexOf('/');
        if (i < 0) {
            return null;
        }
        String parent = location.substring(0, i);
        if (parent.isEmpty()) {
            return "/";                       // child of the Unix root
        }
        return parent.matches("^[A-Za-z]:$") ? parent + "/" : parent; // child of a drive root
    }

    private static String join(String base, String name) {
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private static void requireDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new ApiException(ErrorCode.PROJECT_LOCATION_NOT_DIRECTORY, "That folder does not exist");
        }
    }

    /** Symlinks inside the mount must not lead the chooser outside it. */
    private void requireInsideRealRoot(Path dir) {
        try {
            ProjectPathResolver.Mount m = resolver.mountOf(dir);
            if (m == null) {
                throw new ApiException(ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT, "That folder is outside the folders ORBIT can access");
            }
            Path rootReal = m.container().toRealPath();
            if (!dir.toRealPath().startsWith(rootReal)) {
                throw new ApiException(ErrorCode.PROJECT_LOCATION_OUTSIDE_ROOT, "That folder is outside the projects folder");
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "That folder cannot be read");
        }
    }
}
