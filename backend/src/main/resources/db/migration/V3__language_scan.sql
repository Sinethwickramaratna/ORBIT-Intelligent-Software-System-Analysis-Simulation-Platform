-- ORBIT V3: language detection (Phase 2, step 1).
--   language                  the programming languages ORBIT recognises
--   language_extensions       which file extensions belong to which language (the scanner reads this table, so no
--                             extension list is hard-coded in Java)
--   project_scan              one row per scan of a project (every scan is kept)
--   project_language_detail   what a scan found per language: files and source-code lines

CREATE TABLE language (
    language_id   UUID PRIMARY KEY NOT NULL,
    language_name VARCHAR(100) NOT NULL,
    CONSTRAINT uq_language_language_name UNIQUE (language_name)
);

CREATE TABLE language_extensions (
    extension_id   UUID PRIMARY KEY NOT NULL,
    extension_type VARCHAR(50) NOT NULL,                       -- lower-case, with its dot: .java
    language_id    UUID NOT NULL REFERENCES language(language_id) ON DELETE CASCADE,
    CONSTRAINT uq_language_extensions_language_extension UNIQUE (language_id, extension_type)
);

CREATE TABLE project_scan (
    scan_id    UUID PRIMARY KEY NOT NULL,
    project_id UUID NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,   -- removing a project removes its scans
    scanned_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_project_scan_project_scanned_at ON project_scan(project_id, scanned_at DESC);

CREATE TABLE project_language_detail (
    scan_id         UUID NOT NULL REFERENCES project_scan(scan_id) ON DELETE CASCADE,
    language_id     UUID NOT NULL REFERENCES language(language_id),
    lines_of_code   INT NOT NULL,
    number_of_files INT NOT NULL,
    CONSTRAINT pk_project_language_detail PRIMARY KEY (scan_id, language_id),
    CONSTRAINT ck_project_language_detail_lines CHECK (lines_of_code >= 0),
    CONSTRAINT ck_project_language_detail_files CHECK (number_of_files >= 0)
);
CREATE INDEX idx_project_language_detail_language ON project_language_detail(language_id);

-- Initial languages and their extensions (add more rows here or in a later migration to teach ORBIT new languages).
INSERT INTO language (language_id, language_name) VALUES
    ('0a1b2c3d-0001-4000-8000-000000000001', 'Java'),
    ('0a1b2c3d-0001-4000-8000-000000000002', 'Python'),
    ('0a1b2c3d-0001-4000-8000-000000000003', 'JavaScript'),
    ('0a1b2c3d-0001-4000-8000-000000000004', 'TypeScript');

INSERT INTO language_extensions (extension_id, extension_type, language_id) VALUES
    ('0a1b2c3d-0002-4000-8000-000000000001', '.java', '0a1b2c3d-0001-4000-8000-000000000001'),
    ('0a1b2c3d-0002-4000-8000-000000000002', '.py',   '0a1b2c3d-0001-4000-8000-000000000002'),
    ('0a1b2c3d-0002-4000-8000-000000000003', '.js',   '0a1b2c3d-0001-4000-8000-000000000003'),
    ('0a1b2c3d-0002-4000-8000-000000000004', '.jsx',  '0a1b2c3d-0001-4000-8000-000000000003'),
    ('0a1b2c3d-0002-4000-8000-000000000005', '.mjs',  '0a1b2c3d-0001-4000-8000-000000000003'),
    ('0a1b2c3d-0002-4000-8000-000000000006', '.cjs',  '0a1b2c3d-0001-4000-8000-000000000003'),
    ('0a1b2c3d-0002-4000-8000-000000000007', '.ts',   '0a1b2c3d-0001-4000-8000-000000000004'),
    ('0a1b2c3d-0002-4000-8000-000000000008', '.tsx',  '0a1b2c3d-0001-4000-8000-000000000004');
