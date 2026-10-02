package com.dwp.migration.control;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationAdoptionGuard.HistoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.InventoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.Receipt;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;

public final class MigrationControlMain {
    private static final String RECEIPT_PREFIX = "DWP_MIGRATION_CONTROL_RECEIPT=";

    private MigrationControlMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 0) {
            throw new IllegalStateException(
                    "Migration Control accepts no command arguments or credentials");
        }
        FlywayControl.requireVersion();
        ControlEnvironment configuredEnvironment = ControlEnvironment.load();
        if (configuredEnvironment.bootstrapPrincipal().equals(
                        configuredEnvironment.migrationPrincipal())
                || configuredEnvironment.bootstrapPrincipal().equals(
                        configuredEnvironment.runtimePrincipal())
                || configuredEnvironment.migrationPrincipal().equals(
                        configuredEnvironment.runtimePrincipal())
                || (configuredEnvironment.hasProjectionPublisher()
                        && (configuredEnvironment.bootstrapPrincipal().equals(
                                        configuredEnvironment.projectionPublisherPrincipal())
                                || configuredEnvironment.migrationPrincipal().equals(
                                        configuredEnvironment.projectionPublisherPrincipal())
                                || configuredEnvironment.runtimePrincipal().equals(
                                        configuredEnvironment.projectionPublisherPrincipal())))) {
            throw new IllegalStateException(
                    "Migration Control principals must be pairwise distinct");
        }
        try (Connection fence = bootstrapConnection(configuredEnvironment)) {
            DatabaseControl.acquireExclusiveControlLock(
                    fence, configuredEnvironment);
            // Keep the service-wide preflight as the qualification pass. The
            // immediately following adoption pass captures the immutable
            // predecessor proof under the same lock and bootstrap session.
            ControlPreflight.verify(fence, configuredEnvironment);
            AdoptionSealer.PreparedLegacyBoundaries preparedAdoption =
                    configuredEnvironment.mode() == ControlEnvironment.Mode.ADOPT_OR_UPGRADE
                            ? AdoptionSealer.prepareLegacyBoundaries(
                                    fence, configuredEnvironment)
                            : null;
            ControlCredentials controlCredentials =
                    ServiceConnectionControl.activateExclusiveControlFence(
                            fence, configuredEnvironment);
            ControlEnvironment environment = configuredEnvironment.withControlCredentials(
                    controlCredentials);
            boolean restored = false;
            DatabaseControl.revokeTemporary(fence, environment);
            DatabaseControl.requireTemporaryDenied(fence, environment);
            try {
                List<Integer> adoptionLegacyBoundaries = preparedAdoption == null
                        ? List.of()
                        : consumeAndTransferAdoption(
                                fence, environment, preparedAdoption);
                ManagedDatabaseRoleControl.provision(fence, environment);
                ControlRunReceipt receipt = switch (environment.mode()) {
                    case STRICT_FRESH -> runStrictFresh(environment);
                    case NOTIFICATION_FRESH -> runNotificationFresh(environment);
                    case PEOPLE_FRESH -> runPeopleFresh(environment);
                    case ADOPT_OR_UPGRADE -> runAdoption(
                            environment, adoptionLegacyBoundaries);
                };
                ManagedDatabaseRoleControl.requireSteadyPostMigration(
                        fence, environment);
                DatabaseControl.revokeTemporary(fence, environment);
                DatabaseControl.requireTemporaryDenied(fence, environment);
                ServiceConnectionControl.restoreServiceConnections(
                        fence, configuredEnvironment, controlCredentials);
                // Do not mark the fence restored until the steady-state TEMP
                // invariant has also been rechecked after credential restore.
                // Any failure in this final check must rotate both service
                // credentials and leave CONNECT denied.
                DatabaseControl.revokeTemporary(fence, environment);
                DatabaseControl.requireTemporaryDenied(fence, environment);
                System.out.println(RECEIPT_PREFIX + receipt.toJson());
                restored = true;
            } finally {
                if (!restored) {
                    ServiceConnectionControl.failClosedServiceConnections(
                            fence, configuredEnvironment);
                }
            }
        }
    }

    static ControlRunReceipt runStrictFresh(ControlEnvironment environment)
            throws Exception {
        if ("people".equals(environment.plan().service())
                || "notification".equals(environment.plan().service())) {
            throw new IllegalStateException(
                    "STRICT_FRESH is not valid for the service-specific Control flow");
        }
        try (Connection bootstrap = bootstrapConnection(environment)) {
            DatabaseControl.verifyIdentity(bootstrap, environment);
            DatabaseControl.revokeTemporary(bootstrap, environment);
            DatabaseControl.requireTemporaryDenied(bootstrap, environment);
            for (StreamPlan stream : environment.plan().streams()) {
                ControlPreflight.verifyNativeStream(bootstrap, stream, environment);
            }
        }
        StreamPlan primary = environment.plan().streams().getFirst();
        preparePrimaryMigrations(environment, primary);
        for (StreamPlan stream : environment.plan().streams()) {
            FlywayControl.migrateAndValidate(FlywayControl.load(
                    environment,
                    stream,
                    null));
        }
        try (Connection bootstrap = bootstrapConnection(environment)) {
            transactional(bootstrap, () -> {
                DatabaseControl.revokeTemporary(bootstrap, environment);
                DomainPrivilegeControl.normalize(bootstrap, environment);
            });
            DatabaseControl.requireTemporaryDenied(bootstrap, environment);
        }
        return ControlRunReceipt.create(
                "NATIVE_FRESH",
                environment,
                postgresVersion(environment),
                true,
                nativeStreamSeals(environment));
    }

    private static ControlRunReceipt runNotificationFresh(ControlEnvironment environment)
            throws Exception {
        requireService(environment, "notification");
        StreamPlan stream = environment.plan().streams().getFirst();
        boolean fresh;
        try (Connection bootstrap = bootstrapConnection(environment)) {
            DatabaseControl.verifyIdentity(bootstrap, environment);
            DatabaseControl.revokeTemporary(bootstrap, environment);
            DatabaseControl.requireTemporaryDenied(bootstrap, environment);
            ControlPreflight.verifyNativeStream(bootstrap, stream, environment);
            fresh = !DatabaseControl.historyExists(bootstrap, stream);
            if (fresh) {
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

        if (fresh) {
            FlywayControl.migrateAndValidate(
                    FlywayControl.load(
                            environment,
                            stream,
                            FlywayControl.predecessorVersion(
                                    environment, stream,
                                    NotificationPrivilegedMigration.ROLE_FOUNDATION
                                            .version())),
                    false);
            try (Connection bootstrap = bootstrapConnection(environment)) {
                NotificationAuthorityControl.requireFreshRoleState(
                        bootstrap, NotificationPrivilegedMigration.ROLE_FOUNDATION);
                NotificationAuthorityControl.runExactPrivilegedMigration(
                        bootstrap,
                        environment,
                        stream,
                        NotificationPrivilegedMigration.ROLE_FOUNDATION);
                NotificationAuthorityControl.prepareFoundationRuntimeGrant(
                        bootstrap, environment);
            }
            FlywayControl.migrateAndValidate(
                    FlywayControl.load(
                            environment,
                            stream,
                            MigrationVersion.fromVersion("5")),
                    false);
            try (Connection bootstrap = bootstrapConnection(environment)) {
                NotificationAuthorityControl.anchorFoundationRuntimeGrant(
                        bootstrap, environment);
            }
            FlywayControl.migrateAndValidate(
                    FlywayControl.load(
                            environment,
                            stream,
                            FlywayControl.predecessorVersion(
                                    environment, stream,
                                    NotificationPrivilegedMigration.AUDIT_RELAY.version())),
                    false);
            try (Connection bootstrap = bootstrapConnection(environment)) {
                NotificationAuthorityControl.requireFreshRoleState(
                        bootstrap, NotificationPrivilegedMigration.AUDIT_RELAY);
                NotificationAuthorityControl.runExactPrivilegedMigration(
                        bootstrap,
                        environment,
                        stream,
                        NotificationPrivilegedMigration.AUDIT_RELAY);
                NotificationAuthorityControl.anchorAuditRelayRuntimeGrant(
                        bootstrap, environment);
            }
        }
        FlywayControl.migrateAndValidate(FlywayControl.load(environment, stream, null));
        try (Connection bootstrap = bootstrapConnection(environment)) {
            ControlPreflight.requireNativeHistory(
                    bootstrap, stream, environment.migrationPrincipal());
            NotificationAuthorityControl.normalizeFinalRuntimeRoleState(
                    bootstrap, environment);
            transactional(bootstrap, () -> {
                ProtectedObjectOwnershipControl.transfer(bootstrap, environment);
                DomainPrivilegeControl.normalize(bootstrap, environment);
            });
            NotificationAuthorityControl.requireFinalRuntimeRoleState(
                    bootstrap, environment);
            DatabaseControl.revokeTemporary(bootstrap, environment);
            DatabaseControl.requireTemporaryDenied(bootstrap, environment);
        }
        return ControlRunReceipt.create(
                "NATIVE_FRESH",
                environment,
                postgresVersion(environment),
                true,
                nativeStreamSeals(environment));
    }

    static ControlRunReceipt runPeopleFresh(ControlEnvironment environment)
            throws Exception {
        requireService(environment, "people");
        StreamPlan primary = environment.plan().streams().getFirst();
        try (Connection bootstrap = bootstrapConnection(environment)) {
            DatabaseControl.verifyIdentity(bootstrap, environment);
            DatabaseControl.revokeTemporary(bootstrap, environment);
            DatabaseControl.requireTemporaryDenied(bootstrap, environment);
            for (StreamPlan stream : environment.plan().streams()) {
                ControlPreflight.verifyNativeStream(bootstrap, stream, environment);
            }
        }

        DatabaseCreateMigrationControl.applyPending(environment, primary);
        applyTemporaryMigrations(environment, primary);

        for (StreamPlan stream : environment.plan().streams()) {
            Flyway flyway = FlywayControl.load(
                    environment,
                    stream,
                    null);
            FlywayControl.migrateAndValidate(flyway);
        }
        try (Connection bootstrap = bootstrapConnection(environment)) {
            transactional(bootstrap, () -> {
                DatabaseControl.revokeTemporary(bootstrap, environment);
                DomainPrivilegeControl.normalize(bootstrap, environment);
            });
            DatabaseControl.requireTemporaryDenied(bootstrap, environment);
        }
        List<StreamSeal> seals = nativeStreamSeals(environment);
        return ControlRunReceipt.create(
                "NATIVE_FRESH",
                environment,
                postgresVersion(environment),
                true,
                seals);
    }

    static void preparePrimaryMigrations(
            ControlEnvironment environment, StreamPlan primary) throws Exception {
        DatabaseCreateMigrationControl.applyPending(environment, primary);
        // Platform V254.1 needs two extensions and an exact empty-database
        // precondition before any sequential migration is allowed to mutate.
        PlatformBridgeSchemaControl.prepare(environment, primary);
        applyTemporaryMigrations(environment, primary);
        PlatformBridgeSchemaControl.applyPending(environment, primary);
    }

    static void applyTemporaryMigrations(
            ControlEnvironment environment, StreamPlan primary) throws Exception {
        try (Connection bootstrap = bootstrapConnection(environment)) {
            for (String version : environment.plan().temporaryMigrationVersions()) {
                if (DatabaseControl.versionApplied(bootstrap, primary, version)) {
                    continue;
                }
                MigrationVersion predecessor = FlywayControl.predecessorVersion(
                        environment, primary, version);
                Flyway beforeTemporaryMigration = FlywayControl.load(
                        environment,
                        primary,
                        predecessor);
                FlywayControl.migrateAndValidate(beforeTemporaryMigration, false);
                DatabaseControl.grantTemporary(bootstrap, environment);
                try {
                    Flyway exactTemporaryMigration = FlywayControl.load(
                            environment,
                            primary,
                            MigrationVersion.fromVersion(version));
                    FlywayControl.migrateAndValidate(exactTemporaryMigration, false);
                } finally {
                    DatabaseControl.revokeTemporary(bootstrap, environment);
                }
                DatabaseControl.requireTemporaryDenied(bootstrap, environment);
            }
            DatabaseControl.revokeTemporary(bootstrap, environment);
            DatabaseControl.requireTemporaryDenied(bootstrap, environment);
        }
    }

    private static ControlRunReceipt runAdoption(
            ControlEnvironment environment,
            List<Integer> legacyBoundaries)
            throws Exception {
        if (legacyBoundaries.size() != environment.plan().streams().size()) {
            throw new IllegalStateException(
                    "Prepared legacy predecessor boundaries do not cover the exact plan");
        }
        preparePrimaryMigrations(
                environment, environment.plan().streams().getFirst());
        for (StreamPlan stream : environment.plan().streams()) {
            Flyway flyway = FlywayControl.load(
                    environment,
                    stream,
                    null);
            FlywayControl.migrateAndValidate(flyway);
        }
        List<StreamSeal> seals = new ArrayList<>();
        try (Connection bootstrap = bootstrapConnection(environment);
                Connection migration = migrationConnection(environment)) {
            verifyMigrationIdentity(migration, environment);
            bootstrap.setAutoCommit(false);
            try {
                if ("notification".equals(environment.plan().service())) {
                    NotificationAuthorityControl.normalizeFinalRuntimeRoleState(
                            bootstrap, environment);
                }
                DomainPrivilegeControl.normalize(bootstrap, environment);
                if ("notification".equals(environment.plan().service())) {
                    NotificationAuthorityControl.requireFinalRuntimeRoleState(
                            bootstrap, environment);
                }
                RoleSearchPathFence.requireExact(bootstrap, environment);
                ProtectedSchemaAclControl.verifyFinal(bootstrap, environment);
                for (int index = 0; index < environment.plan().streams().size(); index++) {
                    Receipt receipt = AdoptionSealer.seal(
                            bootstrap,
                            migration,
                            environment.plan().streams().get(index),
                            environment,
                            legacyBoundaries.get(index));
                    seals.add(streamSeal(receipt));
                }
                DatabaseControl.revokeTemporary(bootstrap, environment);
                DatabaseControl.requireTemporaryDenied(bootstrap, environment);
                bootstrap.commit();
            } catch (Exception exception) {
                bootstrap.rollback();
                throw exception;
            }
        }
        return ControlRunReceipt.create(
                "ADOPTED",
                environment,
                postgresVersion(environment),
                true,
                seals);
    }

    private static List<Integer> consumeAndTransferAdoption(
            Connection fence,
            ControlEnvironment environment,
            AdoptionSealer.PreparedLegacyBoundaries prepared) throws Exception {
        if (!fence.getAutoCommit()) {
            throw new IllegalStateException(
                    "Adoption predecessor transfer requires auto-commit entry state");
        }
        fence.setAutoCommit(false);
        try {
            DatabaseControl.verifyIdentity(fence, environment);
            List<Integer> boundaries = prepared.consume(fence, environment);
            if ("notification".equals(environment.plan().service())) {
                NotificationAuthorityControl.requireAdoptionAuthorityFloor(
                        fence, environment.plan().streams().getFirst());
            }
            ProtectedObjectOwnershipControl.transfer(fence, environment);
            fence.commit();
            return boundaries;
        } catch (Exception exception) {
            fence.rollback();
            throw exception;
        } finally {
            fence.setAutoCommit(true);
        }
    }

    private static List<StreamSeal> nativeStreamSeals(ControlEnvironment environment)
            throws Exception {
        ProtectedSchemaAclControl.verifyFinal(environment);
        List<StreamSeal> seals = new ArrayList<>();
        try (Connection bootstrap = bootstrapConnection(environment);
                Connection migration = migrationConnection(environment)) {
            RoleSearchPathFence.requireExact(bootstrap, environment);
            ServiceRoleDdlBoundaryControl.requireExact(bootstrap, environment);
            verifyMigrationIdentity(migration, environment);
            for (StreamPlan stream : environment.plan().streams()) {
                int maximum = DatabaseControl.historyMax(migration, stream);
                HistoryDigest history = MigrationAdoptionGuard.digestHistory(
                        migration,
                        ControlContracts.adoption(stream, environment.plan()),
                        maximum,
                        0,
                        environment.migrationPrincipal());
                if (!history.allSuccessful()
                        || history.hasLegacyPrincipal()
                        || !history.postLegacyOwnedByMigration()) {
                    throw new IllegalStateException(
                            "Fresh history is not native to the migration principal");
                }
                var inventory = MigrationAdoptionGuard.liveInventory(
                        migration,
                        ControlContracts.adoption(stream, environment.plan()),
                        environment.migrationPrincipal(),
                        environment.plan().managedObjectOwnerNames());
                InventoryDigest inventoryDigest =
                        MigrationAdoptionGuard.digestInventory(inventory);
                seals.add(new StreamSeal(
                        stream.streamKey(),
                        history.maxInstalledRank(),
                        history.rowCount(),
                        history.sha256(),
                        inventoryDigest.objectCount(),
                        inventoryDigest.sha256(),
                        ""));
            }
        }
        return List.copyOf(seals);
    }

    private static StreamSeal streamSeal(Receipt receipt) {
        return new StreamSeal(
                receipt.streamKey(),
                receipt.sealedHistoryMaxInstalledRank(),
                receipt.sealedHistoryRowCount(),
                receipt.sealedHistorySha256(),
                receipt.inventoryObjectCount(),
                receipt.inventorySha256(),
                receipt.receiptSha256());
    }

    private static void verifyMigrationIdentity(
            Connection connection, ControlEnvironment environment) throws SQLException {
        if (!environment.database().equals(
                DatabaseControl.scalar(connection, "SELECT current_database()"))
                || !environment.migrationPrincipal().equals(
                        DatabaseControl.scalar(connection, "SELECT current_user"))
                || !environment.migrationPrincipal().equals(
                        DatabaseControl.scalar(connection, "SELECT session_user"))) {
            throw new IllegalStateException("Migration Control connection identity mismatch");
        }
    }

    private static void requireService(
            ControlEnvironment environment, String requiredService) {
        if (!requiredService.equals(environment.plan().service())) {
            throw new IllegalStateException(
                    environment.mode() + " is restricted to " + requiredService);
        }
    }

    private static Connection bootstrapConnection(ControlEnvironment environment)
            throws SQLException {
        return DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.bootstrapPrincipal(),
                environment.bootstrapPassword());
    }

    private static Connection migrationConnection(ControlEnvironment environment)
            throws SQLException {
        return DriverManager.getConnection(
                environment.jdbcUrl(),
                environment.migrationPrincipal(),
                environment.migrationPassword());
    }

    private static String postgresVersion(ControlEnvironment environment) throws SQLException {
        try (Connection connection = bootstrapConnection(environment)) {
            return DatabaseControl.scalar(
                    connection,
                    "SELECT split_part(current_setting('server_version'), ' ', 1)");
        }
    }

    private static void transactional(Connection connection, SqlAction action) throws Exception {
        connection.setAutoCommit(false);
        try {
            action.run();
            connection.commit();
        } catch (Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
