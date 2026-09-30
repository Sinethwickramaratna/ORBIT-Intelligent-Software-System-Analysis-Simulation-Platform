-- ORBIT V1: initial schema (executed automatically on an empty database)

CREATE TABLE users (
    user_id    UUID PRIMARY KEY NOT NULL,
    user_name  VARCHAR(100) NOT NULL,
    password   VARCHAR(255) NOT NULL,          -- Argon2 encoded hash
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_users_user_name UNIQUE (user_name)
);

CREATE TABLE refresh_token_table (
    token_id   UUID PRIMARY KEY NOT NULL,
    token      VARCHAR(255) NOT NULL,          -- SHA-256 hex digest of the opaque refresh token
    user_id    UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expired_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_refresh_token_token UNIQUE (token)
);
CREATE INDEX idx_refresh_token_user_id ON refresh_token_table(user_id);
CREATE INDEX idx_refresh_token_expired_at ON refresh_token_table(expired_at);

-- Key/value application settings (e.g. UI theme chosen on first launch)
CREATE TABLE app_settings (
    setting_key   VARCHAR(100) PRIMARY KEY NOT NULL,
    setting_value VARCHAR(500) NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
