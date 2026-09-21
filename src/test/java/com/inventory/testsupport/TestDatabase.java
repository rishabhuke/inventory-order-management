package com.inventory.testsupport;

import com.inventory.util.DBConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives every database-backed test a brand-new SQLite file (schema + sample data).
 *
 * The static block points DBConnection at a TEST database file BEFORE DBConnection is first
 * used (DBConnection reads the URL once, when its class is loaded). The safety check in reset()
 * refuses to delete anything that is not clearly a test database, so a mis-configured run can
 * never wipe the real inventory.db.
 */
public final class TestDatabase {

    static {
        if (System.getProperty("inventory.db.url") == null) {
            System.setProperty("inventory.db.url", "jdbc:sqlite:target/test-inventory.db");
        }
    }

    private TestDatabase() {
    }

    /** Deletes the test database file and recreates it with the schema and sample data. */
    public static void reset() {
        String url = System.getProperty("inventory.db.url");
        if (!url.startsWith("jdbc:sqlite:") || !url.contains("test")) {
            throw new IllegalStateException("Refusing to reset a database that is not a test database: " + url);
        }
        Path file = Path.of(url.substring("jdbc:sqlite:".length()));
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + "-journal"));
        } catch (IOException e) {
            throw new IllegalStateException("Could not reset the test database", e);
        }
        DBConnection.initializeDatabase();
    }
}
