package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.core;

/** Reports malformed or unsupported archived migration input. */
public final class CoreMigrationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public CoreMigrationException(String message) { super(message); }
    public CoreMigrationException(String message, Throwable cause) { super(message, cause); }
}
