package com.dwp.core.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.dwp.core.database.MigrationAdoptionGuard.Contract;
import com.dwp.core.database.MigrationAdoptionGuard.HistoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.InventoryDigest;
import com.dwp.core.database.MigrationAdoptionGuard.Receipt;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the external, Control-produced run receipt before a strict application can start.
 * The receipt is deliberately supplied by deployment state rather than discovered from the
 * database whose state it authenticates.
 */
public final class MigrationControlRunReceiptGuard {

    private static final String SCHEMA_VERSION = "2.0";
    private static final String DIGEST_VERSION = "migration-control-run-receipt-v2";
    private static final int MAX_RECEIPT_BYTES = 65_536;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern CONTROL_REFERENCE =
            Pattern.compile("dwp-migration-control-v2:[0-9a-f]{64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern POSTGRES_VERSION =
            Pattern.compile("[0-9]+(?:\\.[0-9]+){1,2}");
    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "schemaVersion", "mode", "service", "database", "migrationPrincipal",
            "controlReference", "previousRunReceiptSha256", "postgresVersion",
            "temporaryPrivilegeRevoked", "streams", "receiptSha256");
    private static final Set<String> STREAM_FIELDS = Set.of(
            "streamKey", "historyMaxInstalledRank", "historyRowCount", "historySha256",
            "inventoryObjectCount", "inventorySha256", "adoptionReceiptSha256");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private MigrationControlRunReceiptGuard() {
    }

    public static RunReceipt verify(
            String expectedService,
            List<Contract> expectedContracts,
            DataSource migrationDataSource,
            String migrationPrincipal,
            String expectedControlReference,
            String externalReceiptJson,
            String expectedRunReceiptSha256) {
        return verify(
                expectedService,
                expectedContracts,
                migrationDataSource,
                migrationPrincipal,
                expectedControlReference,
                externalReceiptJson,
                expectedRunReceiptSha256,
                Set.of());
    }

    public static RunReceipt verify(
            String expectedService,
            List<Contract> expectedContracts,
            DataSource migrationDataSource,
            String migrationPrincipal,
            String expectedControlReference,
            String externalReceiptJson,
            String expectedRunReceiptSha256,
            Set<String> additionalProtectedObjectOwners) {
        requireIdentifier("expectedService", expectedService);
        Objects.requireNonNull(expectedContracts, "expectedContracts must not be null");
        Objects.requireNonNull(migrationDataSource, "migrationDataSource must not be null");
        requireIdentifier("migrationPrincipal", migrationPrincipal);
        requireAdditionalProtectedObjectOwners(
                migrationPrincipal, additionalProtectedObjectOwners);
        requireControlReference("expectedControlReference", expectedControlReference);
        requireSha256("expectedRunReceiptSha256", expectedRunReceiptSha256);
        RunReceipt receipt = parse(externalReceiptJson);
        if (!expectedRunReceiptSha256.equals(receipt.receiptSha256())) {
            throw failure(expectedService,
                    "run receipt digest differs from external deployment settings");
        }
        List<String> expectedStreams = expectedContracts.stream()
                .map(Contract::streamKey).sorted().toList();
        if (expectedStreams.isEmpty()
                || expectedStreams.size() != new HashSet<>(expectedStreams).size()
                || !receipt.streams().stream().map(StreamSeal::streamKey).toList()
                        .equals(expectedStreams)) {
            throw failure(expectedService,
                    "run receipt stream set differs from the exact service contract");
        }
        if (!expectedService.equals(receipt.service())
                || !expectedControlReference.equals(receipt.controlReference())
                || !migrationPrincipal.equals(receipt.migrationPrincipal())) {
            throw failure(expectedService,
                    "run receipt service, Control reference, or migration principal differs");
        }
        try (Connection connection = migrationDataSource.getConnection()) {
            String database = scalar(connection, "SELECT current_database()");
            String principal = scalar(connection, "SELECT current_user");
            String sessionPrincipal = scalar(connection, "SELECT session_user");
            String postgresVersion = canonicalPostgresVersion(connection);
            if (!database.equals(receipt.database())
                    || !principal.equals(receipt.migrationPrincipal())
                    || !sessionPrincipal.equals(receipt.migrationPrincipal())
                    || !postgresVersion.equals(receipt.postgresVersion())) {
                throw failure(expectedService,
                        "run receipt database identity or PostgreSQL version differs from the migration connection");
            }
            verifyNativeStreams(
                    connection,
                    receipt,
                    expectedContracts,
                    migrationPrincipal,
                    additionalProtectedObjectOwners);
        } catch (SQLException exception) {
            throw failure(expectedService, "cannot verify Control run receipt", exception);
        }
        return receipt;
    }

    /** Re-checks live native state after application Flyway's required no-op invocation. */
    public static void verifyNativeStateUnchanged(
            RunReceipt receipt,
            List<Contract> expectedContracts,
            DataSource migrationDataSource,
            String migrationPrincipal) {
        verifyNativeStateUnchanged(
                receipt,
                expectedContracts,
                migrationDataSource,
                migrationPrincipal,
                Set.of());
    }

    public static void verifyNativeStateUnchanged(
            RunReceipt receipt,
            List<Contract> expectedContracts,
            DataSource migrationDataSource,
            String migrationPrincipal,
            Set<String> additionalProtectedObjectOwners) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        Objects.requireNonNull(expectedContracts, "expectedContracts must not be null");
        Objects.requireNonNull(migrationDataSource, "migrationDataSource must not be null");
        requireIdentifier("migrationPrincipal", migrationPrincipal);
        requireAdditionalProtectedObjectOwners(
                migrationPrincipal, additionalProtectedObjectOwners);
        try (Connection connection = migrationDataSource.getConnection()) {
            if (!receipt.database().equals(scalar(connection, "SELECT current_database()"))
                    || !migrationPrincipal.equals(scalar(connection, "SELECT current_user"))
                    || !migrationPrincipal.equals(scalar(connection, "SELECT session_user"))
                    || !receipt.postgresVersion().equals(
                            canonicalPostgresVersion(connection))) {
                throw failure(receipt.service(),
                        "migration connection identity or PostgreSQL version changed after application Flyway");
            }
            verifyNativeStreams(
                    connection,
                    receipt,
                    expectedContracts,
                    migrationPrincipal,
                    additionalProtectedObjectOwners);
        } catch (SQLException exception) {
            throw failure(receipt.service(),
                    "cannot re-verify native Control run receipt", exception);
        }
    }

    /** Enforces mutually exclusive native versus adopted application configuration. */
    public static void verifyAdoptionBinding(
            RunReceipt receipt,
            Contract contract,
            String expectedAdoptionReceiptSha256,
            String expectedAdoptionControlReference,
            Receipt databaseReceipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        Objects.requireNonNull(contract, "contract must not be null");
        StreamSeal stream = receipt.stream(contract.streamKey());
        String expectedDigest = nullToEmpty(expectedAdoptionReceiptSha256);
        String expectedReference = nullToEmpty(expectedAdoptionControlReference);
        if (stream.nativeStream()) {
            if (!expectedDigest.isEmpty() || !expectedReference.isEmpty()
                    || databaseReceipt != null) {
                throw failure(receipt.service(),
                        contract.streamKey() + " native and adopted settings are mutually exclusive");
            }
            return;
        }
        if (databaseReceipt == null
                || !stream.adoptionReceiptSha256().equals(expectedDigest)
                || !receipt.controlReference().equals(expectedReference)
                || !stream.adoptionReceiptSha256().equals(databaseReceipt.receiptSha256())
                || stream.historyMaxInstalledRank()
                        != databaseReceipt.sealedHistoryMaxInstalledRank()
                || stream.historyRowCount() != databaseReceipt.sealedHistoryRowCount()
                || !stream.historySha256().equals(databaseReceipt.sealedHistorySha256())
                || stream.inventoryObjectCount() != databaseReceipt.inventoryObjectCount()
                || !stream.inventorySha256().equals(databaseReceipt.inventorySha256())
                || !receipt.controlReference().equals(databaseReceipt.controlReference())) {
            throw failure(receipt.service(),
                    contract.streamKey()
                            + " adopted stream differs from its database and external seals");
        }
    }

    static RunReceipt parse(String externalReceiptJson) {
        if (externalReceiptJson == null || externalReceiptJson.isBlank()) {
            throw new IllegalStateException("Migration Control run receipt JSON is required");
        }
        if (externalReceiptJson.getBytes(StandardCharsets.UTF_8).length > MAX_RECEIPT_BYTES) {
            throw new IllegalStateException("Migration Control run receipt JSON is too large");
        }
        try {
            JsonNode root = MAPPER.readTree(externalReceiptJson);
            requireObject(root, "run receipt");
            requireExactFields(root, TOP_LEVEL_FIELDS, "run receipt");
            JsonNode streamsNode = root.get("streams");
            if (!streamsNode.isArray() || streamsNode.isEmpty()) {
                throw new IllegalStateException(
                        "Migration Control run receipt streams must be a nonempty array");
            }
            List<StreamSeal> streams = new ArrayList<>();
            for (JsonNode streamNode : streamsNode) {
                requireObject(streamNode, "stream seal");
                requireExactFields(streamNode, STREAM_FIELDS, "stream seal");
                streams.add(new StreamSeal(
                        text(streamNode, "streamKey"),
                        integer(streamNode, "historyMaxInstalledRank"),
                        integer(streamNode, "historyRowCount"),
                        text(streamNode, "historySha256"),
                        integer(streamNode, "inventoryObjectCount"),
                        text(streamNode, "inventorySha256"),
                        text(streamNode, "adoptionReceiptSha256")));
            }
            RunReceipt receipt = new RunReceipt(
                    text(root, "schemaVersion"),
                    text(root, "mode"),
                    text(root, "service"),
                    text(root, "database"),
                    text(root, "migrationPrincipal"),
                    text(root, "controlReference"),
                    text(root, "previousRunReceiptSha256"),
                    text(root, "postgresVersion"),
                    bool(root, "temporaryPrivilegeRevoked"),
                    streams,
                    text(root, "receiptSha256"));
            if (!digest(receipt).equals(receipt.receiptSha256())) {
                throw failure(receipt.service(), "run receipt canonical digest is invalid");
            }
            return receipt;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Migration Control run receipt JSON is invalid", exception);
        }
    }

    public static String digest(RunReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt must not be null");
        MessageDigest digest = sha256();
        append(digest, DIGEST_VERSION);
        append(digest, receipt.mode());
        append(digest, receipt.service());
        append(digest, receipt.database());
        append(digest, receipt.migrationPrincipal());
        append(digest, receipt.controlReference());
        append(digest, receipt.previousRunReceiptSha256());
        append(digest, receipt.postgresVersion());
        append(digest, receipt.temporaryPrivilegeRevoked());
        for (StreamSeal stream : receipt.streams()) {
            append(digest, stream.streamKey());
            append(digest, stream.historyMaxInstalledRank());
            append(digest, stream.historyRowCount());
            append(digest, stream.historySha256());
            append(digest, stream.inventoryObjectCount());
            append(digest, stream.inventorySha256());
            append(digest, stream.adoptionReceiptSha256());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyNativeStreams(
            Connection connection,
            RunReceipt receipt,
            List<Contract> expectedContracts,
            String migrationPrincipal,
            Set<String> additionalProtectedObjectOwners) throws SQLException {
        if (!receipt.nativeRun()) {
            return;
        }
        for (Contract contract : expectedContracts) {
            StreamSeal expected = receipt.stream(contract.streamKey());
            HistoryStatus total = historyStatus(connection, contract, migrationPrincipal);
            HistoryDigest history = MigrationAdoptionGuard.digestHistory(
                    connection,
                    contract,
                    expected.historyMaxInstalledRank(),
                    0,
                    migrationPrincipal);
            InventoryDigest inventory = MigrationAdoptionGuard.digestLiveInventory(
                    connection,
                    contract,
                    migrationPrincipal,
                    additionalProtectedObjectOwners);
            if (!history.allSuccessful()
                    || history.hasLegacyPrincipal()
                    || !history.postLegacyOwnedByMigration()
                    || total.invalidRows() != 0
                    || total.maxInstalledRank() != expected.historyMaxInstalledRank()
                    || total.rowCount() != expected.historyRowCount()
                    || history.maxInstalledRank() != expected.historyMaxInstalledRank()
                    || history.rowCount() != expected.historyRowCount()
                    || !history.sha256().equals(expected.historySha256())
                    || inventory.objectCount() != expected.inventoryObjectCount()
                    || !inventory.sha256().equals(expected.inventorySha256())) {
                throw failure(receipt.service(),
                        contract.streamKey()
                                + " native database state differs from the external Control seal");
            }
        }
    }

    private static HistoryStatus historyStatus(
            Connection connection,
            Contract contract,
            String migrationPrincipal) throws SQLException {
        String sql = "SELECT COUNT(*), COALESCE(MAX(installed_rank), 0), "
                + "COUNT(*) FILTER (WHERE NOT success OR installed_by <> ?) FROM "
                + qualified(contract.historySchema(), contract.historyTable());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, migrationPrincipal);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Flyway history status returned no row");
                }
                return new HistoryStatus(
                        result.getInt(1), result.getInt(2), result.getInt(3));
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("database identity query returned no row");
            }
            return result.getString(1);
        }
    }

    private static String canonicalPostgresVersion(Connection connection) throws SQLException {
        return scalar(connection,
                "SELECT split_part(current_setting('server_version'), ' ', 1)");
    }

    private static String qualified(String schema, String table) {
        requireIdentifier("schema", schema);
        requireIdentifier("table", table);
        return '"' + schema + "\".\"" + table + '"';
    }

    private static void requireObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("Migration Control " + label + " must be an object");
        }
    }

    private static void requireExactFields(JsonNode node, Set<String> expected, String label) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Migration Control " + label + " fields are not canonical");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException(
                    "Migration Control field " + field + " must be a string");
        }
        return value.textValue();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalStateException(
                    "Migration Control field " + field + " must be an exact integer");
        }
        return value.intValue();
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException(
                    "Migration Control field " + field + " must be a boolean");
        }
        return value.booleanValue();
    }

    private static void requireIdentifier(String name, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a canonical identifier");
        }
    }

    private static void requireAdditionalProtectedObjectOwners(
            String migrationPrincipal,
            Set<String> additionalProtectedObjectOwners) {
        Objects.requireNonNull(
                additionalProtectedObjectOwners,
                "additionalProtectedObjectOwners must not be null");
        for (String owner : additionalProtectedObjectOwners) {
            requireIdentifier("additional protected-object owner", owner);
            if (migrationPrincipal.equals(owner)) {
                throw new IllegalArgumentException(
                        "additional protected-object owners must exclude migrationPrincipal");
            }
        }
    }

    private static void requireSha256(String name, String value) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalStateException(name + " must be a lowercase SHA-256 digest");
        }
    }

    private static void requireControlReference(String name, String value) {
        if (value == null || !CONTROL_REFERENCE.matcher(value).matches()) {
            throw new IllegalStateException(name + " must be a canonical Control reference");
        }
    }

    private static void append(MessageDigest digest, Object value) {
        String canonical = value instanceof Boolean bool
                ? (bool ? "true" : "false")
                : Objects.requireNonNull(value, "canonical digest field must not be null")
                        .toString();
        byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static IllegalStateException failure(String service, String message) {
        return new IllegalStateException(
                "Migration Control run receipt rejected for " + service + ": " + message);
    }

    private static IllegalStateException failure(
            String service, String message, Exception cause) {
        return new IllegalStateException(
                "Migration Control run receipt rejected for " + service + ": " + message,
                cause);
    }

    public record RunReceipt(
            String schemaVersion,
            String mode,
            String service,
            String database,
            String migrationPrincipal,
            String controlReference,
            String previousRunReceiptSha256,
            String postgresVersion,
            boolean temporaryPrivilegeRevoked,
            List<StreamSeal> streams,
            String receiptSha256) {

        public RunReceipt {
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !Set.of("NATIVE_FRESH", "ADOPTED_FRESH", "ADOPTED").contains(mode)) {
                throw new IllegalStateException("Migration Control run receipt version/mode is invalid");
            }
            requireIdentifier("service", service);
            requireIdentifier("database", database);
            requireIdentifier("migrationPrincipal", migrationPrincipal);
            if (controlReference == null
                    || !CONTROL_REFERENCE.matcher(controlReference).matches()) {
                throw failure(service, "controlReference is not canonical");
            }
            if (previousRunReceiptSha256 == null
                    || (!previousRunReceiptSha256.isEmpty()
                            && !SHA256.matcher(previousRunReceiptSha256).matches())) {
                throw failure(service, "previous run receipt digest is not canonical");
            }
            if (postgresVersion == null
                    || !POSTGRES_VERSION.matcher(postgresVersion).matches()
                    || !temporaryPrivilegeRevoked) {
                throw failure(service,
                        "PostgreSQL version or temporary-privilege state is invalid");
            }
            streams = List.copyOf(Objects.requireNonNull(streams, "streams must not be null"));
            if (streams.isEmpty()
                    || !streams.equals(streams.stream()
                            .sorted(Comparator.comparing(StreamSeal::streamKey)).toList())
                    || streams.size() != streams.stream().map(StreamSeal::streamKey)
                            .distinct().count()) {
                throw failure(service, "streams must be unique and canonically ordered");
            }
            boolean nativeMode = "NATIVE_FRESH".equals(mode);
            if (streams.stream().anyMatch(stream -> stream.nativeStream() != nativeMode)) {
                throw failure(service, "mode and stream adoption seals are inconsistent");
            }
            requireSha256("receiptSha256", receiptSha256);
        }

        public boolean nativeRun() {
            return "NATIVE_FRESH".equals(mode);
        }

        public StreamSeal stream(String streamKey) {
            return streams.stream().filter(stream -> stream.streamKey().equals(streamKey))
                    .findFirst()
                    .orElseThrow(() -> failure(service,
                            "missing stream seal " + streamKey));
        }
    }

    public record StreamSeal(
            String streamKey,
            int historyMaxInstalledRank,
            int historyRowCount,
            String historySha256,
            int inventoryObjectCount,
            String inventorySha256,
            String adoptionReceiptSha256) {

        public StreamSeal {
            if (streamKey == null || !streamKey.matches("[a-z][a-z0-9-]{0,62}")) {
                throw new IllegalStateException("Control stream key is not canonical");
            }
            if (historyMaxInstalledRank < 0
                    || historyRowCount < 0
                    || inventoryObjectCount < 1) {
                throw new IllegalStateException("Control stream counts are not canonical");
            }
            requireSha256("historySha256", historySha256);
            requireSha256("inventorySha256", inventorySha256);
            if (adoptionReceiptSha256 == null
                    || (!adoptionReceiptSha256.isEmpty()
                            && !SHA256.matcher(adoptionReceiptSha256).matches())) {
                throw new IllegalStateException(
                        "Control stream adoption receipt digest is not canonical");
            }
        }

        public boolean nativeStream() {
            return adoptionReceiptSha256.isEmpty();
        }
    }

    private record HistoryStatus(int rowCount, int maxInstalledRank, int invalidRows) {
    }
}
