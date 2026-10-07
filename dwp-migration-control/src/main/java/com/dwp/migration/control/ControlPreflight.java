package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.SQLException;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationAdoptionGuard.HistoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.InventoryDigest;

/** Read-only predecessor verification performed before any Control mutation. */
final class ControlPreflight {
    private ControlPreflight() {
    }

    static void verify(Connection bootstrap, ControlEnvironment environment)
            throws SQLException {
        DatabaseControl.verifyIdentity(bootstrap, environment);
        DatabaseControl.requireTemporaryDenied(bootstrap, environment);
        DatabaseControl.requireDatabaseCreateDenied(bootstrap, environment);
        DatabaseControl.requireStrictServiceLoginRoles(bootstrap, environment);
        RequiredDatabaseExtensionControl.requireExact(bootstrap, environment);
        ManagedDatabaseRoleControl.requirePreflight(bootstrap, environment);
        ProtectedSchemaAclControl.verify(bootstrap, environment);
        SystemCatalogAuthorityControl.verifyExisting(bootstrap, environment);
        switch (environment.mode()) {
            case STRICT_FRESH, PEOPLE_FRESH -> verifyNativeStreams(
                    bootstrap, environment);
            case NOTIFICATION_FRESH -> verifyNotificationNative(
                    bootstrap, environment);
            case ADOPT_OR_UPGRADE -> verifyAdoption(bootstrap, environment);
        }
    }

    static void verifyNativeStream(
            Connection connection,
            StreamPlan stream,
            ControlEnvironment environment) throws SQLException {
        StreamSeal expected = environment.previousNativeSeals().get(stream.streamKey());
        if (!DatabaseControl.historyExists(connection, stream)) {
            if (expected != null) {
                throw new IllegalStateException(
                        "Previous native Control seal requires existing Flyway history");
            }
            DatabaseControl.requireFreshSchemaClean(
                    connection, stream, environment.migrationPrincipal());
            return;
        }
        if (expected == null) {
            throw new IllegalStateException(
                    "Existing native Flyway history requires an external Control seal");
        }
        requireNativeHistory(connection, stream, environment.migrationPrincipal());
        HistoryDigest history = MigrationAdoptionGuard.digestHistory(
                connection,
                ControlContracts.adoption(stream, environment.plan()),
                DatabaseControl.historyMax(connection, stream),
                0,
                environment.migrationPrincipal());
        InventoryDigest inventory = MigrationAdoptionGuard.digestLiveInventory(
                connection,
                ControlContracts.adoption(stream, environment.plan()),
                environment.migrationPrincipal(),
                environment.plan().managedObjectOwnerNames());
        StreamSeal actual = new StreamSeal(
                stream.streamKey(),
                history.maxInstalledRank(),
                history.rowCount(),
                history.sha256(),
                inventory.objectCount(),
                inventory.sha256(),
                "");
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Native database state differs from the external Control seal");
        }
    }

    static void requireNativeHistory(
            Connection connection, StreamPlan stream, String migration) throws SQLException {
        if (!DatabaseControl.historyExists(connection, stream)) {
            return;
        }
        long invalid = DatabaseControl.scalarLong(connection,
                "SELECT COUNT(*) FROM " + ControlValues.quoteIdentifier(stream.schema()) + "."
                        + ControlValues.quoteIdentifier(stream.historyTable())
                        + " WHERE NOT success OR installed_by<>"
                        + ControlValues.quoteLiteral(migration));
        if (invalid != 0L) {
            throw new IllegalStateException(
                    "Fresh Control refuses legacy Flyway history");
        }
    }

    private static void verifyNativeStreams(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        for (StreamPlan stream : environment.plan().streams()) {
            verifyNativeStream(bootstrap, stream, environment);
        }
    }

    private static void verifyNotificationNative(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        StreamPlan stream = environment.plan().streams().getFirst();
        verifyNativeStream(bootstrap, stream, environment);
        if (!DatabaseControl.historyExists(bootstrap, stream)) {
            for (NotificationPrivilegedMigration capability
                    : NotificationPrivilegedMigration.values()) {
                capability.requireAttestedSource(environment);
                NotificationAuthorityControl.requireFreshRoleState(
                        bootstrap, capability);
            }
        } else {
            NotificationAuthorityControl.requirePreUpgradeRuntimeRoleState(
                    bootstrap, environment);
        }
    }

    private static void verifyAdoption(
            Connection bootstrap, ControlEnvironment environment) throws SQLException {
        if ("notification".equals(environment.plan().service())) {
            NotificationAuthorityControl.requireAdoptionAuthorityFloor(
                    bootstrap, environment.plan().streams().getFirst());
        }
        for (StreamPlan stream : environment.plan().streams()) {
            AdoptionSealer.legacyBoundary(bootstrap, stream, environment);
        }
    }
}
