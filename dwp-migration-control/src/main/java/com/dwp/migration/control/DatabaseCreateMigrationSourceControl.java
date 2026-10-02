package com.dwp.migration.control;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Verifies an exact migration declaration against its packaged source bytes. */
final class DatabaseCreateMigrationSourceControl {
    private DatabaseCreateMigrationSourceControl() {
    }

    static void requireExact(
            ControlEnvironment environment, DatabaseCreateMigration migration) {
        if (!migration.service().equals(environment.plan().service())) {
            throw new IllegalStateException(
                    "Privileged migration used outside its service Control plan");
        }
        String actual = HexFormat.of().formatHex(sha256().digest(sourceBytes(migration)));
        if (!migration.sourceSha256().equals(actual)) {
            throw new IllegalStateException(
                    "Privileged migration source digest differs: " + migration.fileName());
        }
    }

    private static byte[] sourceBytes(DatabaseCreateMigration migration) {
        String resource = "db/migration/" + migration.fileName();
        try (var input = DatabaseCreateMigrationSourceControl.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Privileged migration is missing: " + resource);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Privileged migration cannot be read: " + resource,
                    exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
