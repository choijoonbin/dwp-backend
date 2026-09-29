package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.ReceiptState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable, transport-neutral coordination for work-regime commands.
 *
 * <p>The coordinator deliberately does not own authorization, aggregate mutation, or HTTP concerns. A caller
 * supplies already verified authority evidence and a command action. The durable receipt is claimed before that
 * action runs, so a retry with the same tenant, operation, and idempotency key observes the original receipt and
 * never repeats the action.
 */
public final class WorkRegimeCommandCoordinator {

    private static final String RESULT_UNKNOWN = "RESULT_UNKNOWN";
    public static final Duration DEFAULT_RECOVERY_LEASE = Duration.ofMinutes(5);
    private static final Duration MINIMUM_RECOVERY_LEASE = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_RECOVERY_LEASE = Duration.ofHours(1);

    private final ReceiptStore receipts;
    private final Clock clock;
    private final Duration recoveryLease;

    public WorkRegimeCommandCoordinator(ReceiptStore receipts, Clock clock) {
        this(receipts, clock, DEFAULT_RECOVERY_LEASE);
    }

    public WorkRegimeCommandCoordinator(
            ReceiptStore receipts, Clock clock, Duration recoveryLease) {
        this.receipts = Objects.requireNonNull(receipts, "receipts must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.recoveryLease = requireRecoveryLease(recoveryLease);
    }

    /**
     * Claims and runs a new command. An existing claim is always returned unchanged; only the winner that creates
     * the ACCEPTED receipt is allowed to invoke {@code work}.
     */
    public CommandReceipt execute(Command command, CommandWork work) {
        Objects.requireNonNull(work, "work must not be null");
        return executeWithReceipt(command, (value, ignored) -> work.perform(value));
    }

    /**
     * Receipt-aware variant for owner persistence that must bind generated evidence to the exact
     * durable command claim. The callback is invoked only after the receipt is RUNNING.
     */
    public CommandReceipt executeWithReceipt(Command command, ReceiptAwareCommandWork work) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(work, "work must not be null");

        Reservation reservation = receipts.reserve(command, now());
        requireExactBinding(command, reservation.stored().receipt());
        if (!reservation.created()) {
            return reservation.stored().receipt();
        }

        StoredReceipt running = receipts.transition(
                command.tenantId(),
                reservation.stored().receipt().receiptId(),
                ReceiptPhase.ACCEPTED,
                ReceiptPhase.RUNNING,
                null,
                null,
                now());

        TerminalOutcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    work.perform(command, running.receipt()),
                    "command work must return an outcome");
        } catch (Exception failure) {
            restoreInterrupt(failure);
            return transitionOrRead(
                    command.tenantId(), running.receipt().receiptId(), ReceiptPhase.RUNNING,
                    ReceiptPhase.RESULT_UNKNOWN, RESULT_UNKNOWN, null);
        }

        return completeAfterWork(command.tenantId(), running.receipt().receiptId(), outcome);
    }

    /**
     * Reconciles an indeterminate command without allocating a replacement receipt. A concurrent recovery winner
     * owns the RECONCILING phase; later workers observe the same receipt instead of duplicating reconciliation.
     */
    public CommandReceipt recover(long tenantId, UUID receiptId, ReconciliationWork work) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        Objects.requireNonNull(work, "work must not be null");

        StoredReceipt current = receipts.find(tenantId, receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(tenantId, receiptId));
        if (current.phase().terminal() || current.phase() == ReceiptPhase.RECONCILING) {
            return current.receipt();
        }
        if (current.phase() == ReceiptPhase.ACCEPTED
                || current.phase() == ReceiptPhase.RUNNING) {
            Instant recoveryAt = now();
            Instant staleBefore = recoveryAt.minus(recoveryLease);
            if (current.receipt().updatedAt().isAfter(staleBefore)) {
                return current.receipt();
            }
            RecoveryClaim claim = receipts.claimStaleForRecovery(
                    tenantId, receiptId, staleBefore, recoveryAt);
            current = claim.stored();
            if (!claim.claimed()
                    && (current.phase() == ReceiptPhase.ACCEPTED
                    || current.phase() == ReceiptPhase.RUNNING
                    || current.phase() == ReceiptPhase.RECONCILING
                    || current.phase().terminal())) {
                return current.receipt();
            }
        }
        if (current.phase() != ReceiptPhase.RESULT_UNKNOWN) {
            throw new ReceiptTransitionException(
                    receiptId, current.phase(), ReceiptPhase.RECONCILING);
        }

        StoredReceipt reconciling;
        try {
            reconciling = receipts.transition(
                    tenantId,
                    receiptId,
                    ReceiptPhase.RESULT_UNKNOWN,
                    ReceiptPhase.RECONCILING,
                    null,
                    null,
                    now());
        } catch (ReceiptTransitionException concurrentClaim) {
            Optional<StoredReceipt> observed = safeFind(tenantId, receiptId, concurrentClaim);
            if (observed.isPresent()
                    && (observed.orElseThrow().phase() == ReceiptPhase.RECONCILING
                    || observed.orElseThrow().phase().terminal())) {
                return observed.orElseThrow().receipt();
            }
            throw concurrentClaim;
        }

        TerminalOutcome outcome;
        try {
            outcome = Objects.requireNonNull(
                    work.reconcile(reconciling.receipt()),
                    "reconciliation work must return an outcome");
        } catch (Exception failure) {
            restoreInterrupt(failure);
            return transitionOrRead(
                    tenantId, receiptId, ReceiptPhase.RECONCILING,
                    ReceiptPhase.RESULT_UNKNOWN, RESULT_UNKNOWN, null);
        }

        return completeRecovery(tenantId, receiptId, outcome);
    }

    private CommandReceipt completeAfterWork(
            long tenantId, UUID receiptId, TerminalOutcome outcome) {
        try {
            return receipts.transition(
                    tenantId,
                    receiptId,
                    ReceiptPhase.RUNNING,
                    ReceiptPhase.from(outcome.state()),
                    outcome.resultCode(),
                    outcome.resultDigest(),
                    now()).receipt();
        } catch (RuntimeException persistenceFailure) {
            Optional<StoredReceipt> observed = safeFind(tenantId, receiptId, persistenceFailure);
            if (observed.isPresent() && observed.orElseThrow().phase().terminal()) {
                return observed.orElseThrow().receipt();
            }
            try {
                return receipts.transition(
                        tenantId,
                        receiptId,
                        ReceiptPhase.RUNNING,
                        ReceiptPhase.RESULT_UNKNOWN,
                        RESULT_UNKNOWN,
                        null,
                        now()).receipt();
            } catch (RuntimeException unknownWriteFailure) {
                persistenceFailure.addSuppressed(unknownWriteFailure);
                throw persistenceFailure;
            }
        }
    }

    private CommandReceipt completeRecovery(
            long tenantId, UUID receiptId, TerminalOutcome outcome) {
        try {
            return receipts.transition(
                    tenantId,
                    receiptId,
                    ReceiptPhase.RECONCILING,
                    ReceiptPhase.from(outcome.state()),
                    outcome.resultCode(),
                    outcome.resultDigest(),
                    now()).receipt();
        } catch (RuntimeException persistenceFailure) {
            Optional<StoredReceipt> observed = safeFind(tenantId, receiptId, persistenceFailure);
            if (observed.isPresent() && observed.orElseThrow().phase().terminal()) {
                return observed.orElseThrow().receipt();
            }
            try {
                return receipts.transition(
                        tenantId,
                        receiptId,
                        ReceiptPhase.RECONCILING,
                        ReceiptPhase.RESULT_UNKNOWN,
                        RESULT_UNKNOWN,
                        null,
                        now()).receipt();
            } catch (RuntimeException failedWriteFailure) {
                persistenceFailure.addSuppressed(failedWriteFailure);
                throw persistenceFailure;
            }
        }
    }

    private CommandReceipt transitionOrRead(
            long tenantId,
            UUID receiptId,
            ReceiptPhase expected,
            ReceiptPhase next,
            String resultCode,
            String resultDigest) {
        try {
            return receipts.transition(
                    tenantId, receiptId, expected, next, resultCode, resultDigest, now()).receipt();
        } catch (RuntimeException persistenceFailure) {
            Optional<StoredReceipt> observed = safeFind(tenantId, receiptId, persistenceFailure);
            if (observed.isPresent() && observed.orElseThrow().phase() == next) {
                return observed.orElseThrow().receipt();
            }
            throw persistenceFailure;
        }
    }

    private Optional<StoredReceipt> safeFind(
            long tenantId, UUID receiptId, RuntimeException persistenceFailure) {
        try {
            return receipts.find(tenantId, receiptId);
        } catch (RuntimeException readFailure) {
            persistenceFailure.addSuppressed(readFailure);
            return Optional.empty();
        }
    }

    private Instant now() {
        return Objects.requireNonNull(clock.instant(), "clock returned null");
    }

    private static void restoreInterrupt(Throwable failure) {
        Throwable candidate = failure;
        while (candidate != null) {
            if (candidate instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            candidate = candidate.getCause();
        }
    }

    static void requireExactBinding(Command command, CommandReceipt receipt) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(receipt, "receipt must not be null");
        boolean exact = receipt.tenantId() == command.tenantId()
                && receipt.idempotencyKey().equals(command.idempotencyKey())
                && receipt.operation() == command.operation()
                && receipt.aggregateId().equals(command.aggregateId())
                && receipt.scopePublicRef().equals(command.scopePublicRef())
                && Objects.equals(receipt.expectedVersion(), command.expectedVersion())
                && receipt.requestDigest().equals(command.requestDigest())
                && receipt.actorId() == command.authority().actorId()
                && receipt.purpose().equals(command.authority().purpose());
        if (!exact) {
            throw new IdempotencyConflictException(
                    command.tenantId(), command.operation(), command.idempotencyKey());
        }
    }

    private static Duration requireRecoveryLease(Duration recoveryLease) {
        Objects.requireNonNull(recoveryLease, "recoveryLease must not be null");
        if (recoveryLease.compareTo(MINIMUM_RECOVERY_LEASE) < 0
                || recoveryLease.compareTo(MAXIMUM_RECOVERY_LEASE) > 0) {
            throw new IllegalArgumentException(
                    "recoveryLease must be between " + MINIMUM_RECOVERY_LEASE
                            + " and " + MAXIMUM_RECOVERY_LEASE);
        }
        return recoveryLease;
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
    }

    public record Command(
            Authority authority,
            UUID idempotencyKey,
            LifecycleAction operation,
            UUID aggregateId,
            String scopePublicRef,
            Long expectedVersion,
            String requestDigest,
            UUID correlationId) {

        public Command {
            Objects.requireNonNull(authority, "authority must not be null");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(aggregateId, "aggregateId must not be null");
            scopePublicRef = WorkRegimeModels.requireBoundedText(
                    scopePublicRef, "scopePublicRef", 128);
            if (expectedVersion != null && expectedVersion <= 0) {
                throw new IllegalArgumentException("expectedVersion must be positive when supplied");
            }
            if ((operation == LifecycleAction.CREATE_DRAFT) != (expectedVersion == null)) {
                throw new IllegalArgumentException(
                        "only CREATE_DRAFT may omit expectedVersion");
            }
            requestDigest = WorkRegimeModels.requireDigest(requestDigest, "requestDigest");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
        }

        public long tenantId() {
            return authority.tenantId();
        }
    }

    public record TerminalOutcome(ReceiptState state, String resultCode, String resultDigest) {

        public TerminalOutcome {
            Objects.requireNonNull(state, "state must not be null");
            if (state != ReceiptState.SUCCEEDED
                    && state != ReceiptState.REJECTED
                    && state != ReceiptState.FAILED) {
                throw new IllegalArgumentException(
                        "outcome state must be SUCCEEDED, REJECTED or FAILED");
            }
            resultCode = WorkRegimeModels.requireText(resultCode, "resultCode");
            resultDigest = resultDigest == null
                    ? null
                    : WorkRegimeModels.requireDigest(resultDigest, "resultDigest");
            if (state == ReceiptState.SUCCEEDED && resultDigest == null) {
                throw new IllegalArgumentException("successful outcome requires resultDigest");
            }
        }

        public static TerminalOutcome succeeded(String resultCode, String resultDigest) {
            return new TerminalOutcome(ReceiptState.SUCCEEDED, resultCode, resultDigest);
        }

        public static TerminalOutcome failed(String resultCode, String resultDigest) {
            return new TerminalOutcome(ReceiptState.FAILED, resultCode, resultDigest);
        }

        public static TerminalOutcome rejected(String resultCode, String resultDigest) {
            return new TerminalOutcome(ReceiptState.REJECTED, resultCode, resultDigest);
        }
    }

    @FunctionalInterface
    public interface CommandWork {
        TerminalOutcome perform(Command command) throws Exception;
    }

    @FunctionalInterface
    public interface ReceiptAwareCommandWork {
        TerminalOutcome perform(Command command, CommandReceipt runningReceipt) throws Exception;
    }

    /** Returns the tenant-bound durable receipt without changing it. */
    public Optional<CommandReceipt> receipt(long tenantId, UUID receiptId) {
        requireTenant(tenantId);
        Objects.requireNonNull(receiptId, "receiptId must not be null");
        return receipts.find(tenantId, receiptId).map(StoredReceipt::receipt);
    }

    /**
     * Looks up a retry by its durable origin and rejects any attempt to borrow the origin with a
     * different aggregate, scope, actor, expected version, purpose, or request body.
     */
    public Optional<CommandReceipt> findReplay(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        return receipts.findByOrigin(command).map(stored -> {
            requireExactBinding(command, stored.receipt());
            return stored.receipt();
        });
    }

    @FunctionalInterface
    public interface ReconciliationWork {
        TerminalOutcome reconcile(CommandReceipt receipt) throws Exception;
    }

    public interface ReceiptStore {
        Reservation reserve(Command command, Instant at);

        Optional<StoredReceipt> findByOrigin(Command command);

        Optional<StoredReceipt> find(long tenantId, UUID receiptId);

        RecoveryClaim claimStaleForRecovery(
                long tenantId,
                UUID receiptId,
                Instant staleBefore,
                Instant at);

        StoredReceipt transition(
                long tenantId,
                UUID receiptId,
                ReceiptPhase expected,
                ReceiptPhase next,
                String resultCode,
                String resultDigest,
                Instant at);
    }

    public record Reservation(StoredReceipt stored, boolean created) {
        public Reservation {
            Objects.requireNonNull(stored, "stored must not be null");
        }
    }

    public record RecoveryClaim(StoredReceipt stored, boolean claimed) {
        public RecoveryClaim {
            Objects.requireNonNull(stored, "stored must not be null");
            if (claimed && stored.phase() != ReceiptPhase.RESULT_UNKNOWN) {
                throw new IllegalArgumentException(
                        "claimed stale receipt must be RESULT_UNKNOWN");
            }
        }
    }

    public record StoredReceipt(CommandReceipt receipt, ReceiptPhase phase) {
        public StoredReceipt {
            Objects.requireNonNull(receipt, "receipt must not be null");
            Objects.requireNonNull(phase, "phase must not be null");
        }
    }

    public enum ReceiptPhase {
        ACCEPTED,
        RUNNING,
        RECONCILING,
        SUCCEEDED,
        REJECTED,
        FAILED,
        RESULT_UNKNOWN;

        boolean terminal() {
            return this == SUCCEEDED || this == REJECTED || this == FAILED;
        }

        ReceiptState publicState() {
            return ReceiptState.valueOf(name());
        }

        static ReceiptPhase from(ReceiptState state) {
            return ReceiptPhase.valueOf(Objects.requireNonNull(state, "state must not be null").name());
        }
    }

    public static final class AmbiguousCommandException extends Exception {
        private static final long serialVersionUID = 1L;

        public AmbiguousCommandException(String message) {
            super(message);
        }

        public AmbiguousCommandException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class IdempotencyConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IdempotencyConflictException(
                long tenantId, LifecycleAction operation, UUID idempotencyKey) {
            super("Idempotency key is already bound to a different command for tenant "
                    + tenantId + ", operation " + operation + ", key " + idempotencyKey);
        }
    }

    public static final class ReceiptNotFoundException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ReceiptNotFoundException(long tenantId, UUID receiptId) {
            super("Receipt " + receiptId + " was not found for tenant " + tenantId);
        }
    }

    public static final class ReceiptTransitionException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ReceiptTransitionException(
                UUID receiptId, ReceiptPhase actual, ReceiptPhase requested) {
            super("Receipt " + receiptId + " is " + actual + " and cannot transition to " + requested);
        }
    }
}
