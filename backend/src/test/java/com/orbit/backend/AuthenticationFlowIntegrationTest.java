package com.orbit.backend;

import com.orbit.backend.entity.RefreshToken;
import com.orbit.backend.repository.RefreshTokenRepository;
import com.orbit.backend.service.DatabaseProvisioner;
import com.orbit.backend.config.DatabaseState;
import com.orbit.backend.entity.User;
import com.orbit.backend.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AuthenticationFlowIntegrationTest {

    private static final Path ENV_FILE = Path.of("target/test-env/.env");
    private static final String SECRET = "unit-test-secret-key-with-more-than-32-chars!";

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired UserRepository userRepository;
    @Autowired RefreshTokenRepository refreshTokenRepository;
    @Autowired DatabaseProvisioner databaseProvisioner;

    MockMvc mvc;

    @BeforeAll
    static void cleanEnvFile() throws Exception {
        Files.deleteIfExists(ENV_FILE);
    }

    /** Built by hand (with the Spring Security filter chain applied) so the test does not depend on MockMvc auto-configuration packages. */
    @BeforeEach
    void buildMockMvc() throws Exception {
        // The database is provisioned in the background after startup (H2 here) - wait for it.
        long deadline = System.currentTimeMillis() + 30_000;
        while (databaseProvisioner.getState() != DatabaseState.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(databaseProvisioner.getState()).as(databaseProvisioner.getMessage()).isEqualTo(DatabaseState.READY);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private ResultActions postJson(String url, Object body, String bearer) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void fullFirstRunAndAuthenticationFlow() throws Exception {
        // --- first run: nothing configured -------------------------------------------------------------
        mvc.perform(get("/api/setup/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.secretKeyConfigured").value(false))
                .andExpect(jsonPath("$.databaseCredentialsConfigured").value(true)) // H2 URL override in the test profile
                .andExpect(jsonPath("$.databaseState").value("READY"))
                .andExpect(jsonPath("$.hasUsers").value(false));

        postJson("/api/auth/login", new Creds("nobody", "whatever12"), null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECRET_KEY_NOT_CONFIGURED"));

        // --- secret key -> saved to .env ---------------------------------------------------------------
        postJson("/api/setup/environment", new EnvReq("short", null, null), null).andExpect(status().isBadRequest());
        postJson("/api/setup/environment", new EnvReq("has a space in it but is long enough 1234567890", null, null), null)
                .andExpect(status().isBadRequest());
        // database credentials are already configured (test profile), so they must not be overwritten
        postJson("/api/setup/environment", new EnvReq(SECRET, "hacker", "password123"), null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ENVIRONMENT_ALREADY_CONFIGURED"));
        assertThat(Files.exists(ENV_FILE)).as("nothing may be written for a rejected request").isFalse();
        postJson("/api/setup/environment", new EnvReq(SECRET, null, null), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secretKeyConfigured").value(true));
        assertThat(Files.readString(ENV_FILE)).contains("JWT_SECRET=" + SECRET);
        postJson("/api/setup/environment", new EnvReq(SECRET + "x", null, null), null)
                .andExpect(status().isConflict());

        // --- registration ------------------------------------------------------------------------------
        postJson("/api/auth/register", new Creds("sineth", "correct-horse-1"), null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userName").value("sineth"))
                .andExpect(jsonPath("$.password").doesNotExist());
        postJson("/api/auth/register", new Creds("sineth", "another-pass-1"), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"));
        postJson("/api/auth/register", new Creds("x", "short"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.username").exists());

        User stored = userRepository.findByUserName("sineth").orElseThrow();
        assertThat(stored.getPassword()).startsWith("$argon2");
        assertThat(stored.getUserId()).isNotNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getUpdatedAt()).isNotNull();

        // --- login errors -------------------------------------------------------------------------------
        postJson("/api/auth/login", new Creds("ghost", "correct-horse-1"), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        postJson("/api/auth/login", new Creds("sineth", "wrong-password"), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));

        // --- successful login ---------------------------------------------------------------------------
        JsonNode tokens = body(postJson("/api/auth/login", new Creds("sineth", "correct-horse-1"), null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.userName").value("sineth")));
        String access = tokens.get("accessToken").textValue();
        String refresh = tokens.get("refreshToken").textValue();
        Instant accessExpiry = Instant.parse(tokens.get("accessTokenExpiresAt").textValue());
        assertThat(accessExpiry).isBetween(Instant.now().plusSeconds(14 * 60), Instant.now().plusSeconds(15 * 60 + 5));
        Instant refreshExpiry = Instant.parse(tokens.get("refreshTokenExpiresAt").textValue());
        assertThat(refreshExpiry).isBetween(Instant.now().plusSeconds(23 * 3600), Instant.now().plusSeconds(24 * 3600 + 5));
        List<RefreshToken> rows = refreshTokenRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getToken()).isNotEqualTo(refresh); // only a hash is stored
        assertThat(rows.get(0).getUserId()).isEqualTo(stored.getUserId());

        // --- protected endpoints --------------------------------------------------------------------------
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userName").value("sineth"));

        String expired = Jwts.builder().subject(stored.getUserId().toString())
                .claim("user_id", stored.getUserId().toString()).claim("username", "sineth")
                .issuedAt(Date.from(Instant.now().minusSeconds(1000)))
                .expiration(Date.from(Instant.now().minusSeconds(100)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));

        // --- refresh ------------------------------------------------------------------------------------------
        JsonNode refreshed = body(postJson("/api/auth/refresh", new RefreshReq(refresh), null).andExpect(status().isOk()));
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + refreshed.get("accessToken").textValue()))
                .andExpect(status().isOk());
        postJson("/api/auth/refresh", new RefreshReq("bogus"), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_INVALID"));

        // --- expired refresh token is deleted --------------------------------------------------------------
        RefreshToken row = refreshTokenRepository.findAll().get(0);
        row.setExpiredAt(Instant.now().minusSeconds(5));
        refreshTokenRepository.saveAndFlush(row);
        postJson("/api/auth/refresh", new RefreshReq(refresh), null)
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("REFRESH_TOKEN_EXPIRED"));
        assertThat(refreshTokenRepository.count()).isZero();

        // --- logout deletes the refresh token ---------------------------------------------------------------
        JsonNode second = body(postJson("/api/auth/login", new Creds("sineth", "correct-horse-1"), null).andExpect(status().isOk()));
        assertThat(refreshTokenRepository.count()).isEqualTo(1);
        postJson("/api/auth/logout", new RefreshReq(second.get("refreshToken").textValue()), null)
                .andExpect(status().isUnauthorized()); // logout itself needs an access token
        postJson("/api/auth/logout", new RefreshReq(second.get("refreshToken").textValue()), second.get("accessToken").textValue())
                .andExpect(status().isNoContent());
        assertThat(refreshTokenRepository.count()).isZero();
        postJson("/api/auth/refresh", new RefreshReq(second.get("refreshToken").textValue()), null)
                .andExpect(status().isUnauthorized());

        // --- status now reports a user -----------------------------------------------------------------------
        mvc.perform(get("/api/setup/status")).andExpect(jsonPath("$.hasUsers").value(true));
    }

    @Test
    void themePreferenceIsPersisted() throws Exception {
        mvc.perform(get("/api/settings/theme")).andExpect(status().isOk());
        mvc.perform(put("/api/settings/theme").contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"nope\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SETTING"));
        mvc.perform(put("/api/settings/theme").contentType(MediaType.APPLICATION_JSON).content("{\"theme\":\"vs-dark\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.theme").value("vs-dark"));
        mvc.perform(get("/api/settings/theme")).andExpect(jsonPath("$.theme").value("vs-dark"));
        mvc.perform(get("/api/setup/status")).andExpect(jsonPath("$.themeSelected").value(true));
    }

    record Creds(String username, String password) {
    }

    record EnvReq(String secretKey, String dbUsername, String dbPassword) {
    }

    record RefreshReq(String refreshToken) {
    }
}
