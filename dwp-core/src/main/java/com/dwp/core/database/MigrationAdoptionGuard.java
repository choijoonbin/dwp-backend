package com.dwp.core.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

import com.dwp.core.database.MigrationAdoptionEvidenceTypes.HistoryDigestValue;
import com.dwp.core.database.MigrationAdoptionEvidenceTypes.InventoryDigestValue;

/**
 * Verifies a Control-owned adoption receipt without exposing bootstrap authority to an app.
 * Native strict databases have no adoption receipt; their mandatory external run receipt is
 * verified by {@link MigrationControlRunReceiptGuard}. Once legacy history is adopted, schema
 * upgrades are Control-run and re-sealed; application Flyway must have no pending migration.
 */
public final class MigrationAdoptionGuard {

    public static final int CONTRACT_VERSION = 2;
    public static final String CONTROL_SCHEMA = MigrationAdoptionEvidenceTypes.CONTROL_SCHEMA;
    public static final String RECEIPT_TABLE = MigrationAdoptionEvidenceTypes.RECEIPT_TABLE;
    public static final String INVENTORY_TABLE = MigrationAdoptionEvidenceTypes.INVENTORY_TABLE;

    private MigrationAdoptionGuard() {
    }

    public static AdoptionState verifyBeforeMigration(
            Contract contract,
            DataSource migrationDataSource,
            DataSource runtimeDataSource,
            String migrationPrincipal,
            String expectedReceiptSha256,
            String expectedControlReference) {
        return verifyBeforeMigration(
                contract,
                migrationDataSource,
                runtimeDataSource,
                migrationPrincipal,
                expectedReceiptSha256,
                expectedControlReference,
                Set.of());
    }

    /**
     * Verifies a stream whose Control plan deliberately assigns protected
     * objects to named, non-login auxiliary owners. The exact owner remains
     * part of the sealed inventory, so accepting a role does not accept an
     * ownership change after sealing.
     */
    public static AdoptionState verifyBeforeMigration(
            Contract contract,
            DataSource migrationDataSource,
            DataSource runtimeDataSource,
            String migrationPrincipal,
            String expectedReceiptSha256,
            String expectedControlReference,
            Set<String> additionalProtectedObjectOwners) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(migrationDataSource, "migrationDataSource must not be null");
        Objects.requireNonNull(runtimeDataSource, "runtimeDataSource must not be null");
        requireText("migrationPrincipal", migrationPrincipal);
        Set<String> approvedOwners = approvedProtectedObjectOwners(
                migrationPrincipal, additionalProtectedObjectOwners);
        String expectedDigest = nullToEmpty(expectedReceiptSha256);
        String expectedReference = nullToEmpty(expectedControlReference);
        try (Connection connection = migrationDataSource.getConnection()) {
            HistoryStatus status = historyStatus(connection, contract, migrationPrincipal);
            boolean receiptExists = MigrationAdoptionControlMetadataGuard.receiptExists(
                    connection, contract.streamKey());
            if (!status.exists() || status.rowCount() == 0) {
                requireNativeConfiguration(contract, expectedDigest, expectedReference, receiptExists);
                return AdoptionState.nativeHistory(status.maxInstalledRank(), status.rowCount());
            }
            if (status.invalidRows() == 0) {
                requireNativeConfiguration(contract, expectedDigest, expectedReference, receiptExists);
                return AdoptionState.nativeHistory(status.maxInstalledRank(), status.rowCount());
            }
            requireSha256(contract, expectedDigest);
            requireText(contract, "expectedControlReference", expectedReference);
            MigrationAdoptionControlMetadataGuard.verifyPrivileges(
                    contract, migrationDataSource, runtimeDataSource, migrationPrincipal);
            Receipt receipt = verifyReceipt(
                    connection,
                    contract,
                    migrationPrincipal,
                    expectedDigest,
                    expectedReference,
                    migrationPrincipal,
                    approvedOwners);
            return AdoptionState.adopted(receipt);
        } catch (SQLException exception) {
            throw failure(contract, "cannot verify migration adoption preflight", exception);
        }
    }

    public static void requireNoPendingBeforeMigration(
            Contract contract,
            Flyway flyway,
            AdoptionState state) {
        requireNoPendingBeforeMigration(contract, flyway, state, false);
    }

    /**
     * Requires a Control-preapplied native stream when {@code nativeMustBePreapplied} is true.
     * This is used by People because its immutable V7 requires temporary authority that the
     * steady-state application process is deliberately denied.
     */
    public static void requireNoPendingBeforeMigration(
            Contract contract,
            Flyway flyway,
            AdoptionState state,
            boolean nativeMustBePreapplied) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(flyway, "flyway must not be null");
        Objects.requireNonNull(state, "state must not be null");
        int pending = flyway.info().pending().length;
        if ((state.adopted() || nativeMustBePreapplied) && pending != 0) {
            throw failure(contract,
                    "before application Flyway must have no pending migrations, got " + pending);
        }
    }

    public static void requireNoPendingAfterMigration(
            Contract contract,
            Flyway flyway,
            AdoptionState state) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(flyway, "flyway must not be null");
        Objects.requireNonNull(state, "state must not be null");
        int pending = flyway.info().pending().length;
        if (pending != 0) {
            throw failure(contract,
                    "after application Flyway must have no pending migrations, got " + pending);
        }
    }

    public static void verifyAfterMigration(
            Contract contract,
            DataSource migrationDataSource,
            DataSource runtimeDataSource,
            String migrationPrincipal,
            String expectedReceiptSha256,
            String expectedControlReference,
            AdoptionState beforeMigration) {
        verifyAfterMigration(
                contract,
                migrationDataSource,
                runtimeDataSource,
                migrationPrincipal,
                expectedReceiptSha256,
                expectedControlReference,
                beforeMigration,
                Set.of());
    }

    public static void verifyAfterMigration(
            Contract contract,
            DataSource migrationDataSource,
            DataSource runtimeDataSource,
            String migrationPrincipal,
            String expectedReceiptSha256,
            String expectedControlReference,
            AdoptionState beforeMigration,
            Set<String> additionalProtectedObjectOwners) {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(beforeMigration, "beforeMigration must not be null");
        Set<String> approvedOwners = approvedProtectedObjectOwners(
                migrationPrincipal, additionalProtectedObjectOwners);
        try (Connection connection = migrationDataSource.getConnection()) {
            if (!beforeMigration.adopted()) {
                HistoryStatus current = historyStatus(connection, contract, migrationPrincipal);
                if (!current.exists()
                        || current.rowCount() < beforeMigration.rowCount()
                        || current.maxInstalledRank() < beforeMigration.maxInstalledRank()
                        || current.invalidRows() != 0) {
                    throw failure(contract,
                            "native Flyway history must be successful and attributed to the current migration principal");
                }
                requireNativeConfiguration(
                        contract,
                        nullToEmpty(expectedReceiptSha256),
                        nullToEmpty(expectedControlReference),
                        MigrationAdoptionControlMetadataGuard.receiptExists(
                                connection, contract.streamKey()));
                return;
            }
            MigrationAdoptionControlMetadataGuard.verifyPrivileges(
                    contract, migrationDataSource, runtimeDataSource, migrationPrincipal);
            Receipt current = verifyReceipt(
                    connection,
                    contract,
                    migrationPrincipal,
                    expectedReceiptSha256,
                    expectedControlReference,
                    migrationPrincipal,
                    approvedOwners);
            if (!current.equals(beforeMigration.receipt())) {
                throw failure(contract, "adoption receipt changed while application Flyway ran");
            }
        } catch (SQLException exception) {
            throw failure(contract, "cannot verify migration adoption postflight", exception);
        }
    }

    private static void requireNativeConfiguration(
            Contract contract,
            String expectedDigest,
            String expectedReference,
            boolean receiptExists) {
        if (!expectedDigest.isEmpty() || !expectedReference.isEmpty() || receiptExists) {
            throw failure(contract,
                    "native history must not have an adoption receipt or external receipt settings");
        }
    }

    static Receipt verifyReceipt(
            Connection connection,
            Contract contract,
            String migrationPrincipal,
            String expectedReceiptSha256,
            String expectedControlReference,
            String expectedConnectionPrincipal) throws SQLException {
        return verifyReceipt(
                connection,
                contract,
                migrationPrincipal,
                expectedReceiptSha256,
                expectedControlReference,
                expectedConnectionPrincipal,
                approvedProtectedObjectOwners(migrationPrincipal, Set.of()));
    }

    static Receipt verifyReceipt(
            Connection connection,
            Contract contract,
            String migrationPrincipal,
            String expectedReceiptSha256,
            String expectedControlReference,
            String expectedConnectionPrincipal,
            Set<String> approvedOwners) throws SQLException {
        approvedOwners = requireApprovedProtectedObjectOwners(
                migrationPrincipal, approvedOwners);
        MigrationAdoptionControlMetadataGuard.ReceiptRow row =
                MigrationAdoptionControlMetadataGuard.readReceipt(
                connection, contract);
        Receipt receipt = new Receipt(
                row.streamKey(), row.contractVersion(), row.databaseName(),
                row.historySchema(), row.historyTable(), row.legacyMaxInstalledRank(),
                row.sealedHistoryMaxInstalledRank(), row.sealedHistoryRowCount(),
                row.sealedHistorySha256(), row.inventoryObjectCount(),
                row.inventorySha256(), row.migrationPrincipal(), row.controlReference(),
                row.sealedAtUtc(), row.receiptSha256());
        if (receipt.contractVersion() != CONTRACT_VERSION
                || !contract.streamKey().equals(receipt.streamKey())
                || !contract.historySchema().equals(receipt.historySchema())
                || !contract.historyTable().equals(receipt.historyTable())
                || receipt.legacyMaxInstalledRank() < 1
                || receipt.sealedHistoryMaxInstalledRank() < receipt.legacyMaxInstalledRank()
                || receipt.sealedHistoryRowCount() < 1
                || receipt.inventoryObjectCount() < 1) {
            throw failure(contract, "adoption receipt metadata is not canonical v2");
        }
        String currentDatabase = scalar(connection, "SELECT current_database()");
        String currentPrincipal = scalar(connection, "SELECT current_user");
        if (!receipt.databaseName().equals(currentDatabase)
                || !receipt.migrationPrincipal().equals(migrationPrincipal)
                || !currentPrincipal.equals(expectedConnectionPrincipal)
                || !receipt.controlReference().equals(expectedControlReference)
                || !receipt.receiptSha256().equals(expectedReceiptSha256)) {
            throw failure(contract,
                    "adoption receipt identity does not match external deployment settings");
        }
        requireCanonicalInstant(contract, receipt.sealedAtUtc());
        requireSha256(contract, receipt.sealedHistorySha256());
        requireSha256(contract, receipt.inventorySha256());
        requireSha256(contract, receipt.receiptSha256());

        HistoryDigest history = digestHistory(
                connection,
                contract,
                receipt.sealedHistoryMaxInstalledRank(),
                receipt.legacyMaxInstalledRank(),
                migrationPrincipal);
        if (history.rowCount() != receipt.sealedHistoryRowCount()
                || history.maxInstalledRank() != receipt.sealedHistoryMaxInstalledRank()
                || !history.sha256().equals(receipt.sealedHistorySha256())
                || !history.allSuccessful()
                || !history.hasLegacyPrincipal()
                || !history.postLegacyOwnedByMigration()) {
            throw failure(contract, "sealed Flyway history differs from the adoption receipt");
        }
        HistoryStatus total = historyStatus(connection, contract, migrationPrincipal);
        if (total.rowCount() != receipt.sealedHistoryRowCount()
                || total.maxInstalledRank() != receipt.sealedHistoryMaxInstalledRank()) {
            throw failure(contract, "unsealed Flyway history rows exist");
        }

        InventoryDigest stored = digestStoredInventoryForApprovedOwners(
                connection, contract, approvedOwners);
        InventoryDigest live = digestLiveInventoryForApprovedOwners(
                connection, contract, approvedOwners);
        if (stored.objectCount() != receipt.inventoryObjectCount()
                || !stored.sha256().equals(receipt.inventorySha256())
                || !live.equals(stored)) {
            throw failure(contract,
                    "live protected-object inventory differs from the adoption receipt");
        }
        if (!digestReceipt(receipt).equals(receipt.receiptSha256())) {
            throw failure(contract, "adoption receipt canonical digest is invalid");
        }
        return receipt;
    }

    private static HistoryStatus historyStatus(
            Connection connection,
            Contract contract,
            String migrationPrincipal) throws SQLException {
        if (!relationExists(connection, contract.historySchema(), contract.historyTable())) {
            return new HistoryStatus(false, 0, 0, 0);
        }
        String sql = "SELECT COUNT(*), COALESCE(MAX(installed_rank), 0), "
                + "COUNT(*) FILTER (WHERE NOT success OR installed_by <> ?) FROM "
                + qualified(contract.historySchema(), contract.historyTable());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migrationPrincipal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw failure(contract, "Flyway history status returned no row");
                }
                return new HistoryStatus(
                        true, result.getInt(1), result.getInt(2), result.getInt(3));
            }
        }
    }

    public static HistoryDigest digestHistory(
            Connection connection,
            Contract contract,
            int sealedMaxInstalledRank,
            int legacyMaxInstalledRank,
            String migrationPrincipal) throws SQLException {
        HistoryDigestValue value = MigrationAdoptionEvidence.digestHistory(
                connection,
                contract,
                sealedMaxInstalledRank,
                legacyMaxInstalledRank,
                migrationPrincipal);
        return new HistoryDigest(
                value.rowCount(), value.maxInstalledRank(), value.sha256(),
                value.allSuccessful(), value.hasLegacyPrincipal(),
                value.postLegacyOwnedByMigration());
    }

    public static InventoryDigest digestStoredInventory(
            Connection connection,
            Contract contract,
            String migrationPrincipal) throws SQLException {
        return digestStoredInventoryForApprovedOwners(
                connection,
                contract,
                approvedProtectedObjectOwners(migrationPrincipal, Set.of()));
    }

    public static InventoryDigest digestStoredInventory(
            Connection connection,
            Contract contract,
            String migrationPrincipal,
            Set<String> additionalProtectedObjectOwners) throws SQLException {
        return digestStoredInventoryForApprovedOwners(
                connection,
                contract,
                approvedProtectedObjectOwners(
                        migrationPrincipal, additionalProtectedObjectOwners));
    }

    private static InventoryDigest digestStoredInventoryForApprovedOwners(
            Connection connection,
            Contract contract,
            Set<String> approvedOwners) throws SQLException {
        InventoryDigestValue value = MigrationAdoptionEvidence.digestStoredInventory(
                connection, contract, approvedOwners);
        return new InventoryDigest(value.objectCount(), value.sha256());
    }

    public static InventoryDigest digestLiveInventory(
            Connection connection,
            Contract contract,
            String migrationPrincipal) throws SQLException {
        return digestLiveInventoryForApprovedOwners(
                connection,
                contract,
                approvedProtectedObjectOwners(migrationPrincipal, Set.of()));
    }

    public static InventoryDigest digestLiveInventory(
            Connection connection,
            Contract contract,
            String migrationPrincipal,
            Set<String> additionalProtectedObjectOwners) throws SQLException {
        return digestLiveInventoryForApprovedOwners(
                connection,
                contract,
                approvedProtectedObjectOwners(
                        migrationPrincipal, additionalProtectedObjectOwners));
    }

    private static InventoryDigest digestLiveInventoryForApprovedOwners(
            Connection connection,
            Contract contract,
            Set<String> approvedOwners) throws SQLException {
        InventoryDigestValue value = MigrationAdoptionEvidence.digestLiveInventory(
                connection, contract, approvedOwners);
        return new InventoryDigest(value.objectCount(), value.sha256());
    }

    /** Returns the exact rows a Control runner must persist for a newly sealed receipt. */
    public static List<InventoryEntry> liveInventory(
            Connection connection,
            Contract contract,
            String migrationPrincipal) throws SQLException {
        return liveInventory(
                connection,
                contract,
                migrationPrincipal,
                Set.of());
    }

    public static List<InventoryEntry> liveInventory(
            Connection connection,
            Contract contract,
            String migrationPrincipal,
            Set<String> additionalProtectedObjectOwners) throws SQLException {
        Set<String> approvedOwners = approvedProtectedObjectOwners(
                migrationPrincipal, additionalProtectedObjectOwners);
        return MigrationAdoptionEvidence.liveInventory(
                connection, contract, approvedOwners).stream()
                .map(value -> new InventoryEntry(
                        value.objectClass(), value.oid(), value.schema(),
                        value.identity(), value.owner()))
                .toList();
    }

    public static InventoryDigest digestInventory(List<InventoryEntry> objects) {
        InventoryDigestValue value = MigrationAdoptionEvidence.digestInventory(objects);
        return new InventoryDigest(value.objectCount(), value.sha256());
    }

    public static String digestReceipt(Receipt receipt) {
        return MigrationAdoptionEvidence.digestReceipt(receipt);
    }

    static void verifyPublicControlMetadataPrivileges(
            Contract contract,
            Connection connection,
            ControlObjectIds objectIds) throws SQLException {
        MigrationAdoptionControlMetadataGuard.verifyPublicPrivileges(
                contract,
                connection,
                new MigrationAdoptionControlMetadataGuard.ObjectIds(
                        objectIds.schemaOid(),
                        objectIds.receiptOid(),
                        objectIds.inventoryOid()));
    }

    static ControlObjectIds controlObjectIds(
            Contract contract,
            Connection connection) throws SQLException {
        MigrationAdoptionControlMetadataGuard.ObjectIds ids =
                MigrationAdoptionControlMetadataGuard.controlObjectIds(
                        contract, connection);
        return new ControlObjectIds(
                ids.schemaOid(), ids.receiptOid(), ids.inventoryOid());
    }

    private static boolean relationExists(Connection connection, String schema, String table)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT to_regclass(format('%I.%I', ?, ?)) IS NOT NULL")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new IllegalStateException("database scalar query returned no row");
            }
            return result.getString(1);
        }
    }

    static String qualified(String schema, String table) {
        requireIdentifier("schema", schema);
        requireIdentifier("table", table);
        return '"' + schema + "\".\"" + table + '"';
    }

    private static void requireCanonicalInstant(Contract contract, String value) {
        requireText(contract, "sealedAtUtc", value);
        try {
            if (!Instant.parse(value).toString().equals(value)) {
                throw failure(contract, "sealedAtUtc must be a canonical UTC instant");
            }
        } catch (java.time.format.DateTimeParseException exception) {
            throw failure(contract, "sealedAtUtc must be a canonical UTC instant");
        }
    }

    private static void requireSha256(Contract contract, String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw failure(contract,
                    "external adoption SHA-256 must be 64 lowercase hexadecimal characters");
        }
    }

    private static void requireIdentifier(String name, String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,62}")) {
            throw new IllegalArgumentException(name + " must be a canonical SQL identifier");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireText(Contract contract, String name, String value) {
        if (value == null || value.isBlank()) {
            throw failure(contract, name + " must not be blank");
        }
    }

    private static Set<String> approvedProtectedObjectOwners(
            String migrationPrincipal,
            Set<String> additionalProtectedObjectOwners) {
        requireIdentifier("migrationPrincipal", migrationPrincipal);
        Objects.requireNonNull(
                additionalProtectedObjectOwners,
                "additionalProtectedObjectOwners must not be null");
        LinkedHashSet<String> approved = new LinkedHashSet<>();
        approved.add(migrationPrincipal);
        for (String owner : additionalProtectedObjectOwners) {
            requireIdentifier("additional protected-object owner", owner);
            if (migrationPrincipal.equals(owner)) {
                throw new IllegalArgumentException(
                        "additional protected-object owners must exclude migrationPrincipal");
            }
            approved.add(owner);
        }
        return Set.copyOf(approved);
    }

    private static Set<String> requireApprovedProtectedObjectOwners(
            String migrationPrincipal, Set<String> approvedOwners) {
        requireIdentifier("migrationPrincipal", migrationPrincipal);
        Objects.requireNonNull(approvedOwners, "approvedOwners must not be null");
        if (!approvedOwners.contains(migrationPrincipal)) {
            throw new IllegalArgumentException(
                    "approvedOwners must include migrationPrincipal");
        }
        for (String owner : approvedOwners) {
            requireIdentifier("approved protected-object owner", owner);
        }
        return Set.copyOf(approvedOwners);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    static IllegalStateException failure(Contract contract, String message) {
        return new IllegalStateException(
                "Migration adoption rejected for " + contract.streamKey() + ": " + message);
    }

    static IllegalStateException failure(
            Contract contract, String message, Exception cause) {
        return new IllegalStateException(
                "Migration adoption rejected for " + contract.streamKey() + ": " + message,
                cause);
    }

    public record Contract(
            String streamKey,
            String historySchema,
            String historyTable,
            List<String> protectedSchemas)
            implements MigrationAdoptionEvidenceTypes.ContractView {

        public Contract {
            if (streamKey == null || !streamKey.matches("[a-z][a-z0-9-]{0,62}")) {
                throw new IllegalArgumentException("streamKey must be a canonical key");
            }
            requireIdentifier("historySchema", historySchema);
            requireIdentifier("historyTable", historyTable);
            protectedSchemas = List.copyOf(Objects.requireNonNull(
                    protectedSchemas, "protectedSchemas must not be null"));
            if (protectedSchemas.isEmpty()) {
                throw new IllegalArgumentException("protectedSchemas must not be empty");
            }
            protectedSchemas.forEach(schema -> requireIdentifier("protectedSchema", schema));
            if (!protectedSchemas.contains(historySchema)) {
                throw new IllegalArgumentException(
                        "protectedSchemas must include the history schema");
            }
        }
    }

    public record AdoptionState(
            boolean adopted,
            int maxInstalledRank,
            int rowCount,
            Receipt receipt) {

        static AdoptionState nativeHistory(int maxInstalledRank, int rowCount) {
            return new AdoptionState(false, maxInstalledRank, rowCount, null);
        }

        static AdoptionState adopted(Receipt receipt) {
            return new AdoptionState(
                    true,
                    receipt.sealedHistoryMaxInstalledRank(),
                    receipt.sealedHistoryRowCount(),
                    receipt);
        }
    }

    public record Receipt(
            String streamKey,
            int contractVersion,
            String databaseName,
            String historySchema,
            String historyTable,
            int legacyMaxInstalledRank,
            int sealedHistoryMaxInstalledRank,
            int sealedHistoryRowCount,
            String sealedHistorySha256,
            int inventoryObjectCount,
            String inventorySha256,
            String migrationPrincipal,
            String controlReference,
            String sealedAtUtc,
            String receiptSha256)
            implements MigrationAdoptionEvidenceTypes.ReceiptView {
    }

    public record HistoryDigest(
            int rowCount,
            int maxInstalledRank,
            String sha256,
            boolean allSuccessful,
            boolean hasLegacyPrincipal,
            boolean postLegacyOwnedByMigration) {
    }

    public record InventoryDigest(int objectCount, String sha256) {
    }

    private record HistoryStatus(
            boolean exists,
            int rowCount,
            int maxInstalledRank,
            int invalidRows) {
    }

    record ControlObjectIds(long schemaOid, long receiptOid, long inventoryOid) {
    }

    public record InventoryEntry(
            String objectClass,
            long oid,
            String schema,
            String identity,
            String owner)
            implements MigrationAdoptionEvidenceTypes.InventoryEntryView {
    }
}
