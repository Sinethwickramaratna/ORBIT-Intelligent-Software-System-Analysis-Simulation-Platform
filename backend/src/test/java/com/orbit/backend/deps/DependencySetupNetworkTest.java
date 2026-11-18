package com.orbit.backend.deps;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real downloads from the public registries (npm, PyPI, Maven Central, nuget.org, pub.dev). Needs internet, so it only
 * runs with {@code -Dorbit.networkTests=true}:
 * <pre>./mvnw test -Dtest=DependencySetupNetworkTest -Dorbit.networkTests=true</pre>
 * Every case checks first (MISSING), sets up, checks again (INSTALLED, no failures) and looks at the files on disk.
 */
@EnabledIfSystemProperty(named = "orbit.networkTests", matches = "true")
class DependencySetupNetworkTest {

    @TempDir
    Path project;

    /** The user's computer in these tests: Windows x64 (binary wheels / npm optional packages are chosen for it). */
    private final DependencyEngine engine = new DependencyEngine(DependencySettings.defaults().withUserHome(null),
            new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64, "3.12"));

    private void write(String rel, String content) throws IOException {
        Path p = project.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private DependencyEngine.SetupResult setupAndAssertInstalled() throws IOException {
        DependencyReport before = engine.check(project);
        assertThat(before.status()).isEqualTo(DependencyReport.Status.MISSING);
        DependencyEngine.SetupResult r = engine.setup(project, SetupProgress.NONE);
        assertThat(r.failures()).isEmpty();
        assertThat(r.after().status()).as(String.valueOf(r.after())).isEqualTo(DependencyReport.Status.INSTALLED);
        assertThat(engine.check(project).status()).isEqualTo(DependencyReport.Status.INSTALLED);
        return r;
    }

    private boolean anyFile(String dir, String fileName) throws IOException {
        try (Stream<Path> s = Files.walk(project.resolve(dir))) {
            return s.anyMatch(p -> p.getFileName().toString().equals(fileName));
        }
    }

    @Test
    void npmRegistryResolutionInstallsTransitiveDependencies() throws IOException {
        write("web/package.json", """
                {"name":"web","version":"1.0.0","dependencies":{"chalk":"^4.1.2"},"devDependencies":{"is-number":"~7.0.0"}}
                """);
        setupAndAssertInstalled();
        assertThat(project.resolve("web/node_modules/chalk/package.json")).exists();
        assertThat(project.resolve("web/node_modules/supports-color/package.json")).exists(); // transitive, hoisted
        assertThat(project.resolve("web/node_modules/is-number/package.json")).exists();
    }

    @Test
    void npmLockfileInstallKeepsNestedVersions() throws IOException {
        write("app/package.json", """
                {"name":"lockdemo","version":"1.0.0","dependencies":{"is-number":"7.0.0","is-odd":"3.0.1"}}
                """);
        write("app/package-lock.json", """
                {"name":"lockdemo","version":"1.0.0","lockfileVersion":3,"requires":true,"packages":{
                "":{"name":"lockdemo","version":"1.0.0","dependencies":{"is-number":"7.0.0","is-odd":"3.0.1"}},
                "node_modules/is-number":{"version":"7.0.0","resolved":"https://registry.npmjs.org/is-number/-/is-number-7.0.0.tgz",
                 "integrity":"sha512-41Cifkg6e8TylSpdtTpeLVMqvSBEVzTttHvERD741+pnZ8ANv0004MRL43QKPDlK9cGvNp6NZWZUBlbGXYxxng=="},
                "node_modules/is-odd":{"version":"3.0.1","resolved":"https://registry.npmjs.org/is-odd/-/is-odd-3.0.1.tgz",
                 "integrity":"sha512-CQpnWPrDwmP1+SMHXZhtLtJv90yiyVfluGsX5iNCVkrhQtU3TQHsUWPG9wkdk9Lgd5yNpAg9jQEo90CBaXgWMA==",
                 "dependencies":{"is-number":"^6.0.0"}},
                "node_modules/is-odd/node_modules/is-number":{"version":"6.0.0","resolved":"https://registry.npmjs.org/is-number/-/is-number-6.0.0.tgz",
                 "integrity":"sha512-Wu1VHeILBK8KAWJUAiSZQX94GmOE45Rg6/538fKwiloUu21KncEkYGPqob2oSZ5mUT73vLGrHQjKw3KMPwfDzg=="}}}
                """);
        setupAndAssertInstalled();
        assertThat(Files.readString(project.resolve("app/node_modules/is-number/package.json"))).contains("\"7.0.0\"");
        assertThat(Files.readString(project.resolve("app/node_modules/is-odd/node_modules/is-number/package.json"))).contains("\"6.0.0\"");
    }

    @Test
    void pythonRequirementsInstallWindowsWheelsIntoVenv() throws IOException {
        write("svc/requirements.txt", """
                six==1.16.0
                requests>=2.31,<3   # brings urllib3, idna, certifi, charset-normalizer
                colorama ; sys_platform == "win32"
                """);
        setupAndAssertInstalled();
        Path site = project.resolve("svc/.venv/Lib/site-packages");
        assertThat(site.resolve("six.py")).exists();
        assertThat(site.resolve("requests/__init__.py")).exists();
        assertThat(site.resolve("urllib3")).isDirectory();
        assertThat(site.resolve("colorama")).isDirectory();
        assertThat(project.resolve("svc/.venv/.gitignore")).exists();
    }

    @Test
    void pythonPinnedVersionsWithoutWheelsAreReplacedByNewerOnesAndTheFileIsUpdated() throws IOException {
        // On Windows / Python 3.12: PyYAML 5.x and cffi 1.15.0 have no wheels; cffi 1.x has newer ones, PyYAML only 6.x
        String original = "six==1.16.0\r\npyyaml==5.3.1   # config files\r\ncffi==1.15.0 ; sys_platform == \"win32\"\r\n";
        write("legacy/requirements.txt", original);
        assertThat(engine.check(project).status()).isEqualTo(DependencyReport.Status.MISSING);

        DependencyEngine.SetupResult r = engine.setup(project, SetupProgress.NONE, true);
        assertThat(r.failures()).isEmpty();
        assertThat(r.after().status()).as(String.valueOf(r.after())).isEqualTo(DependencyReport.Status.INSTALLED);
        assertThat(r.changes()).extracting(InstallContext.ManifestChange::dependency).containsExactlyInAnyOrder("pyyaml", "cffi");

        String updated = Files.readString(project.resolve("legacy/requirements.txt"));
        String[] lines = updated.split("\r\n", -1);
        assertThat(lines[0]).isEqualTo("six==1.16.0");                                    // installable: untouched
        assertThat(lines[1]).matches("pyyaml==6\\.\\d+(\\.\\d+)?  # config files \\(updated by ORBIT: was ==5\\.3\\.1 - .*\\)");
        assertThat(lines[2]).matches("cffi==1\\.\\d+\\.\\d+ ; sys_platform == \"win32\"  # updated by ORBIT: was ==1\\.15\\.0 - .*");
        assertThat(lines[2]).doesNotStartWith("cffi==1.15.0");                            // same major (1.x) preferred
        assertThat(project.resolve("legacy/requirements.txt" + PythonSupport.BACKUP_SUFFIX)).hasContent(original);
        assertThat(engine.check(project).status()).isEqualTo(DependencyReport.Status.INSTALLED);
    }

    @Test
    void pythonPinnedVersionsAreLeftAloneWhenUpdatesAreNotAllowed() throws IOException {
        String original = "pyyaml==5.3.1\n";
        write("strict/requirements.txt", original);
        DependencyEngine.SetupResult r = engine.setup(project, SetupProgress.NONE, false);
        assertThat(r.failures()).singleElement().asString().contains("version 6.").contains("works");
        assertThat(r.after().status()).isEqualTo(DependencyReport.Status.MISSING);
        assertThat(project.resolve("strict/requirements.txt")).hasContent(original);
        assertThat(project.resolve("strict/requirements.txt" + PythonSupport.BACKUP_SUFFIX)).doesNotExist();
    }

    @Test
    void pythonPyprojectWithPoetryConstraints() throws IOException {
        write("tool/pyproject.toml", """
                [tool.poetry]
                name = "tool"
                version = "0.1.0"
                [tool.poetry.dependencies]
                python = "^3.10"
                attrs = "^23.1"
                """);
        setupAndAssertInstalled();
        assertThat(project.resolve("tool/.venv/Lib/site-packages/attrs")).isDirectory();
        assertThat(anyFile("tool/.venv/Lib/site-packages", "INSTALLER")).isTrue(); // dist-info marked as installed by ORBIT
        try (Stream<Path> s = Files.list(project.resolve("tool/.venv/Lib/site-packages"))) {
            // ^23.1 means >=23.1,<24
            assertThat(s.map(p -> p.getFileName().toString())).anyMatch(n -> n.matches("attrs-23\\.\\d+.*\\.dist-info"));
        }
    }

    @Test
    void mavenMultiModuleResolvesManagedAndTransitiveDependencies() throws IOException {
        write("pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>
                  <modules><module>a</module><module>b</module></modules>
                  <properties><lang3.version>3.14.0</lang3.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>${lang3.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write("a/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>a</artifactId>
                  <dependencies>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId></dependency>
                    <dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """);
        write("b/pom.xml", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>b</artifactId>
                  <dependencies>
                    <dependency><groupId>demo</groupId><artifactId>a</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>2.10.1</version></dependency>
                  </dependencies>
                </project>
                """);
        setupAndAssertInstalled();
        String repo = JvmSupport.REPOSITORY;
        assertThat(project.resolve(repo + "/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.jar")).exists();
        assertThat(project.resolve(repo + "/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar")).exists(); // transitive of junit
        assertThat(project.resolve(repo + "/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar")).exists();
        assertThat(project.resolve(".orbit/.gitignore")).exists();
    }

    @Test
    void gradleWithVersionCatalogAndPlatform() throws IOException {
        write("gradle/libs.versions.toml", """
                [versions]
                slf4j = "2.0.13"
                [libraries]
                slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
                """);
        write("build.gradle.kts", """
                plugins { java }
                repositories { mavenCentral() }
                dependencies {
                    implementation("com.google.code.gson:gson:2.10.1")
                    implementation(libs.slf4j.api)
                    testImplementation(platform("org.junit:junit-bom:5.10.2"))
                    testImplementation("org.junit.jupiter:junit-jupiter")
                }
                """);
        setupAndAssertInstalled();
        String repo = JvmSupport.REPOSITORY;
        assertThat(project.resolve(repo + "/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar")).exists();
        assertThat(project.resolve(repo + "/org/junit/jupiter/junit-jupiter-api/5.10.2/junit-jupiter-api-5.10.2.jar")).exists();
    }

    @Test
    void nugetPackagesWithDependenciesForTargetFramework() throws IOException {
        write("Api/Api.csproj", """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
                    <PackageReference Include="Serilog.Sinks.Console">
                      <Version>5.0.1</Version>
                    </PackageReference>
                  </ItemGroup>
                </Project>
                """);
        setupAndAssertInstalled();
        String folder = NuGetSupport.FOLDER;
        assertThat(project.resolve(folder + "/newtonsoft.json/13.0.3/newtonsoft.json.13.0.3.nupkg")).exists();
        assertThat(project.resolve(folder + "/newtonsoft.json/13.0.3/lib")).isDirectory();
        assertThat(project.resolve(folder + "/serilog")).isDirectory(); // dependency of Serilog.Sinks.Console
    }

    @Test
    void pubHostedPackagesAndSdkPackages() throws IOException {
        write("mobile/pubspec.yaml", """
                name: mobile
                environment:
                  sdk: ">=3.0.0 <4.0.0"
                dependencies:
                  flutter:
                    sdk: flutter
                  path: ^1.9.0
                  collection: ^1.18.0
                dev_dependencies:
                  meta: any
                """);
        DependencyEngine.SetupResult r = setupAndAssertInstalled();
        assertThat(r.after().units().get(0).items()).anyMatch(i -> i.name().equals("flutter") && i.state() == DependencyState.SDK);
        try (Stream<Path> s = Files.list(project.resolve(PubSupport.CACHE + "/hosted/pub.dev"))) {
            assertThat(s.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("path-1."))
                    .anyMatch(n -> n.startsWith("collection-1."));
        }
    }

    // ------------------------------------------------------------------------------------------ unused, venv, cancel

    @Test
    void pythonGetsARealVirtualEnvironmentAndUnusedRequirementsAreNotDownloaded() throws IOException {
        write("requirements.txt", "requests==2.32.3\npandas==2.2.3\n");
        write("main.py", "import requests\nprint(requests.get)\n");
        // the computer ORBIT runs on: with Python installed here the venv is made by `python -m venv`
        DependencyEngine here = new DependencyEngine(DependencySettings.defaults().withUserHome(null), TargetPlatform.current());
        DependencyReport before = here.check(project);
        assertThat(before.units().get(0).items()).anyMatch(i -> i.name().equals("pandas") && i.state() == DependencyState.UNUSED);
        DependencyEngine.SetupResult r = here.setup(project, SetupProgress.NONE, true, true);
        assertThat(r.failures()).isEmpty();
        assertThat(r.after().status()).isEqualTo(DependencyReport.Status.INSTALLED);
        Path venv = project.resolve(".venv");
        assertThat(venv.resolve("pyvenv.cfg")).isRegularFile();
        Path site = PythonSupport.sitePackagesOf(venv).orElseThrow();
        assertThat(site.resolve("requests")).isDirectory();
        assertThat(site.resolve("urllib3")).isDirectory(); // what requests needs
        assertThat(site.resolve("pandas")).doesNotExist();
        assertThat(site.resolve("numpy")).doesNotExist();
        assertThat(PythonSupport.venvInterpreter(venv)).as("a real venv with its own interpreter").isPresent();
    }

    @Test
    void cancelStopsARealSetupWithinSeconds() throws Exception {
        // ORBIT's own frontend lockfile: a few hundred packages, enough to still be downloading when Cancel is pressed
        Path repo = Path.of(System.getProperty("orbit.repo", "..")).toAbsolutePath().normalize();
        Files.copy(repo.resolve("frontend/package.json"), project.resolve("package.json"));
        Files.copy(repo.resolve("frontend/package-lock.json"), project.resolve("package-lock.json"));
        java.util.concurrent.atomic.AtomicInteger installed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Runnable> hooks = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        SetupProgress progress = new SetupProgress() {
            @Override
            public void unit(String title, int index, int total) {
            }

            @Override
            public void step(String text, int done, int total) {
            }

            @Override
            public void item(String what, boolean ok) {
                if (ok) {
                    installed.incrementAndGet();
                }
            }

            @Override
            public void log(String line) {
            }

            @Override
            public boolean cancelled() {
                return cancelled.get();
            }

            @Override
            public void onCancel(Runnable action) {
                hooks.add(action);
            }
        };
        java.util.concurrent.CompletableFuture<Throwable> run = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                engine.setup(project, progress, true, true);
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        long deadline = System.currentTimeMillis() + 120_000;
        while (installed.get() < 25 && !run.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(run).as("still downloading").isNotDone();
        long t0 = System.nanoTime();
        cancelled.set(true);
        hooks.forEach(Runnable::run);
        long cancelMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        Throwable t = run.get(10, java.util.concurrent.TimeUnit.SECONDS);
        long stopMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        int atStop = installed.get();
        Thread.sleep(3000);
        System.out.println("cancel after " + atStop + " packages: cancel() took " + cancelMs + " ms, setup ended after " + stopMs
                + " ms, packages installed afterwards: " + (installed.get() - atStop));
        assertThat(cancelMs).isLessThan(500);
        assertThat(stopMs).isLessThan(3000);
        assertThat(t).isInstanceOf(IOException.class).hasMessageContaining("cancelled");
        assertThat(installed.get() - atStop).as("nothing is installed after Cancel").isLessThanOrEqualTo(2);
    }
}
