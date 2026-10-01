-- ORBIT V2: projects. One user owns many projects (users 1 --- * projects).

CREATE TABLE projects (
    project_id   UUID PRIMARY KEY NOT NULL,
    project_name VARCHAR(150)  NOT NULL,
    location     VARCHAR(1024) NOT NULL,            -- the project folder, as the user typed it (host path)
    project_type VARCHAR(30)   NOT NULL,            -- enum, see the CHECK constraint (mirrors ProjectType.java)
    description  VARCHAR(2000),                     -- optional
    user_id      UUID          NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_projects_project_type CHECK (project_type IN (
        'WEB_APPLICATION',
        'DESKTOP_APPLICATION',
        'MOBILE_APPLICATION',
        'DISTRIBUTED_SYSTEM',
        'MICROSERVICES_SYSTEM',
        'BACKEND_API',
        'DATA_ML_SYSTEM',
        'EMBEDDED_IOT_SYSTEM',
        'OTHER')),
    -- the same folder cannot be registered twice by the same user
    CONSTRAINT uq_projects_user_location UNIQUE (user_id, location)
);
CREATE INDEX idx_projects_user_id ON projects(user_id);
