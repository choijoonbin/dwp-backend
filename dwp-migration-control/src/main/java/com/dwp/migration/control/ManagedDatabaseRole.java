package com.dwp.migration.control;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * A non-login database capability role whose lifecycle belongs to Migration Control.
 *
 * <p>The role is deliberately created by the bootstrap principal rather than by a
 * Flyway login. {@code migrationAuthority} is a short-lived membership used only
 * while the service is offline and migrations are running. It is never a
 * steady-state membership.</p>
 */
record ManagedDatabaseRole(
        String name,
        String introducedInVersion,
        boolean migrationAuthority,
        List<String> allowedOwnershipSchemas) {

    ManagedDatabaseRole {
        if (name == null || !name.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(
                    "Managed database role name must be a canonical identifier");
        }
        if (introducedInVersion == null
                || !introducedInVersion.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "Managed database role introduction version must be canonical");
        }
        allowedOwnershipSchemas = List.copyOf(allowedOwnershipSchemas);
        if (allowedOwnershipSchemas.stream().anyMatch(
                        schema -> schema == null
                                || !schema.matches("[a-z_][a-z0-9_]{0,62}"))
                || new LinkedHashSet<>(allowedOwnershipSchemas).size()
                        != allowedOwnershipSchemas.size()) {
            throw new IllegalArgumentException(
                    "Managed database role ownership schemas must be canonical and unique");
        }
        if (migrationAuthority && allowedOwnershipSchemas.isEmpty()) {
            throw new IllegalArgumentException(
                    "Temporary migration authority requires an owned schema surface");
        }
    }
}
