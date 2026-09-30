CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    username VARCHAR(40) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'REPORTER', 'ADMIN')),
    enabled BOOLEAN NOT NULL,
    token_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- Serialize privilege changes so concurrent requests cannot remove the last admin.
CREATE TABLE security_lock (id INTEGER PRIMARY KEY);
INSERT INTO security_lock (id) VALUES (1);
