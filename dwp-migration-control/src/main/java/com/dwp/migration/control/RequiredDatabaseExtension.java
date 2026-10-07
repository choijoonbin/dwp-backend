package com.dwp.migration.control;

record RequiredDatabaseExtension(String name, String schema, String version) {
    RequiredDatabaseExtension {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,62}")
                || schema == null || !schema.matches("[a-z][a-z0-9_]{0,62}")
                || version == null || !version.matches("[0-9]+(?:\\.[0-9]+)*")) {
            throw new IllegalArgumentException(
                    "Required database extension declaration is not canonical");
        }
    }
}
