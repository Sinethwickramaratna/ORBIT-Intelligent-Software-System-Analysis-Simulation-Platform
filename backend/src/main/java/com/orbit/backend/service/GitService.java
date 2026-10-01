package com.orbit.backend.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Detects and creates git repositories.
 * <p>
 * {@link #init(Path)} writes the same skeleton {@code git init} does (HEAD on {@code main}, config, empty object
 * and ref folders). It is done here, rather than by running the {@code git} program, so it works the same inside the
 * slim backend container (which has no git) as on the user's computer and needs no extra dependency.
 */
@Service
public class GitService {

    public static final String DEFAULT_BRANCH = "main";

    /** A folder is a repository when it directly contains {@code .git} (a folder, or a file for worktrees/submodules). */
    public boolean hasRepository(Path folder) {
        return Files.exists(folder.resolve(".git"));
    }

    /** Creates an empty repository in {@code folder}. The caller checks {@link #hasRepository(Path)} first. */
    public void init(Path folder) throws IOException {
        Path git = folder.resolve(".git");
        if (Files.exists(git)) {
            throw new IOException(".git already exists");
        }
        try {
            Files.createDirectory(git);
            for (String dir : new String[]{"objects/info", "objects/pack", "refs/heads", "refs/tags", "hooks", "info"}) {
                Files.createDirectories(git.resolve(dir));
            }
            write(git.resolve("HEAD"), "ref: refs/heads/" + DEFAULT_BRANCH + "\n");
            write(git.resolve("config"), "[core]\n\trepositoryformatversion = 0\n\tbare = false\n\tlogallrefupdates = true\n");
            write(git.resolve("description"), "Unnamed repository; edit this file 'description' to name the repository.\n");
            write(git.resolve("info/exclude"), "# git ls-files --others --exclude-from=.git/info/exclude\n");
        } catch (IOException | RuntimeException e) {
            deleteQuietly(git); // never leave a half-made repository behind
            throw e;
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
