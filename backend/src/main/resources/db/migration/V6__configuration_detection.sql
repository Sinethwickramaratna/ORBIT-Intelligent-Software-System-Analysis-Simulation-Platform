-- ORBIT V6: configuration detection (Phase 2, step 3).
--   configuration_file            the configuration files ORBIT looks for, by file name (matching ignores case; the
--                                 scanner reads this table, so no file name is hard-coded in Java)
--   project_configuration_detail  where a scan found them: one row per file, with its path relative to the project

CREATE TABLE configuration_file (
    configuration_file_id UUID PRIMARY KEY NOT NULL,
    file_name             VARCHAR(255) NOT NULL,
    CONSTRAINT uq_configuration_file_file_name UNIQUE (file_name)
);

CREATE TABLE project_configuration_detail (
    scan_id               UUID NOT NULL REFERENCES project_scan(scan_id) ON DELETE CASCADE,
    configuration_file_id UUID NOT NULL REFERENCES configuration_file(configuration_file_id),
    file_path             VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_project_configuration_detail PRIMARY KEY (scan_id, configuration_file_id, file_path)
);
CREATE INDEX idx_project_configuration_detail_file ON project_configuration_detail(configuration_file_id);

INSERT INTO configuration_file (configuration_file_id, file_name) VALUES
    ('0c0f0001-0001-4000-8000-000000000001', 'application.yml'),
    ('0c0f0001-0001-4000-8000-000000000002', 'application.yaml'),
    ('0c0f0001-0001-4000-8000-000000000003', 'application.properties'),
    ('0c0f0001-0001-4000-8000-000000000004', 'Dockerfile'),
    ('0c0f0001-0001-4000-8000-000000000005', 'docker-compose.yml'),
    ('0c0f0001-0001-4000-8000-000000000006', 'docker-compose.yaml'),
    ('0c0f0001-0001-4000-8000-000000000007', 'package.json'),
    ('0c0f0001-0001-4000-8000-000000000008', 'requirements.txt');
