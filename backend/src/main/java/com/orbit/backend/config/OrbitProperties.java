package com.orbit.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Typed binding for the {@code orbit.*} configuration namespace. */
@ConfigurationProperties(prefix = "orbit")
public record OrbitProperties(String envFile, Cors cors, Jwt jwt, Database database) {

    public record Cors(List<String> allowedOrigins) {
    }

    public record Jwt(String secret, long accessTokenMinutes, long refreshTokenHours) {
    }

    /**
     * Database connection settings, read from the environment / .env.
     * {@code username} and {@code password} are chosen by the user in the first-run setup screen; {@code name}
     * and {@code port} default to {@code orbit} / {@code 5433}. {@code url} is an optional full JDBC URL override
     * (used by the tests).
     */
    public record Database(String host, String port, String name, String username, String password, String url) {

        public String hostOrDefault() {
            return blank(host) ? "localhost" : host.trim();
        }

        public String portOrDefault() {
            return blank(port) ? "5433" : port.trim();
        }

        public String nameOrDefault() {
            return blank(name) ? "orbit" : name.trim();
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
