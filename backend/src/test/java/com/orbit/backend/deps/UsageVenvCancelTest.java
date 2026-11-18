package com.orbit.backend.deps;

import com.orbit.backend.deps.DependencyReport.Item;
import com.orbit.backend.deps.DependencyReport.Status;
import com.orbit.backend.deps.DependencyReport.Unit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unused dependencies are not downloaded, Python gets a virtual environment, and Cancel stops downloads at once. */
class UsageVenvCancelTest {

    @TempDir
    Path project;

    @TempDir
    Path home;

    private static final TargetPlatform WINDOWS = new TargetPlatform(TargetPlatform.Os.WINDOWS, TargetPlatform.Arch.X64, "3.12");

    private DependencyReport check() throws IOException {
        return new DependencyEngine(DependencySettings.defaults().withUserHome(home), WINDOWS).check(project);
    }

    private void write(String rel, String content) throws IOException {
        Path p = project.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static Unit unit(DependencyReport r, Ecosystem e) {
        return r.units().stream().filter(u -> u.ecosystem() == e).findFirst().orElseThrow();
    }

    private static Item item(Unit u, String name) {
        return u.items().stream().filter(i -> i.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no item " + name + " in " + u.items()));
    }

    // ------------------------------------------------------------------------------------------ Python usage

    @Test
    void pythonRequirementsTheCodeDoesNotImportAreNotNeeded() throws IOException {
        write("requirements.txt", """
                PyYAML==6.0.1
                beautifulsoup4>=4.12
                djangorestframework==3.15.1
                psycopg2-binary==2.9.9
                google-cloud-storage==2.16.0
                requests==2.31.0
                matplotlib==3.8.0
                pytest==8.0.0
                pytest-cov==5.0.0
                gunicorn==22.0.0
                black==24.1.0
                """);
        write("app/main.py", """
                import yaml, os
                from bs4 import BeautifulSoup
                from google.cloud import storage
                from .local import thing
                """);
        write("app/settings.py", """
                INSTALLED_APPS = ['django.contrib.admin', 'rest_framework']
                DATABASES = {'default': {'ENGINE': 'django.db.backends.postgresql'}}
                """);
        write("tests/test_main.py", "def test_x():\n    assert True\n");
        write("Procfile", "web: gunicorn app.wsgi\n");
        write("pyproject.toml", "[tool.black]\nline-length = 100\n");
        Unit u = unit(check(), Ecosystem.PYTHON);
        for (String used : List.of("PyYAML", "beautifulsoup4", "djangorestframework", "psycopg2-binary", "google-cloud-storage",
                "pytest", "pytest-cov", "gunicorn", "black")) {
            assertThat(item(u, used).state()).as(used).isEqualTo(DependencyState.MISSING);
        }
        assertThat(item(u, "requests").state()).isEqualTo(DependencyState.UNUSED);
        assertThat(item(u, "matplotlib").state()).isEqualTo(DependencyState.UNUSED);
        assertThat(u.message()).contains("2 requirements are not used by the code");
    }

    @Test
    void pythonUnitIsGreenWhenEverythingTheCodeUsesIsInstalled() throws IOException {
        write("requirements.txt", "requests==2.31.0\nnumpy==1.26.4\n");
        write("run.py", "import requests\n");
        write(".venv/pyvenv.cfg", "version = 3.12.1\n");
        write(".venv/Lib/site-packages/requests-2.31.0.dist-info/METADATA", "Name: requests\n");
        DependencyReport r = check();
        assertThat(unit(r, Ecosystem.PYTHON).status()).isEqualTo(Status.INSTALLED);
        assertThat(item(unit(r, Ecosystem.PYTHON), "numpy").state()).isEqualTo(DependencyState.UNUSED);
        assertThat(r.status()).isEqualTo(Status.INSTALLED);
        assertThat(r.totalCount()).isEqualTo(1);
        assertThat(r.unusedCount()).isEqualTo(1);
    }

    @Test
    void withoutAnyPythonCodeEveryRequirementCounts() throws IOException {
        write("requirements.txt", "requests==2.31.0\n");
        assertThat(item(unit(check(), Ecosystem.PYTHON), "requests").state()).isEqualTo(DependencyState.MISSING);
    }

    @Test
    void notebooksCountAsCode() throws IOException {
        write("requirements.txt", "pandas==2.2.0\nscikit-learn==1.4.0\nipykernel==6.29.0\nseaborn==0.13.0\n");
        write("analysis.ipynb", """
                {"cells":[{"cell_type":"code","source":["import pandas as pd\\n","from sklearn.linear_model import LinearRegression\\n"]},
                          {"cell_type":"markdown","source":["import seaborn"]}]}
                """);
        Unit u = unit(check(), Ecosystem.PYTHON);
        assertThat(item(u, "pandas").state()).isEqualTo(DependencyState.MISSING);
        assertThat(item(u, "scikit-learn").state()).isEqualTo(DependencyState.MISSING);
        assertThat(item(u, "ipykernel").state()).isEqualTo(DependencyState.MISSING);
        assertThat(item(u, "seaborn").state()).isEqualTo(DependencyState.UNUSED);
    }

    @Test
    void venvWithAnyNameIsFound() throws IOException {
        write("requirements.txt", "requests==2.31.0\n");
        write("run.py", "import requests\n");
        write("my-env/pyvenv.cfg", "version = 3.11.4\n");
        write("my-env/Lib/site-packages/requests-2.31.0.dist-info/METADATA", "Name: requests\n");
        Unit u = unit(check(), Ecosystem.PYTHON);
        assertThat(u.status()).isEqualTo(Status.INSTALLED);
        assertThat(u.installLocation()).isEqualTo("my-env");
    }

    @Test
    void setupCreatesAVirtualEnvironmentInTheProject() throws IOException {
        // the only requirement is unused, so nothing is downloaded: the venv is created all the same
        write("requirements.txt", "requests==2.31.0\n");
        write("run.py", "print('hello')\n");
        TargetPlatform here = TargetPlatform.current();
        ProjectFiles files = ProjectFiles.scan(project);
        PythonSupport python = new PythonSupport();
        try (InstallContext ctx = new InstallContext(DependencySettings.defaults().withUserHome(home), here, SetupProgress.NONE)) {
            python.install(files, python.units(files).get(0), ctx);
        }
        Path venv = project.resolve(".venv");
        assertThat(venv.resolve("pyvenv.cfg")).isRegularFile();
        assertThat(PythonSupport.sitePackagesOf(venv)).isPresent();
        assertThat(Files.readString(venv.resolve("pyvenv.cfg"))).contains("version");
    }

    // ------------------------------------------------------------------------------------------ npm usage

    @Test
    void npmDependenciesTheCodeDoesNotUseAreNotNeeded() throws IOException {
        write("package.json", """
                {"name":"web","scripts":{"build":"tsc -p . && vite build","lint":"eslint src"},
                 "dependencies":{"react":"^18.2.0","react-dom":"^18.2.0","lodash":"^4.17.0","left-pad":"^1.3.0",
                   "@mui/material":"^5.0.0","bootstrap":"^5.3.0","moment":"^2.0.0"},
                 "devDependencies":{"typescript":"~5.4.0","vite":"^5.0.0","eslint":"^9.0.0","eslint-plugin-react":"^7.0.0",
                   "@types/react":"^18.0.0","@types/node":"^20.0.0","@types/moment":"^2.0.0","jest":"^29.0.0","sass":"^1.0.0"}}
                """);
        write("src/App.tsx", """
                import { map } from 'lodash/fp';
                import Button from "@mui/material/Button";
                const Lazy = React.lazy(() => import('./Lazy'));
                export default function App() { return <Button/>; }
                """);
        write("src/styles.scss", "@import 'bootstrap/scss/bootstrap';\n");
        write("eslint.config.js", "import react from 'eslint-plugin-react';\nexport default [{ plugins: { react } }];\n");
        Unit u = unit(check(), Ecosystem.NPM);
        for (String used : List.of("react", "react-dom", "lodash", "@mui/material", "bootstrap", "typescript", "vite", "eslint",
                "eslint-plugin-react", "@types/react", "@types/node", "sass")) {
            assertThat(item(u, used).state()).as(used).isEqualTo(DependencyState.MISSING);
        }
        for (String unused : List.of("left-pad", "moment", "@types/moment", "jest")) {
            assertThat(item(u, unused).state()).as(unused).isEqualTo(DependencyState.UNUSED);
        }
    }

    @Test
    void npmLockfileLeavesOutWhatOnlyUnusedDependenciesNeed() throws IOException {
        write("package.json", "{\"name\":\"app\",\"dependencies\":{\"express\":\"^4.0.0\",\"moment\":\"^2.0.0\"}}");
        write("server.js", "const express = require('express');\n");
        write("package-lock.json", """
                {"lockfileVersion":3,"packages":{
                  "":{"name":"app","dependencies":{"express":"^4.0.0","moment":"^2.0.0"}},
                  "node_modules/express":{"version":"4.19.2","dependencies":{"debug":"2.6.9"}},
                  "node_modules/debug":{"version":"2.6.9","dependencies":{"ms":"2.0.0"}},
                  "node_modules/ms":{"version":"2.0.0"},
                  "node_modules/moment":{"version":"2.30.1","dependencies":{"moment-helper":"1.0.0"}},
                  "node_modules/moment-helper":{"version":"1.0.0","dependencies":{"ms":"2.0.0"}}
                }}
                """);
        CodeUsage usage = CodeUsage.scan(project);
        List<String> paths = NpmSupport.lockEntries(project, "", WINDOWS, usage).orElseThrow().stream()
                .map(NpmSupport.LockEntry::path).toList();
        assertThat(paths).containsExactlyInAnyOrder("node_modules/express", "node_modules/debug", "node_modules/ms");
        // without the filter everything in the lockfile is installed
        assertThat(NpmSupport.lockEntries(project, "", WINDOWS).orElseThrow()).hasSize(5);
        Unit u = unit(check(), Ecosystem.NPM);
        assertThat(u.message()).contains("3 packages the code needs").contains("1 dependency is not used");
        assertThat(item(u, "moment").state()).isEqualTo(DependencyState.UNUSED);
    }

    @Test
    void javascriptImportSpecifiersMapToPackages() {
        assertThat(CodeUsage.jsImports("""
                import a from '@scope/pkg/sub/path';
                import 'side-effect';
                export { x } from "re-export";
                const b = require("cjs-pkg/lib");
                const c = await import(`dyn`);
                import d from './relative';
                import e from 'node:fs';
                import f from '@/alias';
                """)).containsExactlyInAnyOrder("@scope/pkg", "side-effect", "re-export", "cjs-pkg", "dyn");
    }

    // ------------------------------------------------------------------------------------------ Dart usage

    @Test
    void pubDependenciesTheCodeDoesNotImportAreNotNeeded() throws IOException {
        write("pubspec.yaml", """
                name: app
                dependencies:
                  flutter:
                    sdk: flutter
                  http: ^1.2.0
                  provider: ^6.0.0
                  cupertino_icons: ^1.0.6
                  json_annotation: ^4.8.0
                dev_dependencies:
                  flutter_lints: ^3.0.0
                  build_runner: ^2.4.0
                  json_serializable: ^6.7.0
                  mockito: ^5.4.0
                flutter_launcher_icons:
                  android: true
                """);
        write("lib/main.dart", """
                import 'package:flutter/material.dart';
                import 'package:http/http.dart' as http;
                import 'package:json_annotation/json_annotation.dart';
                part 'main.g.dart';
                @JsonSerializable()
                class A {}
                """);
        write("analysis_options.yaml", "include: package:flutter_lints/flutter.yaml\n");
        Unit u = unit(check(), Ecosystem.PUB);
        for (String used : List.of("http", "json_annotation", "flutter_lints", "build_runner", "json_serializable")) {
            assertThat(item(u, used).state()).as(used).isEqualTo(DependencyState.MISSING);
        }
        for (String unused : List.of("provider", "cupertino_icons", "mockito")) {
            assertThat(item(u, unused).state()).as(unused).isEqualTo(DependencyState.UNUSED);
        }
    }

    // ------------------------------------------------------------------------------------------ Cancel

    /** A server that sends a few bytes and then stalls (or trickles a byte every 100 ms), like a slow registry. */
    private static HttpServer stallingServer(AtomicBoolean release, boolean trickle) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(new byte[1024]);
                out.flush();
                long until = System.currentTimeMillis() + 30_000;
                while (!release.get() && System.currentTimeMillis() < until) {
                    try {
                        Thread.sleep(trickle ? 100 : 50);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (trickle) {
                        out.write(1);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // client went away
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        return server;
    }

    private static final class Cancellable implements SetupProgress {
        volatile boolean cancelled;
        final List<Runnable> hooks = new ArrayList<>();

        @Override
        public void unit(String title, int index, int total) {
        }

        @Override
        public void step(String text, int done, int total) {
        }

        @Override
        public void log(String line) {
        }

        @Override
        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public synchronized void onCancel(Runnable action) {
            hooks.add(action);
        }

        synchronized void cancel() {
            cancelled = true;
            hooks.forEach(Runnable::run);
        }
    }

    @Test
    void cancelAbortsADownloadThatIsInProgress() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        HttpServer server = stallingServer(release, true);
        try {
            Cancellable progress = new Cancellable();
            InstallContext ctx = new InstallContext(DependencySettings.defaults(), WINDOWS, progress);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/big.tgz";
            CompletableFuture<Throwable> download = CompletableFuture.supplyAsync(() -> {
                try {
                    ctx.downloader().file(url, null, project.resolve("big.tgz"));
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Thread.sleep(500);
            assertThat(download).isNotDone(); // stalled in the middle of the file
            long t0 = System.nanoTime();
            progress.cancel();
            Throwable t = download.get(5, TimeUnit.SECONDS);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(3000);
            assertThat(t).isInstanceOf(Downloader.CancelledException.class);
            assertThat(project.resolve("big.tgz")).doesNotExist();
            assertThat(project.resolve("big.tgz.part")).doesNotExist();
            ctx.close();
        } finally {
            release.set(true);
            server.stop(0);
        }
    }

    @Test
    void cancelStopsTheDownloadPoolWithoutWaitingForQueuedPackages() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        HttpServer server = stallingServer(release, false); // reads blocked for good: only the pool can give up
        try {
            Cancellable progress = new Cancellable();
            InstallContext ctx = new InstallContext(DependencySettings.defaults(), WINDOWS, progress);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/pkg";
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) { // far more than the download threads: most of them are still queued
                int n = i;
                tasks.add(() -> {
                    ctx.checkCancelled();
                    try {
                        ctx.downloader().file(url, null, project.resolve("p" + n));
                    } catch (IOException e) {
                        ctx.fail("p" + n + ": " + e.getMessage());
                    }
                    return null;
                });
            }
            CompletableFuture<Throwable> run = CompletableFuture.supplyAsync(() -> {
                try {
                    ctx.runAll(tasks);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            Thread.sleep(500);
            assertThat(run).isNotDone();
            long t0 = System.nanoTime();
            progress.cancel();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("Cancel itself never waits").isLessThan(500);
            Throwable t = run.get(5, TimeUnit.SECONDS);
            assertThat(t).isInstanceOf(IOException.class).hasMessageContaining("cancelled");
            assertThat(ctx.failures()).as("what Cancel cut off is not a failure").isEmpty();
            ctx.close();
        } finally {
            release.set(true);
            server.stop(0);
        }
    }

    @Test
    void cancelEndsATlsReadThatIsBlocked() throws Exception {
        // a server that accepts and never answers the TLS handshake: the client's read blocks
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            CompletableFuture.runAsync(() -> {
                try {
                    java.net.Socket s = server.accept();
                    Thread.sleep(30_000);
                    s.close();
                } catch (Exception ignored) {
                    // test over
                }
            });
            Downloader downloader = new Downloader(1 << 20);
            java.net.Socket plain = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort());
            java.net.Socket tls = downloader.socketFactory().createSocket(plain, "localhost", server.getLocalPort(), true);
            CompletableFuture<Throwable> read = CompletableFuture.supplyAsync(() -> {
                try {
                    tls.getInputStream().read();
                    return null;
                } catch (Throwable e) {
                    return e;
                }
            });
            Thread.sleep(500);
            assertThat(read).isNotDone();
            long t0 = System.nanoTime();
            downloader.abortAll();
            assertThat(read.get(3, TimeUnit.SECONDS)).isInstanceOf(IOException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2000);
        }
    }

    @Test
    void cancelledSetupStopsBeforeTheNextManifest() throws IOException {
        write("package.json", "{\"name\":\"a\",\"dependencies\":{\"left-pad\":\"^1.3.0\"}}");
        Cancellable progress = new Cancellable();
        progress.cancel();
        assertThatThrownBy(() -> new DependencyEngine(DependencySettings.defaults().withUserHome(home), WINDOWS)
                .setup(project, progress, true, true)).isInstanceOf(IOException.class).hasMessageContaining("cancelled");
        assertThat(project.resolve("node_modules")).doesNotExist();
    }
}
