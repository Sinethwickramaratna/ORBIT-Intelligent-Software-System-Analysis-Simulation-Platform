package com.orbit.backend.scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyReaderTest {

    /** "name@line:column" of every dependency read from the manifest. */
    private static List<String> read(String fileName, String... lines) {
        return DependencyReader.read(fileName, String.join("\n", lines)).orElseThrow().dependencies().stream()
                .map(d -> d.name() + "@" + d.line() + ":" + d.column()).toList();
    }

    @Test
    void pomReadsParentDependenciesAndPluginsButNotTheProjectItselfExclusionsOrComments() {
        assertThat(read("pom.xml",
                "<project>",
                "  <artifactId>spring-boot-demo</artifactId>",
                "  <parent>",
                "    <artifactId>spring-boot-starter-parent</artifactId>",
                "  </parent>",
                "  <dependencies>",
                "    <!-- <dependency><artifactId>commented-out</artifactId></dependency> -->",
                "    <dependency>",
                "      <artifactId>spring-boot-starter-web</artifactId>",
                "      <exclusions><exclusion><artifactId>excluded-lib</artifactId></exclusion></exclusions>",
                "    </dependency>",
                "    <dependency><artifactId>${dyn}</artifactId></dependency>",
                "  </dependencies>",
                "</project>"))
                .containsExactly("spring-boot-starter-parent@4:17", "spring-boot-starter-web@9:19");
    }

    @Test
    void windowsLineEndingsDoNotMoveTheColumns() {
        var content = String.join("\r\n", "<project>", "  <dependencies>", "    <dependency>",
                "      <artifactId>spring-boot-starter</artifactId>", "    </dependency>", "  </dependencies>", "</project>");
        var dep = DependencyReader.read("pom.xml", content).orElseThrow().dependencies().get(0);
        assertThat(dep.line() + ":" + dep.column()).isEqualTo("4:19");
    }

    @Test
    void gradleReadsDependencyStringsPluginIdsAndNamedNotation() {
        assertThat(read("build.gradle",
                "plugins {",
                "    id 'org.springframework.boot' version '3.2.0'",
                "    id(\"io.spring.dependency-management\") version \"1.1.4\"",
                "}",
                "dependencies {",
                "    implementation 'org.springframework.boot:spring-boot-starter-web:3.2.0'",
                "    // implementation 'commented:out:1.0'",
                "    implementation(\"com.foo:bar-lib:1.0\") // trailing 'x:y:z'",
                "    implementation group: 'org.x', name: 'legacy-lib', version: '1'",
                "}",
                "repositories { maven { url 'https://repo.example.com/maven' } }",
                "rootProject.name = 'spring-boot-demo'"))
                .containsExactly("org.springframework.boot@2:9", "io.spring.dependency-management@3:9",
                        "spring-boot-starter-web@6:46", "bar-lib@8:29", "legacy-lib@9:43");
    }

    @Test
    void packageJsonReadsOnlyTheDependencySections() {
        assertThat(read("package.json",
                "{",
                "  \"name\": \"express-demo\",",
                "  \"dependencies\": {",
                "    \"express\": \"^4.18.0\",",
                "    \"@nestjs/core\": \"^10\"",
                "  },",
                "  \"devDependencies\": { \"jest\": \"^29\" },",
                "  \"scripts\": { \"express\": \"node express.js\" },",
                "  \"description\": \"uses \\\"react\\\" inside\"",
                "}"))
                .containsExactly("express@4:6", "@nestjs/core@5:6", "jest@7:25");
    }

    @Test
    void requirementsReadsNamesAndSkipsCommentsOptionsAndUrls() {
        assertThat(read("requirements.txt",
                "# web",
                "fastapi==0.110.0",
                "  uvicorn[standard]>=0.29",
                "-r base.txt",
                "Flask_SQLAlchemy ; python_version>'3'",
                "requests # comment",
                "git+https://github.com/x/y.git"))
                .containsExactly("fastapi@2:1", "uvicorn@3:3", "Flask_SQLAlchemy@5:1", "requests@6:1");
    }

    @Test
    void pyprojectAndPipfileReadTheirDependencyTables() {
        assertThat(read("pyproject.toml",
                "[project]",
                "name = \"fastapi-demo\"",
                "dependencies = [",
                "  \"fastapi>=0.100\",",
                "  \"pydantic[email]\",  # note",
                "]",
                "",
                "[project.optional-dependencies]",
                "dev = [\"pytest>=7\", \"httpx\"]",
                "[tool.poetry.dependencies]",
                "python = \"^3.11\"",
                "django = \"^5.0\""))
                .containsExactly("fastapi@4:4", "pydantic@5:4", "pytest@9:9", "httpx@9:22", "django@12:1");
        assertThat(read("Pipfile",
                "[[source]]",
                "url = \"https://pypi.org/simple\"",
                "[packages]",
                "flask = \"*\"",
                "[dev-packages]",
                "pytest = \"*\""))
                .containsExactly("flask@4:1", "pytest@6:1");
    }

    @Test
    void pubspecReadsDependenciesButNotTheirOptions() {
        assertThat(read("pubspec.yaml",
                "name: flutter_demo",
                "dependencies:",
                "  flutter:",
                "    sdk: flutter",
                "  http: ^1.0.0",
                "dev_dependencies:",
                "  flutter_test:",
                "    sdk: flutter",
                "flutter:",
                "  uses-material-design: true"))
                .containsExactly("flutter@3:3", "http@5:3", "flutter_test@7:3");
    }

    @Test
    void csprojReadsTheSdkAndPackageReferences() {
        assertThat(read("Demo.csproj",
                "<Project Sdk=\"Microsoft.NET.Sdk.Web\">",
                "  <ItemGroup>",
                "    <PackageReference Include=\"Newtonsoft.Json\" Version=\"13.0.1\" />",
                "    <!-- <PackageReference Include=\"commented\" /> -->",
                "  </ItemGroup>",
                "</Project>"))
                .containsExactly("Microsoft.NET.Sdk.Web@1:15", "Newtonsoft.Json@3:32");
    }

    @Test
    void unsupportedFilesAreNotManifestsAndNamesAreNormalised() {
        assertThat(DependencyReader.read("README.md", "express").isPresent()).isFalse();
        assertThat(DependencyReader.packageManagerOf("POM.XML").orElse("none")).isEqualTo("maven");
        assertThat(DependencyReader.packageManagerOf("requirements-dev.txt").orElse("none")).isEqualTo("pip");
        assertThat(DependencyReader.normalize("pip", "Flask_SQLAlchemy")).isEqualTo("flask-sqlalchemy");
        assertThat(DependencyReader.normalize("npm", "@Angular/Core")).isEqualTo("@angular/core");
    }
}
