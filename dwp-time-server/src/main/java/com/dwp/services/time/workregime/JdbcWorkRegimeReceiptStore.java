package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Command;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptNotFoundException;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptPhase;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptStore;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptTransitionException;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.RecoveryClaim;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Reservation;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.StoredReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL receipt adapter. Every RLS-protected statement runs after a transaction-local tenant binding. */
@Repository
@ConditionalOnProperty(name = "dwp.time.work-regime-api.enabled", havingValue = "true")
public final class JdbcWorkRegimeReceiptStore implements ReceiptStore {

    private static final String SET_TENANT_SQL =
            "SELECT set_config('dwp.tenant_id', ?, true)";

    private static final String INSERT_SQL = """
            INSERT INTO tim_command_receipts (
                public_id,
                tenant_id,
                idempotency_key,
                operation,
                aggregate_public_id,
                scope_public_ref,
                expected_version,
                request_digest,
                lifecycle_state,
                result_code,
                result_digest,
                actor_id,
                purpose_code,
                authorization_decision_id,
                correlation_id,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', NULL, NULL, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, operation, idempotency_key) DO NOTHING
            """;

    private static final String SELECT_BY_ORIGIN_SQL = """
            SELECT tenant_id,
                   public_id,
                   idempotency_key,
                   operation,
                   aggregate_public_id,
                   scope_public_ref,
                   expected_version,
                   request_digest,
                   actor_id,
                   purpose_code,
                   lifecycle_state,
                   result_code,
                   result_digest,
                   updated_at
              FROM tim_command_receipts
             WHERE tenant_id = ?
               AND operation = ?
               AND idempotency_key = ?
             FOR UPDATE
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT tenant_id,
                   public_id,
                   idempotency_key,
                   operation,
                   aggregate_public_id,
                   scope_public_ref,
                   expected_version,
                   request_digest,
                   actor_id,
                   purpose_code,
                   lifecycle_state,
                   result_code,
                   result_digest,
                   updated_at
              FROM tim_command_receipts
             WHERE tenant_id = ?
               AND public_id = ?
            """;

    private static final String SELECT_BY_ID_FOR_UPDATE_SQL = SELECT_BY_ID_SQL + " FOR UPDATE";

    private static final String TRANSITION_SQL = """
            UPDATE tim_command_receipts
               SET lifecycle_state = ?,
                   result_code = ?,
                   result_digest = ?,
                   updated_at = ?
             WHERE tenant_id = ?
               AND public_id = ?
               AND lifecycle_state = ?
            """;

    private static final String CLAIM_STALE_SQL = """
            UPDATE tim_command_receipts
               SET lifecycle_state = 'RESULT_UNKNOWN',
                   result_code = 'RESULT_UNKNOWN',
                   result_digest = NULL,
                   updated_at = ?
             WHERE tenant_id = ?
               AND public_id = ?
               AND lifecycle_state IN ('ACCEPTED', 'RUNNING')
               AND updated_at <= ?
            """;

    private static final RowMapper<StoredReceipt> RECEIPT_MAPPER =
            JdbcWorkRegimeReceiptStore::mapReceipt;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcWorkRegimeReceiptStore(
            JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(10);
    }

    @Override
    public Reservation reserve(Command command, Instant at) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(at, "at must not be null");

        return inTenantTransaction(command.tenantId(), () -> {
            UUID proposedReceiptId = UUID.randomUUID();
            int inserted = jdbc.update(
                    INSERT_SQL,
                    proposedReceiptId,
                    command.tenantId(),
                    command.idempotencyKey(),
                    command.operation().name(),
                    command.aggregateId(),
                    command.scopePublicRef(),
                    command.expectedVersion(),
                    command.requestDigest(),
                    command.authority().actorId(),
                    command.authority().purpose(),
                    command.authority().decisionId(),
                    command.correlationId(),
                    Timestamp.from(at),
                    Timestamp.from(at));

            StoredReceipt stored = exactlyOne(jdbc.query(
                    SELECT_BY_ORIGIN_SQL,
                    RECEIPT_MAPPER,
                    command.tenantId(),
                    command.operation().name(),
                    command.idempotencyKey()),
                    command.tenantId(),
                    command.operation(),
                    command.idempotencyKey());

            WorkRegimeCommandCoordinator.requireExactBinding(command, stored.receipt());
            boolean created = inserted == 1
                    && stored.receipt().receiptId().equals(proposedReceiptId);
            return new Reservation(stored, created);
        });
    }

    @Override
    public Optional<StoredReceipt> findByOrigin(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        return inTenantTransaction(command.tenantId(), () -> {
            List<StoredReceipt> rows = jdbc.query(
                    SELECT_BY_ORIGIN_SQL,
                    RECEIPT_MAPPER,
                    command.tenantId(),
                    command.operation().name(),
                    command.idempotencyKey());
            if (rows.size() > 1) {
                throw new IllegalStateException(
                        "Receipt origin is not unique: " + command.idempotencyKey());
            }
            Optional<StoredReceipt> stored = rows.stream().findFirst();
            stored.ifPresent(value -> WorkRegimeCommandCoordinator.requireExactBinding(
                    command, value.receipt()));
            return stored;
        });
    }

    @Override
    public Optional<StoredReceipt> find(long tenantId, UUID receiptId) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        return inTenantTransaction(tenantId, () -> {
            List<StoredReceipt> rows = jdbc.query(
                    SELECT_BY_ID_SQL, RECEIPT_MAPPER, tenantId, receiptId);
            if (rows.size() > 1) {
                throw new IllegalStateException("Receipt identity is not unique: " + receiptId);
            }
            return rows.stream().findFirst();
        });
    }

    @Override
    public RecoveryClaim claimStaleForRecovery(
            long tenantId,
            UUID receiptId,
            Instant staleBefore,
            Instant at) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(staleBefore, "staleBefore must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (staleBefore.isAfter(at)) {
            throw new IllegalArgumentException("staleBefore must not be after claim time");
        }

        return inTenantTransaction(tenantId, () -> {
            int changed = jdbc.update(
                    CLAIM_STALE_SQL,
                    Timestamp.from(at),
                    tenantId,
                    receiptId,
                    Timestamp.from(staleBefore));
            List<StoredReceipt> rows = jdbc.query(
                    SELECT_BY_ID_FOR_UPDATE_SQL, RECEIPT_MAPPER, tenantId, receiptId);
            if (rows.isEmpty()) {
                throw new ReceiptNotFoundException(tenantId, receiptId);
            }
            if (rows.size() > 1) {
                throw new IllegalStateException("Receipt identity is not unique: " + receiptId);
            }
            if (changed > 1) {
                throw new IllegalStateException("Stale receipt claim changed multiple rows");
            }
            return new RecoveryClaim(rows.getFirst(), changed == 1);
        });
    }

    @Override
    public StoredReceipt transition(
            long tenantId,
            UUID receiptId,
            ReceiptPhase expected,
            ReceiptPhase next,
            String resultCode,
            String resultDigest,
            Instant at) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(expected, "expected must not be null");
        Objects.requireNonNull(next, "next must not be null");
        Objects.requireNonNull(at, "at must not be null");
        validateTransition(expected, next, resultCode, resultDigest);

        return inTenantTransaction(tenantId, () -> {
            int changed = jdbc.update(
                    TRANSITION_SQL,
                    next.name(),
                    resultCode,
                    resultDigest,
                    Timestamp.from(at),
                    tenantId,
                    receiptId,
                    expected.name());

            List<StoredReceipt> rows = jdbc.query(
                    SELECT_BY_ID_FOR_UPDATE_SQL, RECEIPT_MAPPER, tenantId, receiptId);
            if (rows.isEmpty()) {
                throw new ReceiptNotFoundException(tenantId, receiptId);
            }
            if (rows.size() > 1) {
                throw new IllegalStateException("Receipt identity is not unique: " + receiptId);
            }

            StoredReceipt stored = rows.getFirst();
            if (changed != 1 || stored.phase() != next) {
                throw new ReceiptTransitionException(receiptId, stored.phase(), next);
            }
            return stored;
        });
    }

    private <T> T inTenantTransaction(long tenantId, Supplier<T> work) {
        requireTenant(tenantId);
        Objects.requireNonNull(work, "work must not be null");
        T value = transactions.execute(status -> {
            String expectedTenant = Long.toString(tenantId);
            String configuredTenant = jdbc.queryForObject(
                    SET_TENANT_SQL, String.class, expectedTenant);
            if (!expectedTenant.equals(configuredTenant)) {
                throw new IllegalStateException("Database tenant context was not established");
            }
            return work.get();
        });
        return Objects.requireNonNull(value, "transaction returned null");
    }

    private static StoredReceipt exactlyOne(
            List<StoredReceipt> rows,
            long tenantId,
            LifecycleAction operation,
            UUID idempotencyKey) {
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Expected one receipt for tenant " + tenantId + ", operation " + operation
                            + ", idempotency key " + idempotencyKey + "; found " + rows.size());
        }
        return rows.getFirst();
    }

    private static StoredReceipt mapReceipt(ResultSet row, int rowNumber) throws SQLException {
        ReceiptPhase phase = ReceiptPhase.valueOf(row.getString("lifecycle_state"));
        String resultCode = row.getString("result_code");
        String resultDigest = row.getString("result_digest");
        if (resultDigest != null) {
            resultDigest = resultDigest.trim();
        }
        CommandReceipt receipt = new CommandReceipt(
                row.getLong("tenant_id"),
                UUID.fromString(row.getString("public_id")),
                UUID.fromString(row.getString("idempotency_key")),
                LifecycleAction.valueOf(row.getString("operation")),
                UUID.fromString(row.getString("aggregate_public_id")),
                row.getString("scope_public_ref"),
                nullableLong(row, "expected_version"),
                row.getString("request_digest").trim(),
                row.getLong("actor_id"),
                row.getString("purpose_code"),
                phase.publicState(),
                resultCode,
                resultDigest,
                row.getTimestamp("updated_at").toInstant());
        return new StoredReceipt(receipt, phase);
    }

    private static Long nullableLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private static void validateTransition(
            ReceiptPhase expected,
            ReceiptPhase next,
            String resultCode,
            String resultDigest) {
        boolean allowed = switch (expected) {
            case ACCEPTED -> next == ReceiptPhase.RUNNING;
            case RUNNING -> next == ReceiptPhase.SUCCEEDED
                    || next == ReceiptPhase.REJECTED
                    || next == ReceiptPhase.FAILED
                    || next == ReceiptPhase.RESULT_UNKNOWN;
            case RESULT_UNKNOWN -> next == ReceiptPhase.RECONCILING;
            case RECONCILING -> next == ReceiptPhase.SUCCEEDED
                    || next == ReceiptPhase.REJECTED
                    || next == ReceiptPhase.FAILED
                    || next == ReceiptPhase.RESULT_UNKNOWN;
            case SUCCEEDED, REJECTED, FAILED -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException(
                    "Unsupported receipt transition " + expected + " -> " + next);
        }

        if (next == ReceiptPhase.RUNNING || next == ReceiptPhase.RECONCILING) {
            if (resultCode != null || resultDigest != null) {
                throw new IllegalArgumentException("in-flight receipt transition cannot carry a result");
            }
            return;
        }

        WorkRegimeModels.requireText(resultCode, "resultCode");
        if (resultDigest != null) {
            WorkRegimeModels.requireDigest(resultDigest, "resultDigest");
        }
        if (next == ReceiptPhase.SUCCEEDED && resultDigest == null) {
            throw new IllegalArgumentException("successful receipt requires resultDigest");
        }
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
    }
}
