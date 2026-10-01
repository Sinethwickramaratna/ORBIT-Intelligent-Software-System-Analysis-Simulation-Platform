package com.orbit.backend;

import com.orbit.backend.config.DatabaseState;
import com.orbit.backend.repository.ProjectRepository;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Projects API end to end on the in-memory database. It has its own property set (own H2 database, secret already
 * configured), so it gets its own Spring context and cannot disturb the first-run assertions of
 * {@link AuthenticationFlowIntegrationTest}.
 */
@SpringBootTest(properties = {
        "orbit.jwt.secret=project-api-test-secret-key-with-more-than-32-chars!",
        "orbit.database.url=jdbc:h2:mem:orbit_projects;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "orbit.env-file=./target/test-env-projects/.env"
})
@ActiveProfiles("test")
class ProjectApiIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired ProjectRepository projectRepository;
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

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               Object body, String bearer) throws Exception {
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

    /** Registers (if needed) and logs in; returns the access token. */
    private String login(String username) throws Exception {
        Map<String, String> creds = Map.of("username", username, "password", "correct-horse-1");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(creds)));
        JsonNode tokens = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(creds))).andExpect(status().isOk()));
        return tokens.get("accessToken").textValue();
    }

    private static Map<String, Object> newProject(String name, Path location, String type, Boolean initGit) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("projectName", name);
        m.put("location", location.toString());
        m.put("projectType", type);
        m.put("description", "about " + name.trim());
        if (initGit != null) {
            m.put("initGit", initGit);
        }
        return m;
    }

    @Test
    void createSavesTheProjectAndInitializesGitInANewFolder() throws Exception {
        String token = login("creator");
        Path folder = workspace.resolve("shop");

        JsonNode created = body(send(post("/api/projects"), newProject("  Shop  ", folder, "WEB_APPLICATION", true), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectName").value("Shop"))
                .andExpect(jsonPath("$.projectType").value("WEB_APPLICATION"))
                .andExpect(jsonPath("$.projectTypeLabel").value("Web Application"))
                .andExpect(jsonPath("$.description").value("about Shop"))
                .andExpect(jsonPath("$.gitStatus").value("INITIALIZED"))
                .andExpect(jsonPath("$.folderCreated").value(true)));

        assertThat(Files.isDirectory(folder.resolve(".git"))).isTrue();
        var saved = projectRepository.findById(java.util.UUID.fromString(created.get("projectId").textValue())).orElseThrow();
        assertThat(saved.getProjectName()).isEqualTo("Shop");
        assertThat(saved.getUserId().toString()).isEqualTo(created.get("userId").textValue());
        assertThat(saved.getLocation()).isNotBlank();
    }

    @Test
    void anExistingRepositoryIsLeftAlone() throws Exception {
        String token = login("repo-owner");
        Path folder = workspace.resolve("existing");
        Files.createDirectories(folder.resolve(".git"));
        Files.writeString(folder.resolve(".git/HEAD"), "ref: refs/heads/trunk\n");

        send(post("/api/projects"), newProject("Existing", folder, "BACKEND_API", true), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gitStatus").value("ALREADY_EXISTS"))
                .andExpect(jsonPath("$.folderCreated").value(false))
                .andExpect(jsonPath("$.gitRepository").value(true));
        assertThat(Files.readString(folder.resolve(".git/HEAD"))).isEqualTo("ref: refs/heads/trunk\n");
    }

    @Test
    void gitIsNotInitializedUnlessAsked() throws Exception {
        String token = login("no-git");
        Path off = workspace.resolve("off");
        Path omitted = workspace.resolve("omitted");

        send(post("/api/projects"), newProject("Off", off, "OTHER", false), token)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.gitStatus").value("SKIPPED"));
        send(post("/api/projects"), newProject("Omitted", omitted, "OTHER", null), token)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.gitStatus").value("SKIPPED"));
        assertThat(Files.exists(off.resolve(".git"))).isFalse();
        assertThat(Files.exists(omitted.resolve(".git"))).isFalse();
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        String token = login("validator");
        Path folder = workspace.resolve("v");

        Map<String, Object> noName = newProject(" ", folder, "OTHER", null);
        send(post("/api/projects"), noName, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.projectName").exists());
        send(post("/api/projects"), newProject("x", folder, "SPACESHIP", null), token).andExpect(status().isBadRequest());
        Map<String, Object> noLocation = newProject("x", folder, "OTHER", null);
        noLocation.put("location", " ");
        send(post("/api/projects"), noLocation, token).andExpect(status().isBadRequest());
        Map<String, Object> relative = newProject("x", folder, "OTHER", null);
        relative.put("location", "relative/dir");
        send(post("/api/projects"), relative, token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PROJECT_LOCATION_INVALID"));
        assertThat(Files.exists(folder)).isFalse();
    }

    @Test
    void theSameFolderCannotBeRegisteredTwiceByOneUser() throws Exception {
        String token = login("twice");
        Path folder = workspace.resolve("dup");
        send(post("/api/projects"), newProject("One", folder, "OTHER", null), token).andExpect(status().isCreated());
        send(post("/api/projects"), newProject("Two", folder, "OTHER", null), token)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROJECT_ALREADY_EXISTS"));
    }

    @Test
    void usersOnlySeeAndReadTheirOwnProjects() throws Exception {
        String alice = login("alice-p");
        String bob = login("bob-p");
        Path folder = workspace.resolve("alice-app");
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/main.txt"), "x");
        Files.writeString(folder.resolve("README.md"), "x");

        String id = body(send(post("/api/projects"), newProject("Alice App", folder, "DESKTOP_APPLICATION", false), alice)
                .andExpect(status().isCreated())).get("projectId").textValue();

        send(get("/api/projects"), null, alice).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].projectName").value("Alice App"));
        send(get("/api/projects"), null, bob).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        send(get("/api/projects/" + id), null, bob).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
        send(get("/api/projects/" + id + "/tree"), null, bob).andExpect(status().isNotFound());
        send(get("/api/projects/" + id), null, alice).andExpect(status().isOk())
                .andExpect(jsonPath("$.folderAvailable").value(true));
    }

    @Test
    void treeListsFoldersFirstAndLoadsSubFolders() throws Exception {
        String token = login("tree-user");
        Path folder = workspace.resolve("tree-app");
        Files.createDirectories(folder.resolve("src/components"));
        Files.writeString(folder.resolve("src/main.ts"), "x");
        Files.writeString(folder.resolve("src/components/Button.tsx"), "x");
        Files.writeString(folder.resolve("README.md"), "12345");
        Files.createDirectories(folder.resolve(".git"));

        String id = body(send(post("/api/projects"), newProject("Tree", folder, "WEB_APPLICATION", false), token)
                .andExpect(status().isCreated())).get("projectId").textValue();

        send(get("/api/projects/" + id + "/tree"), null, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].name").value("src"))
                .andExpect(jsonPath("$.entries[0].kind").value("FOLDER"))
                .andExpect(jsonPath("$.entries[0].hasChildren").value(true))
                .andExpect(jsonPath("$.entries[1].name").value("README.md"))
                .andExpect(jsonPath("$.entries[1].kind").value("FILE"))
                .andExpect(jsonPath("$.entries[1].size").value(5));
        send(get("/api/projects/" + id + "/tree").param("path", "src"), null, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].path").value("src/components"))
                .andExpect(jsonPath("$.entries[1].path").value("src/main.ts"));
        send(get("/api/projects/" + id + "/tree").param("path", "src/components"), null, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].name").value("Button.tsx"));
    }

    @Test
    void treeRefusesPathsOutsideTheProject() throws Exception {
        String token = login("path-user");
        Path folder = workspace.resolve("safe");
        String id = body(send(post("/api/projects"), newProject("Safe", folder, "OTHER", false), token)
                .andExpect(status().isCreated())).get("projectId").textValue();

        for (String bad : new String[]{"..", "src/../..", "/etc", "C:/Windows", "missing"}) {
            send(get("/api/projects/" + id + "/tree").param("path", bad), null, token)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROJECT_PATH_INVALID"));
        }
    }

    @Test
    void inspectDescribesTheLocationBeforeCreating() throws Exception {
        String token = login("inspector");
        Path repo = workspace.resolve("inspect-repo");
        Files.createDirectories(repo.resolve(".git"));

        send(get("/api/projects/inspect").param("location", repo.toString()), null, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.gitRepository").value(true));
        send(get("/api/projects/inspect").param("location", workspace.resolve("brand-new").toString()), null, token)
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.gitRepository").value(false));
        send(get("/api/projects/inspect").param("location", "relative"), null, token).andExpect(jsonPath("$.valid").value(false));
        send(get("/api/projects/config"), null, token).andExpect(status().isOk()).andExpect(jsonPath("$.root").doesNotExist());
    }

    @Test
    void everyEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects/inspect?location=x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects/config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/projects/00000000-0000-0000-0000-000000000000/tree")).andExpect(status().isUnauthorized());
    }
}
