package com.dwp.migration.control;

import static com.dwp.migration.control.ControlValues.quoteIdentifier;
import static com.dwp.migration.control.ControlValues.quoteLiteral;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.dwp.core.database.MigrationAdoptionGuard;
import com.dwp.core.database.MigrationAdoptedPredecessorControllerVerifier;
import com.dwp.core.database.MigrationAdoptionGuard.AdoptionState;
import com.dwp.core.database.MigrationAdoptionGuard.Contract;
import com.dwp.core.database.MigrationAdoptionGuard.HistoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.InventoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.InventoryEntry;
import com.dwp.core.database.MigrationAdoptionGuard.Receipt;

final class AdoptionSealer {
    private static final String CONTROL_SCHEMA = MigrationAdoptionGuard.CONTROL_SCHEMA;
    private static final String RECEIPT_TABLE = MigrationAdoptionGuard.RECEIPT_TABLE;
    private static final String INVENTORY_TABLE = MigrationAdoptionGuard.INVENTORY_TABLE;

    private AdoptionSealer() {
    }

    static int legacyBoundary(
            Connection bootstrap,
            StreamPlan stream,
            ControlEnvironment environment) throws SQLException {
        return prepareLegacyBoundary(bootstrap, stream, environment).legacyMaxInstalledRank();
    }

    static PreparedLegacyBoundaries prepareLegacyBoundaries(
            Connection bootstrap,
            ControlEnvironment environment) throws SQLException {
        if (environment.mode() != ControlEnvironment.Mode.ADOPT_OR_UPGRADE) {
            throw new IllegalStateException(
                    "Legacy predecessor proof is valid only for adoption upgrades");
        }
        List<PreparedLegacyBoundary> proofs = new ArrayList<>();
        for (StreamPlan stream : environment.plan().streams()) {
            proofs.add(prepareLegacyBoundary(bootstrap, stream, environment));
        }
        return new PreparedLegacyBoundaries(proofs);
    }

    private static PreparedLegacyBoundary prepareLegacyBoundary(
            Connection bootstrap,
            StreamPlan stream,
            ControlEnvironment environment) throws SQLException {
        ExistingReceipt existing = existingReceipt(bootstrap, stream.streamKey());
        if (existing != null) {
            String expectedReceipt = environment.previousReceiptSha256ByStream()
                    .get(stream.streamKey());
            if (!existing.receiptSha256().equals(expectedReceipt)
                    || !existing.controlReference().equals(
                            environment.previousControlReference())) {
                throw new IllegalStateException(
                        "Existing adoption receipt requires exact previous deployment seal");
            }
            ControlDataSource migration = roleDataSource(
                    environment, environment.migrationPrincipal());
            ControlDataSource runtime = roleDataSource(
                    environment, environment.runtimePrincipal());
            AdoptionState state = MigrationAdoptionGuard.verifyBeforeMigration(
                    ControlContracts.adoption(stream, environment.plan()),
                    migration,
                    runtime,
                    environment.migrationPrincipal(),
                    expectedReceipt,
                    environment.previousControlReference(),
                    environment.plan().managedObjectOwnerNames());
            if (!state.adopted()
                    || state.receipt().legacyMaxInstalledRank()
                            != existing.legacyMaxInstalledRank()) {
                throw new IllegalStateException(
                        "Existing adoption receipt pre-state is inconsistent");
            }
            return new PreparedLegacyBoundary(
                    stream.streamKey(), PredecessorMode.EXISTING,
                    state.receipt().legacyMaxInstalledRank(), state.receipt(), null);
        }
        if (environment.previousReceiptSha256ByStream().containsKey(stream.streamKey())) {
            throw new IllegalStateException(
                    "Previous adoption settings were supplied without an existing receipt");
        }
        int legacyMax = DatabaseControl.historyMax(bootstrap, stream);
        if (legacyMax < 1) {
            throw new IllegalStateException(
                    "Initial adoption requires existing successful Flyway history");
        }
        HistoryDigest history = MigrationAdoptionGuard.digestHistory(
                bootstrap,
                ControlContracts.adoption(stream, environment.plan()),
                legacyMax,
                legacyMax,
                environment.migrationPrincipal());
        return new PreparedLegacyBoundary(
                stream.streamKey(), PredecessorMode.INITIAL,
                legacyMax, null, history);
    }

    static final class PreparedLegacyBoundaries {
        private final List<PreparedLegacyBoundary> proofs;
        private boolean consumed;

        PreparedLegacyBoundaries(List<PreparedLegacyBoundary> proofs) {
            this.proofs = List.copyOf(Objects.requireNonNull(
                    proofs, "proofs must not be null"));
        }

        synchronized List<Integer> consume(
                Connection bootstrap,
                ControlEnvironment environment) throws SQLException {
            if (consumed) {
                throw new IllegalStateException(
                        "Prepared legacy predecessor proof was already consumed");
            }
            consumed = true;
            if (environment.mode() != ControlEnvironment.Mode.ADOPT_OR_UPGRADE) {
                throw new IllegalStateException(
                        "Prepared legacy predecessor proof mode is inconsistent");
            }
            List<StreamPlan> streams = environment.plan().streams();
            if (proofs.size() != streams.size()) {
                throw new IllegalStateException(
                        "Prepared legacy predecessor proof count is inconsistent");
            }
            boolean existingExpected = !environment.previousReceiptSha256ByStream().isEmpty();
            for (int index = 0; index < streams.size(); index++) {
                PreparedLegacyBoundary proof = proofs.get(index);
                StreamPlan stream = streams.get(index);
                if (!proof.streamKey().equals(stream.streamKey())
                        || (proof.mode() == PredecessorMode.EXISTING) != existingExpected) {
                    throw new IllegalStateException(
                            "Prepared legacy predecessor proof order or mode is inconsistent");
                }
                if (proof.mode() == PredecessorMode.EXISTING
                        && (!proof.receipt().receiptSha256().equals(
                                environment.previousReceiptSha256ByStream().get(stream.streamKey()))
                                || !proof.receipt().controlReference().equals(
                                        environment.previousControlReference()))) {
                    throw new IllegalStateException(
                            "Prepared legacy predecessor proof deployment binding is inconsistent");
                }
            }
            if (bootstrap == null || bootstrap.getAutoCommit()) {
                throw new IllegalStateException(
                        "Prepared legacy predecessor proof requires the atomic transfer transaction");
            }
            DatabaseConnectionFence.requireDatabaseAcl(
                    bootstrap, environment, DatabaseConnectionFence.State.ACTIVE);
            DatabaseConnectionFence.requireNoForeignSessions(bootstrap, environment);
            lockPredecessorEvidence(bootstrap, streams, existingExpected);
            DatabaseConnectionFence.requireNoForeignSessions(bootstrap, environment);
            List<Integer> boundaries = new ArrayList<>();
            for (int index = 0; index < streams.size(); index++) {
                PreparedLegacyBoundary proof = proofs.get(index);
                StreamPlan stream = streams.get(index);
                if (proof.mode() == PredecessorMode.EXISTING) {
                    Receipt current = MigrationAdoptedPredecessorControllerVerifier.verify(
                            ControlContracts.adoption(stream, environment.plan()),
                            bootstrap,
                            environment.bootstrapPrincipal(),
                            environment.migrationPrincipal(),
                            environment.runtimePrincipal(),
                            proof.receipt(),
                            environment.plan().managedObjectOwnerNames());
                    if (current.legacyMaxInstalledRank()
                            != proof.legacyMaxInstalledRank()) {
                        throw new IllegalStateException(
                                "Prepared existing predecessor boundary changed");
                    }
                } else {
                    if (existingReceipt(bootstrap, stream.streamKey()) != null
                            || environment.previousReceiptSha256ByStream()
                                    .containsKey(stream.streamKey())) {
                        throw new IllegalStateException(
                                "Initial adoption receipt appeared after BASELINE verification");
                    }
                    int currentMax = DatabaseControl.historyMax(bootstrap, stream);
                    HistoryDigest currentHistory = MigrationAdoptionGuard.digestHistory(
                            bootstrap,
                            ControlContracts.adoption(stream, environment.plan()),
                            currentMax,
                            currentMax,
                            environment.migrationPrincipal());
                    if (currentMax != proof.legacyMaxInstalledRank()
                            || !currentHistory.equals(proof.initialHistory())) {
                        throw new IllegalStateException(
                                "Initial adoption history changed after BASELINE verification");
                    }
                }
                boundaries.add(proof.legacyMaxInstalledRank());
            }
            return List.copyOf(boundaries);
        }

        private static void lockPredecessorEvidence(
                Connection bootstrap,
                List<StreamPlan> streams,
                boolean existing) throws SQLException {
            DatabaseControl.execute(bootstrap, "SET LOCAL lock_timeout='5s'");
            if (existing) {
                DatabaseControl.execute(
                        bootstrap,
                        "LOCK TABLE " + quoteIdentifier(CONTROL_SCHEMA) + "."
                                + quoteIdentifier(RECEIPT_TABLE) + ", "
                                + quoteIdentifier(CONTROL_SCHEMA) + "."
                                + quoteIdentifier(INVENTORY_TABLE)
                                + " IN ACCESS EXCLUSIVE MODE");
            }
            for (StreamPlan stream : streams) {
                DatabaseControl.execute(
                        bootstrap,
                        "LOCK TABLE " + quoteIdentifier(stream.schema()) + "."
                                + quoteIdentifier(stream.historyTable())
                                + " IN ACCESS EXCLUSIVE MODE");
            }
        }
    }

    enum PredecessorMode {
        INITIAL,
        EXISTING
    }

    record PreparedLegacyBoundary(
            String streamKey,
            PredecessorMode mode,
            int legacyMaxInstalledRank,
            Receipt receipt,
            HistoryDigest initialHistory) {
        PreparedLegacyBoundary {
            if (streamKey == null || !streamKey.matches("[a-z][a-z0-9-]{0,62}")
                    || mode == null
                    || legacyMaxInstalledRank < 1
                    || (mode == PredecessorMode.EXISTING)
                            != (receipt != null && initialHistory == null)
                    || (mode == PredecessorMode.INITIAL)
                            != (receipt == null && initialHistory != null)
                    || (initialHistory != null
                            && (initialHistory.rowCount() < 1
                                    || initialHistory.maxInstalledRank()
                                            != legacyMaxInstalledRank
                                    || initialHistory.sha256() == null
                                    || !initialHistory.sha256().matches("[0-9a-f]{64}")
                                    || !initialHistory.allSuccessful()
                                    || !initialHistory.hasLegacyPrincipal()
                                    || !initialHistory.postLegacyOwnedByMigration()))
                    || (receipt != null
                            && (!streamKey.equals(receipt.streamKey())
                                    || legacyMaxInstalledRank
                                            != receipt.legacyMaxInstalledRank()))) {
                throw new IllegalArgumentException(
                        "Prepared legacy predecessor proof is not canonical");
            }
        }
    }

    private static ControlDataSource roleDataSource(
            ControlEnvironment environment, String effectiveRole) {
        if (effectiveRole.equals(environment.migrationPrincipal())) {
            return new ControlDataSource(
                    environment.jdbcUrl(),
                    environment.migrationPrincipal(),
                    environment.migrationPassword());
        }
        if (!effectiveRole.equals(environment.runtimePrincipal())) {
            throw new IllegalStateException(
                    "Migration Control cannot open an unrecognized direct principal");
        }
        return new ControlDataSource(
                environment.jdbcUrl(),
                environment.runtimePrincipal(),
                environment.runtimePassword());
    }

    static Receipt seal(
            Connection bootstrap,
            Connection migration,
            StreamPlan stream,
            ControlEnvironment environment,
            int legacyMax) throws SQLException {
        ensureMetadata(bootstrap, environment);
        Contract contract = ControlContracts.adoption(stream, environment.plan());
        HistoryDigest history = MigrationAdoptionGuard.digestHistory(
                migration,
                contract,
                DatabaseControl.historyMax(migration, stream),
                legacyMax,
                environment.migrationPrincipal());
        if (!history.allSuccessful()
                || !history.hasLegacyPrincipal()
                || !history.postLegacyOwnedByMigration()
                || history.rowCount() < 1
                || history.maxInstalledRank() < legacyMax) {
            throw new IllegalStateException(
                    "Migration Control cannot seal invalid Flyway provenance");
        }
        List<InventoryEntry> inventory = MigrationAdoptionGuard.liveInventory(
                migration,
                contract,
                environment.migrationPrincipal(),
                environment.plan().managedObjectOwnerNames());
        if (inventory.isEmpty()) {
            throw new IllegalStateException(
                    "Migration Control cannot seal an empty protected-object inventory");
        }
        InventoryDigest inventoryDigest = MigrationAdoptionGuard.digestInventory(inventory);
        String sealedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).toString();
        Receipt unsigned = new Receipt(
                stream.streamKey(),
                MigrationAdoptionGuard.CONTRACT_VERSION,
                environment.database(),
                stream.schema(),
                stream.historyTable(),
                legacyMax,
                history.maxInstalledRank(),
                history.rowCount(),
                history.sha256(),
                inventoryDigest.objectCount(),
                inventoryDigest.sha256(),
                environment.migrationPrincipal(),
                environment.controlReference(),
                sealedAt,
                "");
        Receipt receipt = new Receipt(
                unsigned.streamKey(),
                unsigned.contractVersion(),
                unsigned.databaseName(),
                unsigned.historySchema(),
                unsigned.historyTable(),
                unsigned.legacyMaxInstalledRank(),
                unsigned.sealedHistoryMaxInstalledRank(),
                unsigned.sealedHistoryRowCount(),
                unsigned.sealedHistorySha256(),
                unsigned.inventoryObjectCount(),
                unsigned.inventorySha256(),
                unsigned.migrationPrincipal(),
                unsigned.controlReference(),
                unsigned.sealedAtUtc(),
                MigrationAdoptionGuard.digestReceipt(unsigned));
        replaceRows(bootstrap, inventory, receipt);
        closeMetadataPrivileges(bootstrap, environment);
        return receipt;
    }

    private static void ensureMetadata(
            Connection connection, ControlEnvironment environment) throws SQLException {
        ControlMetadata.ensure(connection, environment.bootstrapPrincipal());
    }

    private static void replaceRows(
            Connection connection, List<InventoryEntry> inventory, Receipt receipt)
            throws SQLException {
        String inventoryName = quoteIdentifier(CONTROL_SCHEMA) + "."
                + quoteIdentifier(INVENTORY_TABLE);
        String receiptName = quoteIdentifier(CONTROL_SCHEMA) + "."
                + quoteIdentifier(RECEIPT_TABLE);
        try (PreparedStatement deleteInventory = connection.prepareStatement(
                "DELETE FROM " + inventoryName + " WHERE stream_key=?");
                PreparedStatement deleteReceipt = connection.prepareStatement(
                        "DELETE FROM " + receiptName + " WHERE stream_key=?");
                PreparedStatement insertInventory = connection.prepareStatement(
                        "INSERT INTO " + inventoryName
                                + " (stream_key,object_class,object_oid,object_schema,"
                                + "object_identity,adopted_owner) VALUES (?,?,?::oid,?,?,?)");
                PreparedStatement insertReceipt = connection.prepareStatement(
                        "INSERT INTO " + receiptName
                                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            deleteInventory.setString(1, receipt.streamKey());
            deleteInventory.executeUpdate();
            deleteReceipt.setString(1, receipt.streamKey());
            deleteReceipt.executeUpdate();
            for (InventoryEntry object : inventory) {
                insertInventory.setString(1, receipt.streamKey());
                insertInventory.setString(2, object.objectClass());
                insertInventory.setLong(3, object.oid());
                insertInventory.setString(4, object.schema());
                insertInventory.setString(5, object.identity());
                insertInventory.setString(6, object.owner());
                insertInventory.addBatch();
            }
            insertInventory.executeBatch();
            List<Object> values = new ArrayList<>(List.of(
                    receipt.streamKey(), receipt.contractVersion(), receipt.databaseName(),
                    receipt.historySchema(), receipt.historyTable(),
                    receipt.legacyMaxInstalledRank(), receipt.sealedHistoryMaxInstalledRank(),
                    receipt.sealedHistoryRowCount(), receipt.sealedHistorySha256(),
                    receipt.inventoryObjectCount(), receipt.inventorySha256(),
                    receipt.migrationPrincipal(), receipt.controlReference(),
                    receipt.sealedAtUtc(), receipt.receiptSha256()));
            for (int index = 0; index < values.size(); index++) {
                insertReceipt.setObject(index + 1, values.get(index));
            }
            insertReceipt.executeUpdate();
        }
    }

    private static void closeMetadataPrivileges(
            Connection connection, ControlEnvironment environment) throws SQLException {
        String schema = quoteIdentifier(CONTROL_SCHEMA);
        String migration = quoteIdentifier(environment.migrationPrincipal());
        String runtime = quoteIdentifier(environment.runtimePrincipal());
        DatabaseControl.execute(connection, "REVOKE ALL ON SCHEMA " + schema
                + " FROM PUBLIC, " + migration + ", " + runtime);
        DatabaseControl.execute(connection, "GRANT USAGE ON SCHEMA " + schema
                + " TO " + migration);
        DatabaseControl.execute(connection, "REVOKE ALL ON ALL TABLES IN SCHEMA " + schema
                + " FROM PUBLIC, " + migration + ", " + runtime);
        DatabaseControl.execute(connection, "GRANT SELECT ON TABLE " + schema + "."
                + quoteIdentifier(RECEIPT_TABLE) + ", " + schema + "."
                + quoteIdentifier(INVENTORY_TABLE) + " TO " + migration);
    }

    private static ExistingReceipt existingReceipt(Connection connection, String streamKey)
            throws SQLException {
        if (DatabaseControl.scalarLong(connection,
                "SELECT CASE WHEN to_regclass('dwp_migration_control.adoption_receipt') "
                        + "IS NULL THEN 0 ELSE 1 END") == 0L) {
            return null;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT legacy_max_installed_rank,receipt_sha256,control_reference FROM "
                        + quoteIdentifier(CONTROL_SCHEMA) + "." + quoteIdentifier(RECEIPT_TABLE)
                        + " WHERE stream_key=?")) {
            statement.setString(1, streamKey);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                ExistingReceipt receipt = new ExistingReceipt(
                        result.getInt(1), result.getString(2), result.getString(3));
                if (result.next()) {
                    throw new IllegalStateException("Duplicate adoption receipt");
                }
                return receipt;
            }
        }
    }

    private record ExistingReceipt(
            int legacyMaxInstalledRank,
            String receiptSha256,
            String controlReference) {
    }
}
