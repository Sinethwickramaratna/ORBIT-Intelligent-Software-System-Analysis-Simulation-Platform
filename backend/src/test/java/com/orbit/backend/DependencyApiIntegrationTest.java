package com.orbit.backend;

import com.orbit.backend.config.DatabaseState;
import com.orbit.backend.service.DatabaseProvisioner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dependency Check and Setup Dependencies through the REST API. The npm registry points at a closed local port, so
 * a setup that would need a download fails quickly and deterministically without internet; real downloads are covered
 * by DependencySetupNetworkTest.
 */
@SpringBootTest(properties = {
        "orbit.jwt.secret=dependency-api-test-secret-key-with-more-than-32-chars!",
        "orbit.database.url=jdbc:h2:mem:orbit_dependencies;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "orbit.env-file=./target/test-env-dependencies/.env",
        "orbit.dependencies.npm-registry=http://127.0.0.1:9",
        "orbit.dependencies.user-caches=false"
})
@ActiveProfiles("test")
class DependencyApiIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired DatabaseProvisioner databaseProvisioner;

    @TempDir Path workspace;
    MockMvc mvc;

    @BeforeEach
    void buildMockMvc() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (databaseProvisioner.getState() != DatabaseState.READY && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(databaseProvisioner.getState()).as(databaseProvisioner.getMessage()).isEqualTo(DatabaseState.READY);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body, String bearer) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private String login(String username) throws Exception {
        Map<String, String> creds = Map.of("username", username, "password", "correct-horse-1");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(creds)));
        JsonNode tokens = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(creds))).andExpect(status().isOk()));
        return tokens.get("accessToken").textValue();
    }

    /** Creates a project in a folder that already holds {@code package.json}; returns its id. */
    private String createProject(String token, String name, String packageJson, boolean installed) throws Exception {
        Path folder = workspace.resolve(name);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("package.json"), packageJson);
        if (installed) {
            Files.createDirectories(folder.resolve("node_modules/left-pad"));
            Files.writeString(folder.resolve("node_modules/left-pad/package.json"), "{\"version\":\"1.3.0\"}");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectName", name);
        m.put("location", folder.toString());
        m.put("projectType", "WEB_APPLICATION");
        m.put("initGit", false);
        return body(send(post("/api/projects"), m, token).andExpect(status().isCreated())).get("projectId").textValue();
    }

    private JsonNode waitForSetup(String token, String id) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        JsonNode p;
        do {
            Thread.sleep(100);
            p = body(send(get("/api/projects/" + id + "/dependencies/setup/progress"), null, token).andExpect(status().isOk()));
        } while ("RUNNING".equals(p.get("state").textValue()) && System.currentTimeMillis() < deadline);
        return p;
    }

    private static final String NEEDS_LEFT_PAD = "{\"name\":\"x\",\"dependencies\":{\"left-pad\":\"^1.3.0\"}}";

    @Test
    void createRunsTheDependencyCheckAutomatically() throws Exception {
        String token = login("dep-creator");
        String id = createProject(token, "auto", NEEDS_LEFT_PAD, false);

        send(get("/api/projects/" + id + "/dependencies"), null, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MISSING"))
                .andExpect(jsonPath("$.totalDependencies").value(1))
                .andExpect(jsonPath("$.missingDependencies").value(1))
                .andExpect(jsonPath("$.units[0].ecosystem").value("npm"))
                .andExpect(jsonPath("$.units[0].ecosystemLabel").value("Node.js (npm)"))
                .andExpect(jsonPath("$.units[0].manifest").value("package.json"))
                .andExpect(jsonPath("$.units[0].installLocation").value("node_modules"))
                .andExpect(jsonPath("$.units[0].dependencies[0].name").value("left-pad"))
                .andExpect(jsonPath("$.units[0].dependencies[0].state").value("MISSING"));
    }

    @Test
    void checkButtonSeesFilesInstalledAfterTheProjectWasCreated() throws Exception {
        String token = login("dep-checker");
        String id = createProject(token, "later", NEEDS_LEFT_PAD, false);
        Path folder = workspace.resolve("later");
        Files.createDirectories(folder.resolve("node_modules/left-pad"));
        Files.writeString(folder.resolve("node_modules/left-pad/package.json"), "{\"version\":\"1.3.0\"}");

        send(post("/api/projects/" + id + "/dependencies/check"), null, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INSTALLED"))
                .andExpect(jsonPath("$.units[0].dependencies[0].installed").value("1.3.0"));
        // the stored result is the new one
        send(get("/api/projects/" + id + "/dependencies"), null, token).andExpect(jsonPath("$.status").value("INSTALLED"));
        // and the check's progress shows what it did, step by step
        send(get("/api/projects/" + id + "/dependencies/check/progress"), null, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CHECK"))
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.percent").value(100))
                .andExpect(jsonPath("$.steps[0].title").value("Check Node.js (npm) - package.json"))
                .andExpect(jsonPath("$.steps[0].state").value("DONE"))
                .andExpect(jsonPath("$.steps[0].summary").value("1 installed"));
    }

    @Test
    void setupWithNothingMissingSucceeds() throws Exception {
        String token = login("dep-ready");
        String id = createProject(token, "ready", NEEDS_LEFT_PAD, true);

        send(post("/api/projects/" + id + "/dependencies/setup"), null, token)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("RUNNING"));
        JsonNode done = waitForSetup(token, id);
        assertThat(done.get("state").textValue()).isEqualTo("SUCCEEDED");
        assertThat(done.get("percent").intValue()).isEqualTo(100);
        assertThat(done.get("failures")).isEmpty();
        assertThat(done.get("steps")).isEmpty(); // nothing was missing, so there was nothing to do
        assertThat(done.get("log").toString()).contains("Nothing is missing");
        send(get("/api/projects/" + id + "/dependencies"), null, token).andExpect(jsonPath("$.status").value("INSTALLED"));
    }

    @Test
    void setupThatCannotDownloadReportsTheFailureAndStaysMissing() throws Exception {
        String token = login("dep-offline");
        String id = createProject(token, "offline", NEEDS_LEFT_PAD, false);

        send(post("/api/projects/" + id + "/dependencies/setup"), null, token).andExpect(status().isAccepted());
        // a second setup while the first one runs is refused
        send(post("/api/projects/" + id + "/dependencies/setup"), null, token)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_SETUP_RUNNING"));
        JsonNode done = waitForSetup(token, id);
        assertThat(done.get("state").textValue()).isEqualTo("FAILED");
        assertThat(done.get("kind").textValue()).isEqualTo("SETUP");
        assertThat(done.get("failures").toString()).contains("left-pad");
        assertThat(done.get("failed").intValue()).isPositive();
        assertThat(done.get("installed").intValue()).isZero();
        assertThat(done.get("steps")).hasSize(1);
        assertThat(done.get("steps").get(0).get("title").textValue()).isEqualTo("Node.js (npm) - package.json");
        assertThat(done.get("steps").get(0).get("state").textValue()).isEqualTo("FAILED");
        assertThat(done.get("steps").get(0).get("summary").textValue()).contains("failed");
        assertThat(done.get("log").toString()).contains("Node.js (npm)");
        send(get("/api/projects/" + id + "/dependencies"), null, token)
                .andExpect(jsonPath("$.status").value("MISSING"))
                .andExpect(jsonPath("$.targetPlatform").isNotEmpty());
    }

    @Test
    void setupProgressIsIdleBeforeAnySetupAndCancelIsHarmless() throws Exception {
        String token = login("dep-idle");
        String id = createProject(token, "idle", NEEDS_LEFT_PAD, false);
        send(get("/api/projects/" + id + "/dependencies/setup/progress"), null, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));
        send(delete("/api/projects/" + id + "/dependencies/setup"), null, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"));
    }

    @Test
    void anotherUsersProjectAndAnonymousCallsAreRefused() throws Exception {
        String owner = login("dep-owner");
        String id = createProject(owner, "private", NEEDS_LEFT_PAD, false);
        String other = login("dep-other");
        send(get("/api/projects/" + id + "/dependencies"), null, other).andExpect(status().isNotFound());
        send(post("/api/projects/" + id + "/dependencies/check"), null, other).andExpect(status().isNotFound());
        send(post("/api/projects/" + id + "/dependencies/setup"), null, other).andExpect(status().isNotFound());
        send(get("/api/projects/" + id + "/dependencies"), null, null).andExpect(status().isUnauthorized());
    }
}
