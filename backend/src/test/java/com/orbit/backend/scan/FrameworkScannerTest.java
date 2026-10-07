package com.orbit.backend.scan;

import com.orbit.backend.scan.FrameworkScanner.CatalogEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FrameworkScannerTest {

    /** Same shape as the framework_dependency table; the scanner has no catalog of its own. */
    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry("Spring Boot", "spring-boot-*", "maven"),
            new CatalogEntry("Spring Framework", "spring-web", "maven"),
            new CatalogEntry("FastAPI", "fastapi", "pip"),
            new CatalogEntry("Express", "express", "npm"),
            new CatalogEntry("Next.js", "next", "npm"));

    private static void file(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static FrameworkScanner.Result scan(Path root, String... manifests) {
        return new FrameworkScanner(CATALOG, 8192).scan(root, List.of(manifests), () -> { });
    }

    private static String evidence(FrameworkScanner.Result result, String framework) {
        return result.evidenceByFramework().get(framework).stream()
                .map(e -> e.filePath() + ":" + e.line() + ":" + e.column() + "=" + e.dependency()).toList().toString();
    }

    @Test
    void detectsFrameworksFromDependencyNamesWithTheirPosition(@TempDir Path root) throws IOException {
        file(root, "pom.xml", String.join("\n", "<project>", "  <artifactId>demo</artifactId>", "  <dependencies>", "    <dependency>",
                "      <artifactId>spring-boot-starter-web</artifactId>", "    </dependency>", "  </dependencies>", "</project>"));
        file(root, "api/requirements.txt", "FastAPI==0.110\n");
        file(root, "web/package.json", "{\n  \"dependencies\": {\n    \"express\": \"^4\"\n  }\n}");

        var result = scan(root, "pom.xml", "api/requirements.txt", "web/package.json");

        assertThat(result.evidenceByFramework().keySet()).containsExactly("Express", "FastAPI", "Spring Boot");
        assertThat(evidence(result, "Spring Boot")).isEqualTo("[pom.xml:5:19=spring-boot-starter-web]");
        assertThat(evidence(result, "FastAPI")).isEqualTo("[api/requirements.txt:1:1=FastAPI]");
        assertThat(evidence(result, "Express")).isEqualTo("[web/package.json:3:6=express]");
        assertThat(result.manifestsRead()).isEqualTo(3);
    }

    @Test
    void lookAlikesAreNotFrameworks(@TempDir Path root) throws IOException {
        // the project's own name, a commented-out dependency, an exclusion and spring-web alone are not Spring Boot
        file(root, "a/pom.xml", "<project><artifactId>spring-boot-demo</artifactId></project>");
        file(root, "b/pom.xml", "<project><dependencies><!-- <dependency><artifactId>spring-boot-starter</artifactId></dependency> --></dependencies></project>");
        file(root, "c/pom.xml", "<project><dependencies><dependency><artifactId>junit</artifactId><exclusions><exclusion><artifactId>spring-boot-starter</artifactId></exclusion></exclusions></dependency></dependencies></project>");
        file(root, "d/pom.xml", "<project><dependencies><dependency><artifactId>spring-web</artifactId></dependency></dependencies></project>");
        // a dependency of another ecosystem and a script / package name in package.json are not evidence either
        file(root, "e/requirements.txt", "next==1.0\n");
        file(root, "f/package.json", "{\"name\":\"fastapi\",\"dependencies\":{\"fastapi\":\"1\"},\"scripts\":{\"next\":\"next dev\"}}");

        var result = scan(root, "a/pom.xml", "b/pom.xml", "c/pom.xml", "d/pom.xml", "e/requirements.txt", "f/package.json");

        assertThat(result.evidenceByFramework().keySet()).containsExactly("Spring Framework");
    }

    @Test
    void prefixRulesMatchAndEvidenceIsSortedAndCapped(@TempDir Path root) throws IOException {
        file(root, "big/pom.xml", "<project><dependencies>"
                + "<dependency><artifactId>spring-boot-starter-x</artifactId></dependency>\n".repeat(60) + "</dependencies></project>");
        file(root, "pom.xml", "<project><dependencies><dependency><artifactId>spring-boot-starter</artifactId></dependency></dependencies></project>");

        var list = scan(root, "big/pom.xml", "pom.xml").evidenceByFramework().get("Spring Boot");

        assertThat(list.size()).isEqualTo(FrameworkScanner.MAX_EVIDENCE_PER_FRAMEWORK);
        assertThat(list.get(0).filePath()).isEqualTo("pom.xml");        // shallowest manifest first
        assertThat(list.get(1).filePath()).isEqualTo("big/pom.xml");
    }

    @Test
    void unreadableManifestsAreSkipped(@TempDir Path root) throws IOException {
        file(root, "huge/package.json", "{\"dependencies\":{\"express\":\"1\"}}" + " ".repeat(20000));
        var result = scan(root, "huge/package.json", "missing/pom.xml", "../escape/pom.xml");
        assertThat(result.evidenceByFramework().size()).isEqualTo(0);
        assertThat(result.manifestsRead()).isEqualTo(0);
    }

    @Test
    void manifestsAmongKeepsOnlyDependencyFiles() {
        assertThat(FrameworkScanner.manifestsAmong(List.of("pom.xml", "a/application.yml", "c/package.json", "Dockerfile", "pom.xml")))
                .containsExactly("pom.xml", "c/package.json");
    }
}
