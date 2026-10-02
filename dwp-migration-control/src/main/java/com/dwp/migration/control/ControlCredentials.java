package com.dwp.migration.control;

record ControlCredentials(
        String migrationPassword,
        String runtimePassword,
        String projectionPublisherPassword) {

    ControlCredentials(String migrationPassword, String runtimePassword) {
        this(migrationPassword, runtimePassword, "");
    }

    ControlCredentials {
        requireTemporaryCredential(migrationPassword, "migration");
        requireTemporaryCredential(runtimePassword, "runtime");
        if (!projectionPublisherPassword.isEmpty()) {
            requireTemporaryCredential(
                    projectionPublisherPassword, "projection publisher");
        }
        if (migrationPassword.equals(runtimePassword)
                || migrationPassword.equals(projectionPublisherPassword)
                || runtimePassword.equals(projectionPublisherPassword)) {
            throw new IllegalStateException(
                    "Migration Control temporary credentials must be distinct");
        }
    }

    private static void requireTemporaryCredential(String value, String principalType) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalStateException(
                    "Migration Control temporary " + principalType
                            + " credential is not canonical");
        }
    }
}
