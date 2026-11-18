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
import java.util.Set;
import java.net.Socket;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Thrown when the user cancelled while a download was running. */
    public static class CancelledException extends IOException {
        public CancelledException() {
            super("Setup cancelled");
        }
    }

    private final long maxBytes;
    private final java.util.function.BooleanSupplier cancelled;
    /** The plain TCP sockets under this downloader's TLS connections: closing one ends a blocked read at once. */
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final SSLSocketFactory tls = new TrackingSocketFactory((SSLSocketFactory) SSLSocketFactory.getDefault(), sockets);

    public Downloader(long maxBytes) {
        this(maxBytes, () -> false);
    }

    public Downloader(long maxBytes, java.util.function.BooleanSupplier cancelled) {
        this.maxBytes = maxBytes;
        this.cancelled = cancelled;
    }

    /**
     * Ends every download in flight at once (used when the user cancels): the TCP sockets under the TLS connections are
     * closed, so a read blocked on a slow registry fails immediately. Never blocks - closing a plain socket does not
     * wait for the thread that is reading from it (HttpURLConnection.disconnect() would).
     */
    public void abortAll() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException | RuntimeException ignored) {
                // already closed
            }
        }
        sockets.clear();
    }

    /** Hands out the platform's TLS sockets and remembers the plain socket each one runs on. */
    private static final class TrackingSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final Set<Socket> sockets;

        TrackingSocketFactory(SSLSocketFactory delegate, Set<Socket> sockets) {
            this.delegate = delegate;
            this.sockets = sockets;
        }

        private Socket track(Socket raw, Socket result) {
            sockets.removeIf(Socket::isClosed);
            sockets.add(raw);
            return result;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return track(s, delegate.createSocket(s, host, port, autoClose)); // HttpsURLConnection layers TLS on its own socket
        }

        @Override
        public Socket createSocket() throws IOException {
            Socket s = delegate.createSocket();
            return track(s, s);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket s = delegate.createSocket(host, port);
            return track(s, s);
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws IOException {
            Socket s = delegate.createSocket(host, port, localHost, localPort);
            return track(s, s);
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws IOException {
            Socket s = delegate.createSocket(host, port);
            return track(s, s);
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort)
                throws IOException {
            Socket s = delegate.createSocket(address, port, localAddress, localPort);
            return track(s, s);
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }
    }

    /** The TLS socket factory of this downloader (package-private for tests). */
    SSLSocketFactory socketFactory() {
        return tls;
    }

    private void checkCancelled() throws CancelledException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
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
            checkCancelled();
            Path part = target.resolveSibling(target.getFileName() + ".part");
            HttpURLConnection con = null;
            try {
                // HttpURLConnection (HTTP/1.1) has a real per-read timeout: a stalled registry fails instead of hanging
                con = (HttpURLConnection) uri.toURL().openConnection();
                if (con instanceof HttpsURLConnection https) {
                    https.setSSLSocketFactory(tls);
                }
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
                            checkCancelled();
                            total += n;
                            if (total > maxBytes) {
                                throw new NotFoundException("Download is larger than " + (maxBytes >> 20) + " MB: " + url);
                            }
                            out.write(buf, 0, n);
                        }
                    }
                }
                checkCancelled();
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (NotFoundException | CancelledException e) {
                Files.deleteIfExists(part);
                throw e;
            } catch (java.net.URISyntaxException e) {
                throw new IOException("Invalid redirect for " + url);
            } catch (IOException e) {
                Files.deleteIfExists(part);
                last = e;
                checkCancelled(); // a connection closed by abortAll() ends here: no retry
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
