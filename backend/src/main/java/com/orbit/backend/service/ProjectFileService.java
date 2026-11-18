package com.orbit.backend.service;

import com.orbit.backend.dto.response.FileContentResponse;
import com.orbit.backend.dto.response.ProjectTreeResponse;
import com.orbit.backend.dto.response.ProjectTreeResponse.Entry;
import com.orbit.backend.dto.response.ProjectTreeResponse.Kind;
import com.orbit.backend.exception.ApiException;
import com.orbit.backend.exception.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads a project folder one level at a time (like Solution Explorer) and edits it from the Explorer: opens and saves
 * text files, creates, renames and deletes files and folders. Nothing ever leaves the project root.
 */
@Service
public class ProjectFileService {

    /** More entries than this in one folder are cut off and reported as {@code truncated}. */
    static final int MAX_ENTRIES = 2000;
    private static final String GIT_DIR = ".git";
    /** Files larger than this are not opened in the editor (and cannot be saved through it). */
    static final long MAX_EDIT_BYTES = 5L * 1024 * 1024;
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String INVALID_NAME_CHARS = "\\/:*?\"<>|";
    private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    public ProjectTreeResponse list(Path projectRoot, String relativePath) {
        Path rootReal = realRoot(projectRoot);
        String rel = cleanRelative(relativePath);
        Path target = rel.isEmpty() ? rootReal : rootReal.resolve(rel).normalize();
        Path targetReal;
        try {
            targetReal = target.toRealPath(); // follows symlinks, so an escaping link is caught below
        } catch (NoSuchFileException e) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "That folder does not exist in the project");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE);
        }
        if (!targetReal.startsWith(rootReal)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
        }
        if (!Files.isDirectory(targetReal)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "That path is not a folder");
        }

        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(targetReal)) {
            for (Path child : stream) {
                String name = child.getFileName().toString();
                if (name.equals(GIT_DIR)) {
                    continue; // git's own bookkeeping is hidden, like in Visual Studio
                }
                if (entries.size() >= MAX_ENTRIES) {
                    truncated = true;
                    break;
                }
                entries.add(entryFor(child, name, rel));
            }
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE);
        }

        entries.sort(Comparator
                .comparing((Entry e) -> e.kind() == Kind.FOLDER ? 0 : 1)
                .thenComparing(e -> e.name().toLowerCase(Locale.ROOT))
                .thenComparing(Entry::name));
        return new ProjectTreeResponse(rel, entries, truncated);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Editing: read / save text files, create / rename / delete files and folders (never outside the project root)
    // ------------------------------------------------------------------------------------------------------------

    /** Opens a text file for the editor. Binary, non-UTF-8 and oversized files are refused, so a save cannot corrupt them. */
    public FileContentResponse readFile(Path projectRoot, String relativePath) {
        Path rootReal = realRoot(projectRoot);
        String rel = requireEntryPath(relativePath);
        Path file = existingFile(rootReal, rel);
        try {
            long size = Files.size(file);
            if (size > MAX_EDIT_BYTES) {
                throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                        "This file is " + (size / (1024 * 1024)) + " MB; files up to " + (MAX_EDIT_BYTES / (1024 * 1024)) + " MB can be edited here");
            }
            byte[] bytes = Files.readAllBytes(file);
            boolean bom = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF;
            int offset = bom ? 3 : 0;
            for (int i = offset; i < bytes.length; i++) {
                if (bytes[i] == 0) {
                    throw new ApiException(ErrorCode.FILE_NOT_TEXT, "This is a binary file and cannot be edited here");
                }
            }
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new ApiException(ErrorCode.FILE_NOT_TEXT);
            }
            return new FileContentResponse(rel, file.getFileName().toString(), content, size,
                    Files.getLastModifiedTime(file).toMillis(), bom, content.contains("\r\n") ? "CRLF" : "LF");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_OPERATION_FAILED, "The file could not be read");
        }
    }

    /** Saves a text file (UTF-8). The file must already exist; new files are made with {@link #create}. */
    public FileContentResponse saveFile(Path projectRoot, String relativePath, String content, boolean bom, Long expectedLastModified) {
        Path rootReal = realRoot(projectRoot);
        String rel = requireEntryPath(relativePath);
        Path file = existingFile(rootReal, rel);
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        if (body.length > MAX_EDIT_BYTES) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE);
        }
        try {
            if (expectedLastModified != null && Files.getLastModifiedTime(file).toMillis() != expectedLastModified) {
                throw new ApiException(ErrorCode.FILE_CHANGED_ON_DISK);
            }
            try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                if (bom) {
                    out.write(UTF8_BOM);
                }
                out.write(body);
            }
            return new FileContentResponse(rel, file.getFileName().toString(), null, Files.size(file),
                    Files.getLastModifiedTime(file).toMillis(), bom, content.contains("\r\n") ? "CRLF" : "LF");
        } catch (AccessDeniedException e) {
            throw new ApiException(ErrorCode.FILE_OPERATION_FAILED, "The file is read-only or locked by another program");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_OPERATION_FAILED, "The file could not be saved");
        }
    }

    /** Creates an empty file or folder named {@code name} inside {@code parentPath} ("" = the project root). */
    public Entry create(Path projectRoot, String parentPath, String name, Kind kind) {
        Path rootReal = realRoot(projectRoot);
        String parentRel = cleanRelative(parentPath);
        rejectGitSegments(parentRel);
        String cleanName = validName(name);
        Path parent = existingFolder(rootReal, parentRel);
        Path target = parent.resolve(cleanName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(ErrorCode.FILE_ALREADY_EXISTS, "'" + cleanName + "' already exists in this folder");
        }
        try {
            if (kind == Kind.FOLDER) {
                Files.createDirectory(target);
            } else {
                Files.createFile(target);
            }
        } catch (FileAlreadyExistsException e) {
            throw new ApiException(ErrorCode.FILE_ALREADY_EXISTS, "'" + cleanName + "' already exists in this folder");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_OPERATION_FAILED, "'" + cleanName + "' could not be created");
        }
        return entryFor(target, cleanName, parentRel);
    }

    /** Renames a file or folder in place (it stays in the same folder). A change of letter case only is allowed. */
    public Entry rename(Path projectRoot, String relativePath, String newName) {
        Path rootReal = realRoot(projectRoot);
        String rel = requireEntryPath(relativePath);
        String cleanName = validName(newName);
        Path source = existingEntry(rootReal, rel);
        String parentRel = parentOf(rel);
        String oldName = source.getFileName().toString();
        if (oldName.equals(cleanName)) {
            return entryFor(source, oldName, parentRel);
        }
        Path target = source.resolveSibling(cleanName);
        try {
            boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
            boolean caseOnly = exists && oldName.equalsIgnoreCase(cleanName) && Files.isSameFile(source, target);
            if (exists && !caseOnly) {
                throw new ApiException(ErrorCode.FILE_ALREADY_EXISTS, "'" + cleanName + "' already exists in this folder");
            }
            if (caseOnly) {
                // case-insensitive file systems (Windows, macOS): go through a temporary name so the case really changes
                Path temp = source.resolveSibling(".orbit-rename-" + System.nanoTime());
                Files.move(source, temp);
                Files.move(temp, target);
            } else {
                Files.move(source, target);
            }
        } catch (ApiException e) {
            throw e;
        } catch (FileAlreadyExistsException e) {
            throw new ApiException(ErrorCode.FILE_ALREADY_EXISTS, "'" + cleanName + "' already exists in this folder");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_OPERATION_FAILED,
                    "'" + oldName + "' could not be renamed (is it open in another program?)");
        }
        return entryFor(target, cleanName, parentRel);
    }

    /** Deletes a file, or a folder with everything in it. Symbolic links are removed, never followed. */
    public void delete(Path projectRoot, String relativePath) {
        Path rootReal = realRoot(projectRoot);
        String rel = requireEntryPath(relativePath);
        Path target = existingEntry(rootReal, rel);
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    deleteOne(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    deleteOne(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_OPERATION_FAILED,
                    "'" + target.getFileName() + "' could not be deleted completely (is something in it open in another program?)");
        }
    }

    /** Deletes one path; read-only files (e.g. git objects on Windows) are made writable first. */
    private static void deleteOne(Path p) throws IOException {
        try {
            Files.delete(p);
        } catch (AccessDeniedException e) {
            DosFileAttributeView dos = Files.getFileAttributeView(p, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (dos == null) {
                throw e;
            }
            dos.setReadOnly(false);
            Files.delete(p);
        }
    }

    /** A non-empty relative path of an entry (the project root itself cannot be opened, renamed or deleted). */
    private static String requireEntryPath(String relativePath) {
        String rel = cleanRelative(relativePath);
        if (rel.isEmpty()) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "The project folder itself cannot be changed here");
        }
        rejectGitSegments(rel);
        return rel;
    }

    /** Git's own folder is hidden in the Explorer and must never be edited through it. */
    private static void rejectGitSegments(String rel) {
        for (String part : rel.split("/")) {
            if (part.equalsIgnoreCase(GIT_DIR)) {
                throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "Git's own .git folder cannot be changed here");
            }
        }
    }

    private static String parentOf(String rel) {
        int slash = rel.lastIndexOf('/');
        return slash < 0 ? "" : rel.substring(0, slash);
    }

    /** The real folder for {@code rel}; it must exist, be a folder and lie inside the project. */
    private static Path existingFolder(Path rootReal, String rel) {
        Path real;
        try {
            real = (rel.isEmpty() ? rootReal : rootReal.resolve(rel)).toRealPath();
        } catch (NoSuchFileException e) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND, "That folder does not exist in the project");
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE);
        }
        if (!real.startsWith(rootReal)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
        }
        if (!Files.isDirectory(real)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "That path is not a folder");
        }
        return real;
    }

    /**
     * The entry itself (not followed if it is a symbolic link) inside its real parent folder, so renaming or deleting
     * a link touches the link and never what it points to.
     */
    private static Path existingEntry(Path rootReal, String rel) {
        Path parent = existingFolder(rootReal, parentOf(rel));
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        Path entry = parent.resolve(name);
        if (!Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND);
        }
        return entry;
    }

    /** A regular file to read or write; a link is followed but must still end inside the project. */
    private static Path existingFile(Path rootReal, String rel) {
        Path entry = existingEntry(rootReal, rel);
        Path real;
        try {
            real = entry.toRealPath();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.FILE_NOT_FOUND);
        }
        if (!real.startsWith(rootReal)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
        }
        if (!Files.isRegularFile(real)) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID, "That path is a folder, not a file");
        }
        return real;
    }

    /** A single file or folder name that is valid on Windows, macOS and Linux alike. */
    static String validName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.FILE_NAME_INVALID, "A name is required");
        }
        String name = raw.strip();
        if (name.length() > 255) {
            throw new ApiException(ErrorCode.FILE_NAME_INVALID, "The name is longer than 255 characters");
        }
        if (name.equals(".") || name.equals("..")) {
            throw new ApiException(ErrorCode.FILE_NAME_INVALID, "'" + name + "' is not a valid name");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || INVALID_NAME_CHARS.indexOf(c) >= 0) {
                throw new ApiException(ErrorCode.FILE_NAME_INVALID, "A name cannot contain any of  \\ / : * ? \" < > |");
            }
        }
        if (name.endsWith(".")) {
            throw new ApiException(ErrorCode.FILE_NAME_INVALID, "A name cannot end with a dot");
        }
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (WINDOWS_RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.FILE_NAME_INVALID, "'" + stem + "' is a reserved name on Windows");
        }
        if (name.equalsIgnoreCase(GIT_DIR)) {
            throw new ApiException(ErrorCode.FILE_NAME_INVALID, "'.git' is reserved for Git");
        }
        return name;
    }

    /** Throws PROJECT_FOLDER_UNAVAILABLE when the root is missing; returns the real (symlink-free) root. */
    private Path realRoot(Path projectRoot) {
        try {
            Path real = projectRoot.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
            }
            return real;
        } catch (IOException e) {
            throw new ApiException(ErrorCode.PROJECT_FOLDER_UNAVAILABLE, "The project folder no longer exists");
        }
    }

    private Entry entryFor(Path child, String name, String parentRel) {
        String path = parentRel.isEmpty() ? name : parentRel + "/" + name;
        // NOFOLLOW: a symlink to a folder is shown as a plain entry and is never expanded
        if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            return new Entry(name, path, Kind.FOLDER, null, hasVisibleChildren(child));
        }
        Long size = null;
        try {
            size = Files.size(child);
        } catch (IOException ignored) {
            // unreadable size: show the file without one
        }
        return new Entry(name, path, Kind.FILE, size, false);
    }

    private boolean hasVisibleChildren(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!p.getFileName().toString().equals(GIT_DIR)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            // unreadable folder: treat as empty
        }
        return false;
    }

    /** Relative path from the client: '/'-separated, no '..', not absolute, no control characters. */
    static String cleanRelative(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().replace('\\', '/');
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
            }
        }
        if (s.startsWith("/") || s.matches("^[A-Za-z]:.*")) {
            throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
        }
        List<String> parts = new ArrayList<>();
        for (String part : s.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                throw new ApiException(ErrorCode.PROJECT_PATH_INVALID);
            }
            parts.add(part);
        }
        return String.join("/", parts);
    }
}
