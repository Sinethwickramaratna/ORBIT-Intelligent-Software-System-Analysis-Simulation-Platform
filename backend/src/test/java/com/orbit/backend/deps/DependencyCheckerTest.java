package com.orbit.backend.deps;

import com.orbit.backend.deps.DependencyReport.Item;
import com.orbit.backend.deps.DependencyReport.Status;
import com.orbit.backend.deps.DependencyReport.Unit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The dependency check of every ecosystem against files on disk (no network). */
class DependencyCheckerTest {

    @TempDir
    Path project;

    @TempDir
    Path home;

    private static final TargetPlatform WINDOWS = new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64, "3.12");

    private DependencyReport check() throws IOException {
        return new DependencyEngine(DependencySettings.defaults().withUserHome(home), WINDOWS).check(project);
    }

    private void write(Path base, String rel, String content) throws IOException {
        Path p = base.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private void write(String rel, String content) throws IOException {
        write(project, rel, content);
    }

    private static Unit unit(DependencyReport r, Ecosystem e, String manifest) {
        return r.units().stream().filter(u -> u.ecosystem() == e && u.manifest().equals(manifest)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + e + " unit " + manifest + " in " + r.units()));
    }

    private static Item item(Unit u, String name) {
        return u.items().stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no item " + name + " in " + u.items()));
    }

    // ------------------------------------------------------------------------------------------ npm

    @Test
    void npmInstalledMissingOutdatedLocalAndRemoteDependencies() throws IOException {
        write("web/package.json", """
                {"name":"web","dependencies":{"react":"^18.2.0","lodash":"^4.17.0","left-pad":"^1.3.0",
                 "shared":"file:../shared","forked":"github:someone/forked"},
                 "devDependencies":{"typescript":"~5.4.0"}}
                """);
        write("web/node_modules/react/package.json", "{\"name\":\"react\",\"version\":\"18.3.1\"}");
        write("web/node_modules/lodash/package.json", "{\"name\":\"lodash\",\"version\":\"3.10.1\"}");
        write("web/node_modules/typescript/package.json", "{\"name\":\"typescript\",\"version\":\"5.4.5\"}");
        Unit u = unit(check(), Ecosystem.NPM, "web/package.json");
        assertThat(u.status()).isEqualTo(Status.MISSING);
        assertThat(u.installLocation()).isEqualTo("web/node_modules");
        assertThat(item(u, "react").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(item(u, "react").installed()).isEqualTo("18.3.1");
        assertThat(item(u, "lodash").state()).isEqualTo(DependencyState.OUTDATED);
        assertThat(item(u, "left-pad").state()).isEqualTo(DependencyState.MISSING);
        assertThat(item(u, "shared").state()).isEqualTo(DependencyState.LOCAL);
        assertThat(item(u, "forked").state()).isEqualTo(DependencyState.UNSUPPORTED);
        assertThat(item(u, "typescript").scope()).isEqualTo("dev");
    }

    @Test
    void npmWorkspaceMembersFindHoistedPackagesAndSiblings() throws IOException {
        write("package.json", "{\"name\":\"mono\",\"private\":true,\"workspaces\":[\"packages/*\"]}");
        write("packages/app/package.json", "{\"name\":\"app\",\"dependencies\":{\"lib\":\"*\",\"is-number\":\"^7.0.0\"}}");
        write("packages/lib/package.json", "{\"name\":\"lib\",\"version\":\"1.0.0\"}");
        write("node_modules/is-number/package.json", "{\"version\":\"7.0.0\"}");
        write("packages/app/node_modules/ignored/package.json", "{\"name\":\"ignored\",\"dependencies\":{\"x\":\"1\"}}");
        DependencyReport r = check();
        Unit app = unit(r, Ecosystem.NPM, "packages/app/package.json");
        assertThat(app.status()).isEqualTo(Status.INSTALLED);
        assertThat(item(app, "lib").state()).isEqualTo(DependencyState.LOCAL);
        assertThat(item(app, "is-number").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(app.installLocation()).isEqualTo("node_modules");
        // manifests inside node_modules are never units
        assertThat(r.units()).noneMatch(x -> x.manifest().contains("node_modules"));
    }

    @Test
    void npmLockfileCountsEveryLockedPackageForThisPlatform() throws IOException {
        write("package.json", "{\"name\":\"a\",\"dependencies\":{\"esbuild\":\"0.20.0\"}}");
        write("package-lock.json", """
                {"lockfileVersion":3,"packages":{"":{"name":"a","dependencies":{"esbuild":"0.20.0"}},
                 "node_modules/esbuild":{"version":"0.20.0","optionalDependencies":{
                   "@esbuild/win32-x64":"0.20.0","@esbuild/linux-x64":"0.20.0"}},
                 "node_modules/@esbuild/win32-x64":{"version":"0.20.0","optional":true,"os":["win32"],"cpu":["x64"]},
                 "node_modules/@esbuild/linux-x64":{"version":"0.20.0","optional":true,"os":["linux"],"cpu":["x64"]}}}
                """);
        write("node_modules/esbuild/package.json", "{\"version\":\"0.20.0\"}");
        Unit u = unit(check(), Ecosystem.NPM, "package.json");
        assertThat(item(u, "esbuild").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(u.status()).isEqualTo(Status.MISSING);
        assertThat(u.message()).isEqualTo("1 of 2 packages the code needs (from package-lock.json) are not installed"); // linux one skipped
        write("node_modules/@esbuild/win32-x64/package.json", "{\"version\":\"0.20.0\"}");
        assertThat(unit(check(), Ecosystem.NPM, "package.json").status()).isEqualTo(Status.INSTALLED);
    }

    @Test
    void npmLockfileIgnoresPackagesOnlyNeededByAnotherPlatformsBinary() throws IOException {
        // Vite/rolldown style: the wasm32 binding is skipped, so its helper packages (no os/cpu of their own) are not needed
        write("package.json", "{\"name\":\"a\",\"devDependencies\":{\"rolldown\":\"1.0.0\"}}");
        write("package-lock.json", """
                {"lockfileVersion":3,"packages":{"":{"name":"a","devDependencies":{"rolldown":"1.0.0"}},
                 "node_modules/rolldown":{"version":"1.0.0","dev":true,"optionalDependencies":{
                   "@rolldown/binding-win32-x64-msvc":"1.0.0","@rolldown/binding-wasm32-wasi":"1.0.0"}},
                 "node_modules/@rolldown/binding-win32-x64-msvc":{"version":"1.0.0","optional":true,"os":["win32"],"cpu":["x64"]},
                 "node_modules/@rolldown/binding-wasm32-wasi":{"version":"1.0.0","optional":true,"cpu":["wasm32"],
                   "dependencies":{"@napi-rs/wasm-runtime":"1.0.0"}},
                 "node_modules/@napi-rs/wasm-runtime":{"version":"1.0.0","optional":true,"dependencies":{"@emnapi/core":"1.0.0"}},
                 "node_modules/@emnapi/core":{"version":"1.0.0","optional":true},
                 "node_modules/orphan":{"version":"1.0.0"}}}
                """);
        write("node_modules/rolldown/package.json", "{\"version\":\"1.0.0\"}");
        write("node_modules/@rolldown/binding-win32-x64-msvc/package.json", "{\"version\":\"1.0.0\"}");
        Unit u = unit(check(), Ecosystem.NPM, "package.json");
        assertThat(u.message()).isNull();
        assertThat(u.status()).isEqualTo(Status.INSTALLED);
        // positive control: the Windows binding IS needed, so removing it turns the check red again
        Files.delete(project.resolve("node_modules/@rolldown/binding-win32-x64-msvc/package.json"));
        Unit again = unit(check(), Ecosystem.NPM, "package.json");
        assertThat(again.status()).isEqualTo(Status.MISSING);
        assertThat(again.message()).isEqualTo("1 of 2 packages the code needs (from package-lock.json) are not installed");
    }

    // ------------------------------------------------------------------------------------------ Python

    @Test
    void pythonRequirementsAgainstTheVirtualEnvironment() throws IOException {
        write("api/requirements.txt", """
                # app
                -r requirements-base.txt
                Django>=4.2,<5  # web
                numpy==1.26.4
                uvloop ; sys_platform != "win32"
                -e .
                git+https://github.com/someone/thing.git#egg=thing
                """);
        write("api/requirements-base.txt", "requests>=2.31\n");
        write("api/.venv/pyvenv.cfg", "home = C:\\Python311\nversion = 3.11.9\n");
        write("api/.venv/Lib/site-packages/Django-4.2.11.dist-info/METADATA", "Name: Django\n");
        write("api/.venv/Lib/site-packages/numpy-1.26.0.dist-info/METADATA", "Name: numpy\n");
        Unit u = unit(check(), Ecosystem.PYTHON, "api/requirements-base.txt, api/requirements.txt");
        assertThat(u.installLocation()).isEqualTo("api/.venv");
        assertThat(item(u, "Django").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(item(u, "numpy").state()).isEqualTo(DependencyState.OUTDATED);
        assertThat(item(u, "requests").state()).isEqualTo(DependencyState.MISSING);
        assertThat(item(u, "thing").state()).isEqualTo(DependencyState.UNSUPPORTED);
        assertThat(u.items()).noneMatch(i -> i.name().equals("uvloop")); // marker is false on Windows
        assertThat(u.status()).isEqualTo(Status.MISSING);
    }

    @Test
    void pythonPyprojectPoetryAndPipfileWithoutVenv() throws IOException {
        write("svc/pyproject.toml", """
                [project]
                name = "svc"
                dependencies = ["fastapi>=0.110", "svc-helpers"]
                [tool.poetry.group.dev.dependencies]
                pytest = "^8.0"
                """);
        write("svc/Pipfile", """
                [packages]
                flask = "*"
                [dev-packages]
                black = {version = ">=24.1"}
                """);
        Unit u = unit(check(), Ecosystem.PYTHON, "svc/Pipfile, svc/pyproject.toml");
        assertThat(u.items()).extracting(Item::name).contains("fastapi", "svc-helpers", "pytest", "flask", "black");
        assertThat(u.items()).allMatch(i -> i.state() == DependencyState.MISSING);
        assertThat(u.message()).contains("Setup Dependencies creates svc/.venv");
        assertThat(PythonSupport.poetryToPep440("^8.0")).isEqualTo(">=8.0,<9");
        assertThat(PythonSupport.poetryToPep440("^0.2.3")).isEqualTo(">=0.2.3,<0.3");
        assertThat(PythonSupport.poetryToPep440("~1.2.3")).isEqualTo(">=1.2.3,<1.3");
        assertThat(PythonSupport.poetryToPep440("1.2.3")).isEqualTo("==1.2.3");
    }

    @Test
    void pythonRequirementLinesAreRewrittenKeepingExtrasMarkersAndComments() {
        String why = "no ready-made package (wheel) for Windows / Python 3.12";
        assertThat(PythonSupport.rewriteRequirement("numpy==1.21.0", "numpy", "1.26.4", "==1.21.0", why))
                .isEqualTo("numpy==1.26.4  # updated by ORBIT: was ==1.21.0 - " + why);
        assertThat(PythonSupport.rewriteRequirement("  Pillow[webp] >=8,<9 ; sys_platform == 'win32'   # images", "pillow", "9.5.0",
                ">=8,<9", why))
                .isEqualTo("  Pillow[webp]==9.5.0 ; sys_platform == 'win32'  # images (updated by ORBIT: was >=8,<9 - " + why + ")");
        assertThat(PythonSupport.rewriteRequirement("requests", "requests", "2.32.3", "", why))
                .startsWith("requests==2.32.3  # updated by ORBIT: was unpinned");
        assertThat(PythonSupport.rewriteRequirement("django==4.2", "numpy", "1.26.4", "==1.21.0", why)).isNull(); // other package
    }

    @Test
    void pythonNewerVersionPrefersTheSameMajorVersion() {
        List<com.orbit.backend.deps.version.Pep440.Version> installable = java.util.stream.Stream
                .of("1.14.0", "1.16.0", "1.17.1", "2.0.0", "2.1.0rc1")
                .map(v -> com.orbit.backend.deps.version.Pep440.parse(v).orElseThrow()).toList();
        java.util.function.Function<String, String> newer = req -> PythonSupport.newerVersion(installable,
                req == null ? null : com.orbit.backend.deps.version.Pep440.parse(req).orElseThrow()).map(Object::toString).orElse(null);
        assertThat(newer.apply("1.15.0")).isEqualTo("1.17.1");   // newest 1.x above the pin
        assertThat(newer.apply("1.17.1")).isEqualTo("2.0.0");    // no newer 1.x: newest overall (never a pre-release)
        assertThat(newer.apply("2.0.0")).isNull();               // nothing newer that works
        assertThat(newer.apply(null)).isEqualTo("2.0.0");        // unpinned: newest
        assertThat(com.orbit.backend.deps.version.Pep440.specifier(">=1.2,<2").lowerBound().orElseThrow().toString()).isEqualTo("1.2");
        assertThat(com.orbit.backend.deps.version.Pep440.specifier("<2").lowerBound()).isEmpty();
    }

    @Test
    void pythonRequirementsRememberTheirFileAndLine() throws IOException {
        write("svc/requirements.txt", "# deps\n-r base.txt\nflask==3.0.0\nnumpy==1.26.0 \\\n    --hash=sha256:abc\n");
        write("svc/base.txt", "\nrequests>=2.31\n");
        PythonSupport py = new PythonSupport();
        ProjectFiles files = ProjectFiles.scan(project);
        var unit = py.units(files).get(0);
        var declared = py.declared(files, unit);
        var flask = declared.stream().filter(d -> d.req().name().equals("flask")).findFirst().orElseThrow();
        assertThat(flask.source()).isEqualTo("svc/requirements.txt");
        assertThat(flask.line()).isEqualTo(2);
        var requests = declared.stream().filter(d -> d.req().name().equals("requests")).findFirst().orElseThrow();
        assertThat(requests.source()).isEqualTo("svc/base.txt");
        assertThat(requests.line()).isEqualTo(1);
        var numpy = declared.stream().filter(d -> d.req().name().equals("numpy")).findFirst().orElseThrow();
        assertThat(numpy.line()).isEqualTo(-1); // continued + hash-checked: never rewritten
    }

    @Test
    void pythonWheelsAreChosenForTheUsersComputer() {
        assertThat(PythonSupport.wheelScore("six-1.16.0-py2.py3-none-any.whl", WINDOWS)).isPositive();
        assertThat(PythonSupport.wheelScore("numpy-1.26.4-cp312-cp312-win_amd64.whl", WINDOWS)).isPositive();
        assertThat(PythonSupport.wheelScore("numpy-1.26.4-cp312-cp312-manylinux_2_17_x86_64.whl", WINDOWS)).isZero();
        assertThat(PythonSupport.wheelScore("numpy-1.26.4-cp311-cp311-win_amd64.whl", WINDOWS)).isZero();
        assertThat(PythonSupport.wheelScore("cryptography-42.0.5-cp39-abi3-win_amd64.whl", WINDOWS)).isPositive();
        TargetPlatform mac = new TargetPlatform(TargetPlatform.Os.MAC, TargetPlatform.Arch.ARM64, "3.12");
        assertThat(PythonSupport.wheelScore("numpy-1.26.4-cp312-cp312-macosx_11_0_arm64.whl", mac)).isPositive();
        assertThat(PythonSupport.wheelScore("numpy-1.26.4-cp312-cp312-macosx_10_9_x86_64.whl", mac)).isZero();
        // binary for the right Python beats the pure wheel
        assertThat(PythonSupport.wheelScore("x-1-cp312-cp312-win_amd64.whl", WINDOWS))
                .isGreaterThan(PythonSupport.wheelScore("x-1-py3-none-any.whl", WINDOWS));
    }

    // ------------------------------------------------------------------------------------------ Maven

    @Test
    void mavenModulesManagedVersionsAndUserRepository() throws IOException {
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version><packaging>pom</packaging>
                  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.2.5</version></parent>
                  <properties><gson.version>2.10.1</gson.version></properties>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>${gson.version}</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        write("core/pom.xml", """
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId>
                  <dependencies>
                    <dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId></dependency>
                    <dependency><groupId>demo</groupId><artifactId>util</artifactId><version>${project.version}</version></dependency>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.14.0</version><scope>test</scope></dependency>
                  </dependencies>
                </project>
                """);
        write("util/pom.xml", "<project><parent><groupId>demo</groupId><artifactId>root</artifactId><version>1.0</version></parent><artifactId>util</artifactId></project>");
        write(home, ".m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar", "jar");
        write(".orbit/maven-repository/org/springframework/boot/spring-boot-starter-parent/3.2.5/spring-boot-starter-parent-3.2.5.pom", "<project/>");
        DependencyReport r = check();
        Unit core = unit(r, Ecosystem.MAVEN, "core/pom.xml");
        assertThat(item(core, "com.google.code.gson:gson").state()).isEqualTo(DependencyState.INSTALLED); // in ~/.m2
        assertThat(item(core, "com.google.code.gson:gson").required()).isEqualTo("2.10.1");      // managed + property
        assertThat(item(core, "demo:util").state()).isEqualTo(DependencyState.LOCAL);
        assertThat(item(core, "demo:root").state()).isEqualTo(DependencyState.LOCAL);              // parent module
        assertThat(item(core, "org.apache.commons:commons-lang3").state()).isEqualTo(DependencyState.MISSING);
        assertThat(core.status()).isEqualTo(Status.MISSING);
        Unit root = unit(r, Ecosystem.MAVEN, "pom.xml");
        assertThat(item(root, "org.springframework.boot:spring-boot-starter-parent").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(root.installLocation()).isEqualTo(".orbit/maven-repository");
    }

    // ------------------------------------------------------------------------------------------ Gradle

    @Test
    void gradleCoordinatesCatalogPlatformsAndGradleCache() throws IOException {
        write("gradle/libs.versions.toml", """
                [versions]
                okhttp = "4.12.0"
                [libraries]
                okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
                """);
        write("app/build.gradle", """
                buildscript { dependencies { classpath 'com.android.tools.build:gradle:8.3.0' } }
                dependencies {
                    implementation 'com.google.guava:guava:33.1.0-jre' // guava
                    implementation libs.okhttp
                    implementation libs.does.not.exist
                    implementation platform('org.junit:junit-bom:5.10.2')
                    testImplementation group: 'junit', name: 'junit', version: '4.13.2'
                    implementation project(':core')
                }
                """);
        write(home, ".gradle/caches/modules-2/files-2.1/com.google.guava/guava/33.1.0-jre/abc123/guava-33.1.0-jre.jar", "jar");
        Unit u = unit(check(), Ecosystem.GRADLE, "app/build.gradle");
        assertThat(item(u, "com.google.guava:guava").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(item(u, "com.squareup.okhttp3:okhttp").required()).isEqualTo("4.12.0");
        assertThat(item(u, "com.squareup.okhttp3:okhttp").state()).isEqualTo(DependencyState.MISSING);
        assertThat(item(u, "org.junit:junit-bom").scope()).isEqualTo("platform");
        assertThat(item(u, "junit:junit").scope()).isEqualTo("test");
        assertThat(item(u, "libs.does.not.exist").state()).isEqualTo(DependencyState.UNSUPPORTED);
        assertThat(u.items()).noneMatch(i -> i.name().contains("com.android.tools.build")); // buildscript classpath
    }

    @Test
    void gradleReactNativeAppTakesTheReactNativeVersionFromNodeModules() throws IOException {
        write("mobile/package.json", "{\"dependencies\":{\"react-native\":\"0.81.5\"}}");
        write("mobile/node_modules/react-native/package.json", "{\"name\":\"react-native\",\"version\":\"0.81.5\"}");
        write("mobile/android/app/build.gradle", """
                apply plugin: "com.android.application"
                apply plugin: "com.facebook.react"
                dependencies {
                    implementation("com.facebook.react:react-android")
                    implementation("com.facebook.react:hermes-android")
                }
                """);
        write(".orbit/maven-repository/com/facebook/react/react-android/0.81.5/react-android-0.81.5.aar", "aar");
        Unit u = unit(check(), Ecosystem.GRADLE, "mobile/android/app/build.gradle");
        assertThat(item(u, "com.facebook.react:react-android").required()).isEqualTo("0.81.5");
        assertThat(item(u, "com.facebook.react:react-android").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(item(u, "com.facebook.react:hermes-android").required()).isEqualTo("0.81.5");
        assertThat(item(u, "com.facebook.react:hermes-android").state()).isEqualTo(DependencyState.MISSING);
    }

    // ------------------------------------------------------------------------------------------ NuGet

    @Test
    void nugetPackageReferencesWithCentralVersions() throws IOException {
        write("Directory.Packages.props", """
                <Project><ItemGroup>
                  <PackageVersion Include="Serilog" Version="3.1.1" />
                </ItemGroup></Project>
                """);
        write("src/Api/Api.csproj", """
                <Project Sdk="Microsoft.NET.Sdk.Web">
                  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
                  <ItemGroup>
                    <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
                    <PackageReference Include="Serilog" />
                    <!-- <PackageReference Include="Commented.Out" Version="1.0.0" /> -->
                    <PackageReference Include="Dapper"><Version>2.1.35</Version></PackageReference>
                  </ItemGroup>
                </Project>
                """);
        write(".orbit/nuget-packages/newtonsoft.json/13.0.3/.nupkg.metadata", "{}");
        write(home, ".nuget/packages/serilog/2.12.0/serilog.2.12.0.nupkg", "nupkg");
        Unit u = unit(check(), Ecosystem.NUGET, "src/Api/Api.csproj");
        assertThat(item(u, "Newtonsoft.Json").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(item(u, "Serilog").required()).isEqualTo("3.1.1");
        assertThat(item(u, "Serilog").state()).isEqualTo(DependencyState.OUTDATED);
        assertThat(item(u, "Dapper").state()).isEqualTo(DependencyState.MISSING);
        assertThat(u.items()).noneMatch(i -> i.name().equals("Commented.Out"));
        assertThat(NuGetSupport.rank("net8.0", "net6.0")).isGreaterThan(NuGetSupport.rank("net8.0", ".NETStandard2.0"));
        assertThat(NuGetSupport.rank("net8.0", "net9.0")).isNegative();
        assertThat(NuGetSupport.rank("net48", ".NETFramework4.6.2")).isPositive();
    }

    // ------------------------------------------------------------------------------------------ pub

    @Test
    void pubSdkPathGitAndResolvedPackages() throws IOException {
        write("mobile/pubspec.yaml", """
                name: mobile
                dependencies:
                  flutter:
                    sdk: flutter
                  http: ^1.2.0
                  provider: ^6.0.0
                  shared:
                    path: ../shared
                  forked:
                    git: https://github.com/someone/forked.git
                dev_dependencies:
                  lints: ^3.0.0
                """);
        write("mobile/.dart_tool/package_config.json", """
                {"configVersion":2,"packages":[
                 {"name":"http","rootUri":"file:///C:/Users/x/AppData/Local/Pub/Cache/hosted/pub.dev/http-1.2.1","packageUri":"lib/"}]}
                """);
        write(".orbit/pub-cache/hosted/pub.dev/lints-3.0.0/pubspec.yaml", "name: lints\n");
        Unit u = unit(check(), Ecosystem.PUB, "mobile/pubspec.yaml");
        assertThat(item(u, "flutter").state()).isEqualTo(DependencyState.SDK);
        assertThat(item(u, "http").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(item(u, "http").installed()).isEqualTo("1.2.1");
        assertThat(item(u, "lints").state()).isEqualTo(DependencyState.INSTALLED);
        assertThat(item(u, "provider").state()).isEqualTo(DependencyState.MISSING);
        assertThat(item(u, "shared").state()).isEqualTo(DependencyState.LOCAL);
        assertThat(item(u, "forked").state()).isEqualTo(DependencyState.UNSUPPORTED);
    }

    // ------------------------------------------------------------------------------------------ project level

    @Test
    void nativeOnlyAndEmptyProjectsNeedNothing() throws IOException {
        assertThat(check().status()).isEqualTo(Status.NO_DEPENDENCIES);
        write("CMakeLists.txt", "project(x)\n");
        DependencyReport r = check();
        assertThat(r.status()).isEqualTo(Status.NO_DEPENDENCIES);
        assertThat(unit(r, Ecosystem.NATIVE, "CMakeLists.txt").message()).contains("system");
    }

    @Test
    void everythingInstalledIsGreen() throws IOException {
        write("package.json", "{\"dependencies\":{\"a\":\"^1.0.0\"}}");
        write("node_modules/a/package.json", "{\"version\":\"1.4.0\"}");
        DependencyReport r = check();
        assertThat(r.status()).isEqualTo(Status.INSTALLED);
        assertThat(r.totalCount()).isEqualTo(1);
        assertThat(r.missingCount()).isZero();
    }

    @Test
    void archivesNeverWriteOutsideTheTarget() {
        Path root = project.toAbsolutePath();
        assertThat(Archives.safe(root, "../evil.txt")).isNull();
        assertThat(Archives.safe(root, "a/../../evil.txt")).isNull();
        assertThat(Archives.safe(root, "C:/Windows/evil.txt")).isNull();
        assertThat(Archives.safe(root, "/etc/passwd")).isEqualTo(root.resolve("etc/passwd"));
        assertThat(Archives.safe(root, "lib/index.js")).isEqualTo(root.resolve("lib/index.js"));
        assertThat(Archives.stripFirst("package/lib/a.js")).isEqualTo("lib/a.js");
    }

    @Test
    void platformIsReadFromTheHostPath() {
        assertThat(TargetPlatform.fromHostPath("E:\\Projects\\shop").os()).isEqualTo(TargetPlatform.Os.WINDOWS);
        assertThat(TargetPlatform.fromHostPath("/Users/me/code").os()).isEqualTo(TargetPlatform.Os.MAC);
        assertThat(TargetPlatform.fromHostPath("/home/me/code").os()).isEqualTo(TargetPlatform.Os.LINUX);
        assertThat(List.of(WINDOWS.npmOs(), WINDOWS.npmCpu(), WINDOWS.pySysPlatform())).containsExactly("win32", "x64", "win32");
    }
}
