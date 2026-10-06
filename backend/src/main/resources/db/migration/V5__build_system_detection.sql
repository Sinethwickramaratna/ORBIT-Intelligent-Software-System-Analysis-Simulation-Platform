-- ORBIT V5: build system detection (Phase 2, step 2).
--   build_system            the build systems / dependency managers ORBIT recognises (Maven, Gradle, ...)
--   build_file_type         evidence: which file names identify which build system. A value is an exact file name
--                           (pom.xml) or a "*.ext" pattern (*.csproj), always lower-case; matching ignores case. The
--                           scanner reads this table, so no build file name is hard-coded in Java.
--   project_build_detail    what a scan found: one row per build system detected in that scan
--   project_build_evidence  the evidence files (paths relative to the project) behind each detected build system.
--                           Needed to show "Evidence: pom.xml, settings.gradle"; project_build_detail itself only has
--                           the two requested key columns.

CREATE TABLE build_system (
    build_system_id UUID PRIMARY KEY NOT NULL,
    name            VARCHAR(100) NOT NULL,
    CONSTRAINT uq_build_system_name UNIQUE (name)
);

CREATE TABLE build_file_type (
    build_file_id   UUID PRIMARY KEY NOT NULL,
    file_type       VARCHAR(255) NOT NULL,
    build_system_id UUID NOT NULL REFERENCES build_system(build_system_id) ON DELETE CASCADE,
    CONSTRAINT uq_build_file_type_file_type UNIQUE (file_type)
);
CREATE INDEX idx_build_file_type_build_system ON build_file_type(build_system_id);

CREATE TABLE project_build_detail (
    scan_id         UUID NOT NULL REFERENCES project_scan(scan_id) ON DELETE CASCADE,
    build_system_id UUID NOT NULL REFERENCES build_system(build_system_id),
    CONSTRAINT pk_project_build_detail PRIMARY KEY (scan_id, build_system_id)
);
CREATE INDEX idx_project_build_detail_build_system ON project_build_detail(build_system_id);

CREATE TABLE project_build_evidence (
    scan_id         UUID NOT NULL,
    build_system_id UUID NOT NULL,
    file_path       VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_project_build_evidence PRIMARY KEY (scan_id, build_system_id, file_path),
    CONSTRAINT fk_project_build_evidence_detail FOREIGN KEY (scan_id, build_system_id)
        REFERENCES project_build_detail(scan_id, build_system_id) ON DELETE CASCADE
);

-- Build systems (fixed ids so later migrations and tests can refer to them).
INSERT INTO build_system (build_system_id, name) VALUES
    ('0b1d0001-0001-4000-8000-000000000001', 'Maven'),
    ('0b1d0001-0001-4000-8000-000000000002', 'Gradle'),
    ('0b1d0001-0001-4000-8000-000000000003', 'Ant'),
    ('0b1d0001-0001-4000-8000-000000000004', 'npm/Node ecosystem'),
    ('0b1d0001-0001-4000-8000-000000000005', 'Yarn'),
    ('0b1d0001-0001-4000-8000-000000000006', 'pnpm'),
    ('0b1d0001-0001-4000-8000-000000000007', 'Python dependency management'),
    ('0b1d0001-0001-4000-8000-000000000008', 'Pipenv'),
    ('0b1d0001-0001-4000-8000-000000000009', 'Python packaging'),
    ('0b1d0001-0001-4000-8000-00000000000a', 'Poetry'),
    ('0b1d0001-0001-4000-8000-00000000000b', 'CMake'),
    ('0b1d0001-0001-4000-8000-00000000000c', 'Make'),
    ('0b1d0001-0001-4000-8000-00000000000d', 'Meson'),
    ('0b1d0001-0001-4000-8000-00000000000e', '.NET (MSBuild)'),
    ('0b1d0001-0001-4000-8000-00000000000f', 'NuGet'),
    ('0b1d0001-0001-4000-8000-000000000010', 'Dart pub');

-- Evidence files.
INSERT INTO build_file_type (build_file_id, file_type, build_system_id) VALUES
    ('0b1d0002-0001-4000-8000-000000000001', 'pom.xml', '0b1d0001-0001-4000-8000-000000000001'),
    ('0b1d0002-0001-4000-8000-000000000002', 'build.gradle', '0b1d0001-0001-4000-8000-000000000002'),
    ('0b1d0002-0001-4000-8000-000000000003', 'build.gradle.kts', '0b1d0001-0001-4000-8000-000000000002'),
    ('0b1d0002-0001-4000-8000-000000000004', 'settings.gradle', '0b1d0001-0001-4000-8000-000000000002'),
    ('0b1d0002-0001-4000-8000-000000000005', 'settings.gradle.kts', '0b1d0001-0001-4000-8000-000000000002'),
    ('0b1d0002-0001-4000-8000-000000000006', 'build.xml', '0b1d0001-0001-4000-8000-000000000003'),
    ('0b1d0002-0001-4000-8000-000000000007', 'package.json', '0b1d0001-0001-4000-8000-000000000004'),
    ('0b1d0002-0001-4000-8000-000000000008', 'package-lock.json', '0b1d0001-0001-4000-8000-000000000004'),
    ('0b1d0002-0001-4000-8000-000000000009', 'yarn.lock', '0b1d0001-0001-4000-8000-000000000005'),
    ('0b1d0002-0001-4000-8000-00000000000a', 'pnpm-lock.yaml', '0b1d0001-0001-4000-8000-000000000006'),
    ('0b1d0002-0001-4000-8000-00000000000b', 'pnpm-workspace.yaml', '0b1d0001-0001-4000-8000-000000000006'),
    ('0b1d0002-0001-4000-8000-00000000000c', 'requirements.txt', '0b1d0001-0001-4000-8000-000000000007'),
    ('0b1d0002-0001-4000-8000-00000000000d', 'pipfile', '0b1d0001-0001-4000-8000-000000000008'),
    ('0b1d0002-0001-4000-8000-00000000000e', 'pipfile.lock', '0b1d0001-0001-4000-8000-000000000008'),
    ('0b1d0002-0001-4000-8000-00000000000f', 'pyproject.toml', '0b1d0001-0001-4000-8000-000000000009'),
    ('0b1d0002-0001-4000-8000-000000000010', 'setup.py', '0b1d0001-0001-4000-8000-000000000009'),
    ('0b1d0002-0001-4000-8000-000000000011', 'setup.cfg', '0b1d0001-0001-4000-8000-000000000009'),
    ('0b1d0002-0001-4000-8000-000000000012', 'poetry.lock', '0b1d0001-0001-4000-8000-00000000000a'),
    ('0b1d0002-0001-4000-8000-000000000013', 'cmakelists.txt', '0b1d0001-0001-4000-8000-00000000000b'),
    ('0b1d0002-0001-4000-8000-000000000014', 'makefile', '0b1d0001-0001-4000-8000-00000000000c'),
    ('0b1d0002-0001-4000-8000-000000000015', 'gnumakefile', '0b1d0001-0001-4000-8000-00000000000c'),
    ('0b1d0002-0001-4000-8000-000000000016', 'meson.build', '0b1d0001-0001-4000-8000-00000000000d'),
    ('0b1d0002-0001-4000-8000-000000000017', '*.csproj', '0b1d0001-0001-4000-8000-00000000000e'),
    ('0b1d0002-0001-4000-8000-000000000018', '*.sln', '0b1d0001-0001-4000-8000-00000000000e'),
    ('0b1d0002-0001-4000-8000-000000000019', 'packages.config', '0b1d0001-0001-4000-8000-00000000000f'),
    ('0b1d0002-0001-4000-8000-00000000001a', 'nuget.config', '0b1d0001-0001-4000-8000-00000000000f'),
    ('0b1d0002-0001-4000-8000-00000000001b', 'pubspec.yaml', '0b1d0001-0001-4000-8000-000000000010'),
    ('0b1d0002-0001-4000-8000-00000000001c', 'pubspec.lock', '0b1d0001-0001-4000-8000-000000000010');
