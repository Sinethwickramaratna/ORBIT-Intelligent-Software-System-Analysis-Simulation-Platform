-- ORBIT V8: dependency check (Architecture Reconstruction, step 1).
--   project_dependency_check  the newest dependency check of a project (older checks are replaced): overall status,
--                             counts and the operating system the dependencies were checked / downloaded for
--   project_dependency_unit   one manifest group of that check (a package.json, a pom.xml module, the Python manifests
--                             of one folder, ...): ecosystem, where Setup Dependencies installs it, its status
--   project_dependency_item   one declared dependency of a unit: requirement as written, installed version, state
--                             (INSTALLED, MISSING, OUTDATED, LOCAL, SDK, UNSUPPORTED)

CREATE TABLE project_dependency_check (
    check_id        UUID PRIMARY KEY NOT NULL,
    project_id      UUID NOT NULL REFERENCES projects(project_id) ON DELETE CASCADE,
    checked_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    status          VARCHAR(20) NOT NULL,
    total_count     INT NOT NULL,
    missing_count   INT NOT NULL,
    target_platform VARCHAR(50) NOT NULL,
    CONSTRAINT ck_project_dependency_check_status CHECK (status IN ('INSTALLED', 'MISSING', 'NO_DEPENDENCIES'))
);
CREATE INDEX idx_project_dependency_check_project ON project_dependency_check(project_id, checked_at DESC);

CREATE TABLE project_dependency_unit (
    unit_id          UUID PRIMARY KEY NOT NULL,
    check_id         UUID NOT NULL REFERENCES project_dependency_check(check_id) ON DELETE CASCADE,
    position         INT NOT NULL,
    ecosystem        VARCHAR(20) NOT NULL,
    manifest_path    VARCHAR(2000) NOT NULL,
    install_location VARCHAR(1000),
    status           VARCHAR(20) NOT NULL,
    message          VARCHAR(1000)
);
CREATE INDEX idx_project_dependency_unit_check ON project_dependency_unit(check_id, position);

CREATE TABLE project_dependency_item (
    item_id           UUID PRIMARY KEY NOT NULL,
    unit_id           UUID NOT NULL REFERENCES project_dependency_unit(unit_id) ON DELETE CASCADE,
    position          INT NOT NULL,
    dependency_name   VARCHAR(500) NOT NULL,
    required_version  VARCHAR(500),
    installed_version VARCHAR(255),
    scope             VARCHAR(50),
    state             VARCHAR(20) NOT NULL,
    note              VARCHAR(1000)
);
CREATE INDEX idx_project_dependency_item_unit ON project_dependency_item(unit_id, position);
