package com.inventory.exception;

/**
 * Unchecked wrapper for low-level JDBC failures (SQLException) that the caller
 * cannot meaningfully recover from, such as a missing database file or a broken
 * connection. Wrapping keeps java.sql out of the service and UI layers, and
 * Main catches it once so the program reports the problem instead of crashing.
 */
public class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
