-- ORBIT V7: framework detection (Phase 2, step 4).
--   framework                   the frameworks ORBIT can recognise (Spring Boot, FastAPI, Express, ...)
--   framework_dependency        the dependency names that prove a framework, per package manager. The scanner reads this
--                               table, so no framework or dependency name is hard-coded in Java. dependency_name is stored
--                               lower-case (Python names normalised: _ and . become -); a trailing * is a prefix rule
--                               (spring-boot-* matches spring-boot-starter-web). package_manager is the dependency
--                               ecosystem: maven (Maven AND Gradle use Maven coordinates), pip, npm, pub, nuget.
--   project_framework_detail    which frameworks a scan found (composite key scan + framework)
--   project_framework_evidence  where the proof is: manifest file, line and column of the dependency. Besides the
--                               requested columns it stores framework_id and the matched dependency name, so each piece of
--                               evidence belongs to one detected framework.

CREATE TABLE framework (
    framework_id UUID PRIMARY KEY NOT NULL,
    name         VARCHAR(100) NOT NULL,
    CONSTRAINT uq_framework_name UNIQUE (name)
);

CREATE TABLE framework_dependency (
    framework_dependency_id UUID PRIMARY KEY NOT NULL,
    framework_id            UUID NOT NULL REFERENCES framework(framework_id) ON DELETE CASCADE,
    dependency_name         VARCHAR(255) NOT NULL,
    package_manager         VARCHAR(50) NOT NULL,
    CONSTRAINT uq_framework_dependency_name UNIQUE (dependency_name)
);
CREATE INDEX idx_framework_dependency_framework ON framework_dependency(framework_id);

CREATE TABLE project_framework_detail (
    scan_id      UUID NOT NULL REFERENCES project_scan(scan_id) ON DELETE CASCADE,
    framework_id UUID NOT NULL REFERENCES framework(framework_id),
    CONSTRAINT pk_project_framework_detail PRIMARY KEY (scan_id, framework_id)
);
CREATE INDEX idx_project_framework_detail_framework ON project_framework_detail(framework_id);

CREATE TABLE project_framework_evidence (
    evidence_id     UUID PRIMARY KEY NOT NULL,
    scan_id         UUID NOT NULL REFERENCES project_scan(scan_id) ON DELETE CASCADE,
    framework_id    UUID NOT NULL,
    dependency_name VARCHAR(255) NOT NULL,
    file_path       VARCHAR(1000) NOT NULL,
    line_number     INT NOT NULL,
    col_number      INT NOT NULL,
    CONSTRAINT fk_project_framework_evidence_detail FOREIGN KEY (scan_id, framework_id)
        REFERENCES project_framework_detail(scan_id, framework_id) ON DELETE CASCADE,
    CONSTRAINT ck_project_framework_evidence_position CHECK (line_number >= 1 AND col_number >= 1)
);
CREATE INDEX idx_project_framework_evidence_scan ON project_framework_evidence(scan_id, framework_id);

INSERT INTO framework (framework_id, name) VALUES
    ('0f0a0001-0001-4000-8000-000000000001', 'Spring Boot'),
    ('0f0a0001-0001-4000-8000-000000000002', 'Spring Framework'),
    ('0f0a0001-0001-4000-8000-000000000003', 'Spring Data'),
    ('0f0a0001-0001-4000-8000-000000000004', 'Quarkus'),
    ('0f0a0001-0001-4000-8000-000000000005', 'Micronaut'),
    ('0f0a0001-0001-4000-8000-000000000006', 'Vert.x'),
    ('0f0a0001-0001-4000-8000-000000000007', 'Dropwizard'),
    ('0f0a0001-0001-4000-8000-000000000008', 'Apache Struts'),
    ('0f0a0001-0001-4000-8000-000000000009', 'FastAPI'),
    ('0f0a0001-0001-4000-8000-00000000000a', 'Flask'),
    ('0f0a0001-0001-4000-8000-00000000000b', 'Django'),
    ('0f0a0001-0001-4000-8000-00000000000c', 'Tornado'),
    ('0f0a0001-0001-4000-8000-00000000000d', 'Pyramid'),
    ('0f0a0001-0001-4000-8000-00000000000e', 'Streamlit'),
    ('0f0a0001-0001-4000-8000-00000000000f', 'TensorFlow'),
    ('0f0a0001-0001-4000-8000-000000000010', 'PyTorch'),
    ('0f0a0001-0001-4000-8000-000000000011', 'Express'),
    ('0f0a0001-0001-4000-8000-000000000012', 'React'),
    ('0f0a0001-0001-4000-8000-000000000013', 'Next.js'),
    ('0f0a0001-0001-4000-8000-000000000014', 'Vue.js'),
    ('0f0a0001-0001-4000-8000-000000000015', 'Angular'),
    ('0f0a0001-0001-4000-8000-000000000016', 'NestJS'),
    ('0f0a0001-0001-4000-8000-000000000017', 'Svelte'),
    ('0f0a0001-0001-4000-8000-000000000018', 'Nuxt'),
    ('0f0a0001-0001-4000-8000-000000000019', 'Fastify'),
    ('0f0a0001-0001-4000-8000-00000000001a', 'Koa'),
    ('0f0a0001-0001-4000-8000-00000000001b', 'Electron'),
    ('0f0a0001-0001-4000-8000-00000000001c', 'React Native'),
    ('0f0a0001-0001-4000-8000-00000000001d', 'Flutter'),
    ('0f0a0001-0001-4000-8000-00000000001e', 'ASP.NET Core');

INSERT INTO framework_dependency (framework_dependency_id, framework_id, dependency_name, package_manager) VALUES
    ('0f0a0002-0001-4000-8000-000000000001', '0f0a0001-0001-4000-8000-000000000001', 'spring-boot-*', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000002', '0f0a0001-0001-4000-8000-000000000001', 'org.springframework.boot', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000003', '0f0a0001-0001-4000-8000-000000000002', 'spring-web', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000004', '0f0a0001-0001-4000-8000-000000000002', 'spring-webmvc', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000005', '0f0a0001-0001-4000-8000-000000000002', 'spring-webflux', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000006', '0f0a0001-0001-4000-8000-000000000002', 'spring-context', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000007', '0f0a0001-0001-4000-8000-000000000003', 'spring-data-*', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000008', '0f0a0001-0001-4000-8000-000000000004', 'quarkus-*', 'maven'),
    ('0f0a0002-0001-4000-8000-000000000009', '0f0a0001-0001-4000-8000-000000000004', 'io.quarkus', 'maven'),
    ('0f0a0002-0001-4000-8000-00000000000a', '0f0a0001-0001-4000-8000-000000000005', 'micronaut-*', 'maven'),
    ('0f0a0002-0001-4000-8000-00000000000b', '0f0a0001-0001-4000-8000-000000000005', 'io.micronaut.application', 'maven'),
    ('0f0a0002-0001-4000-8000-00000000000c', '0f0a0001-0001-4000-8000-000000000006', 'vertx-*', 'maven'),
    ('0f0a0002-0001-4000-8000-00000000000d', '0f0a0001-0001-4000-8000-000000000007', 'dropwizard-*', 'maven'),
    ('0f0a0002-0001-4000-8000-00000000000e', '0f0a0001-0001-4000-8000-000000000008', 'struts2-core', 'maven'),
    ('0f0a0002-0001-4000-8000-00000000000f', '0f0a0001-0001-4000-8000-000000000009', 'fastapi', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000010', '0f0a0001-0001-4000-8000-00000000000a', 'flask', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000011', '0f0a0001-0001-4000-8000-00000000000b', 'django', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000012', '0f0a0001-0001-4000-8000-00000000000c', 'tornado', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000013', '0f0a0001-0001-4000-8000-00000000000d', 'pyramid', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000014', '0f0a0001-0001-4000-8000-00000000000e', 'streamlit', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000015', '0f0a0001-0001-4000-8000-00000000000f', 'tensorflow', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000016', '0f0a0001-0001-4000-8000-000000000010', 'torch', 'pip'),
    ('0f0a0002-0001-4000-8000-000000000017', '0f0a0001-0001-4000-8000-000000000011', 'express', 'npm'),
    ('0f0a0002-0001-4000-8000-000000000018', '0f0a0001-0001-4000-8000-000000000012', 'react', 'npm'),
    ('0f0a0002-0001-4000-8000-000000000019', '0f0a0001-0001-4000-8000-000000000013', 'next', 'npm'),
    ('0f0a0002-0001-4000-8000-00000000001a', '0f0a0001-0001-4000-8000-000000000014', 'vue', 'npm'),
    ('0f0a0002-0001-4000-8000-00000000001b', '0f0a0001-0001-4000-8000-000000000015', '@angular/core', 'npm'),
    ('0f0a0002-0001-4000-8000-00000000001c', '0f0a0001-0001-4000-8000-000000000016', '@nestjs/core', 'npm'),
    ('0f0a0002-0001-4000-8000-00000000001d', '0f0a0001-0001-4000-8000-000000000017', 'svelte', 'npm'),
    ('0f0a0002-0001-4000-8000-00000000001e', '0f0a0001-0001-4000-8000-000000000018', 'nuxt', 'npm'),
    ('0f0a0002-0001-4000-8000-00000000001f', '0f0a0001-0001-4000-8000-000000000019', 'fastify', 'npm'),
    ('0f0a0002-0001-4000-8000-000000000020', '0f0a0001-0001-4000-8000-00000000001a', 'koa', 'npm'),
    ('0f0a0002-0001-4000-8000-000000000021', '0f0a0001-0001-4000-8000-00000000001b', 'electron', 'npm'),
    ('0f0a0002-0001-4000-8000-000000000022', '0f0a0001-0001-4000-8000-00000000001c', 'react-native', 'npm'),
    ('0f0a0002-0001-4000-8000-000000000023', '0f0a0001-0001-4000-8000-00000000001d', 'flutter', 'pub'),
    ('0f0a0002-0001-4000-8000-000000000024', '0f0a0001-0001-4000-8000-00000000001e', 'microsoft.aspnetcore.*', 'nuget'),
    ('0f0a0002-0001-4000-8000-000000000025', '0f0a0001-0001-4000-8000-00000000001e', 'microsoft.net.sdk.web', 'nuget');
