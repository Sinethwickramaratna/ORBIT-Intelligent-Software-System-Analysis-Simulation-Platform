package com.orbit.backend.deps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * HTTPS downloads for the installers: only {@code https} (and {@code http} to localhost, for tests) is fetched,
 * downloads are size-capped, retried on network errors and 5xx, and checked against the checksum the registry
 * publishes before they are used.
 */
public class Downloader {

    /** Thrown when a registry answers 404 / 410. */
    public static class NotFoundException extends IOException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    private static final String USER_AGENT = "ORBIT-dependency-setup/1.0";
    private static final int ATTEMPTS = 3;

    private final long maxBytes;

    public Downloader(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    static void requireAllowed(URI uri) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean local = host.equals("localhost") || host.equals("127.0.0.1");
        if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
            throw new IOException("Only https downloads are allowed: " + uri);
        }
    }

    /** GET as text (JSON metadata). */
    public String text(String url, String accept) throws IOException {
        Path tmp = Files.createTempFile("orbit-meta", ".txt");
        try {
            file(url, accept, tmp);
            return Files.readString(tmp);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** GET into {@code target} (replaced atomically when complete). */
    public void file(String url, String accept, Path target) throws IOException {
        URI uri = URI.create(url);
        requireAllowed(uri);
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Path part = target.resolveSibling(target.getFileName() + ".part");
            HttpURLConnection con = null;
            try {
                // HttpURLConnection (HTTP/1.1) has a real per-read timeout: a stalled registry fails instead of hanging
                con = (HttpURLConnection) uri.toURL().openConnection();
                con.setConnectTimeout((int) Duration.ofSeconds(20).toMillis());
                con.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent", USER_AGENT);
                if (accept != null) {
                    con.setRequestProperty("Accept", accept);
                }
                int code = con.getResponseCode();
                if (code == 404 || code == 410) {
                    throw new NotFoundException("Not found: " + url);
                }
                if (code >= 500 || code == 429) {
                    throw new IOException("HTTP " + code + " from " + uri.getHost());
                }
                if (code >= 300) {
                    throw new NotFoundException("HTTP " + code + " for " + url);
                }
                requireAllowed(con.getURL().toURI());
                try (InputStream in = con.getInputStream()) {
                    Files.createDirectories(target.toAbsolutePath().getParent());
                    try (OutputStream out = Files.newOutputStream(part)) {
                        byte[] buf = new byte[64 * 1024];
                        long total = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            total += n;
                            if (total > maxBytes) {
                                throw new NotFoundException("Download is larger than " + (maxBytes >> 20) + " MB: " + url);
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (NotFoundException e) {
                Files.deleteIfExists(part);
                throw e;
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Invalid redirect for " + url);
            } catch (IOException e) {
                Files.deleteIfExists(part);
                last = e;
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Download interrupted");
                }
                if (attempt < ATTEMPTS) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrupted");
                    }
                }
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
        throw last == null ? new IOException("Download failed: " + url) : last;
    }

    // ------------------------------------------------------------------------------------------ checksums

    public static String hex(Path file, String algorithm) throws IOException {
        return HexFormat.of().formatHex(digest(file, algorithm));
    }

    static byte[] digest(Path file, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Unsupported checksum " + algorithm);
        }
    }

    /** Checks a hex SHA-256 (PyPI, pub.dev). */
    public static void verifySha256(Path file, String expectedHex, String what) throws IOException {
        if (expectedHex == null || expectedHex.isBlank()) {
            return;
        }
        if (!hex(file, "SHA-256").equalsIgnoreCase(expectedHex.trim())) {
            throw new IOException("Checksum mismatch for " + what + " - the download was not used");
        }
    }

    /** Checks an npm Subresource-Integrity string ({@code sha512-...}, several allowed) or a hex sha1 shasum. */
    public static void verifyIntegrity(Path file, String integrity, String shasum, String what) throws IOException {
        if (integrity != null && !integrity.isBlank()) {
            boolean checked = false;
            for (String entry : integrity.trim().split("\\s+")) {
                int dash = entry.indexOf('-');
                if (dash < 0) {
                    continue;
                }
                String alg = switch (entry.substring(0, dash).toLowerCase(Locale.ROOT)) {
                    case "sha512" -> "SHA-512";
                    case "sha384" -> "SHA-384";
                    case "sha256" -> "SHA-256";
                    case "sha1" -> "SHA-1";
                    default -> null;
                };
                if (alg == null) {
                    continue;
                }
                checked = true;
                String b64 = entry.substring(dash + 1).replaceAll("\\?.*$", "");
                if (Base64.getEncoder().encodeToString(digest(file, alg)).equals(b64)) {
                    return;
                }
            }
            if (checked) {
                throw new IOException("Integrity check failed for " + what + " - the download was not used");
            }
        }
        if (shasum != null && !shasum.isBlank() && !hex(file, "SHA-1").equalsIgnoreCase(shasum.trim())) {
            throw new IOException("Checksum mismatch for " + what + " - the download was not used");
        }
    }
}
