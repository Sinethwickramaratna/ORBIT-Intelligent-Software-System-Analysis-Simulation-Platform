package com.orbit.backend.database;

public enum DatabaseState {
    /** No DB username/password saved yet. */
    NOT_CONFIGURED,
    /** Credentials known; waiting for PostgreSQL, then running migrations. */
    CONNECTING,
    /** Connected and schema up to date. */
    READY,
    /** Gave up; see {@code message}. */
    FAILED
}
