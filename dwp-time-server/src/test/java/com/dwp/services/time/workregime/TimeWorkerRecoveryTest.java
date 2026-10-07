package com.dwp.services.time.workregime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Command;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.IdempotencyConflictException;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptPhase;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptStore;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.ReceiptTransitionException;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.RecoveryClaim;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.Reservation;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.StoredReceipt;
import com.dwp.services.time.workregime.WorkRegimeCommandCoordinator.TerminalOutcome;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.LifecycleAction;
import com.dwp.services.time.workregime.WorkRegimeModels.ReceiptState;
import org.junit.jupiter.api.Test;

class TimeWorkerRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-09-17T08:00:00Z");
    private static final String REQUEST_DIGEST = "a".repeat(64);
    private static final String RESULT_DIGEST = "b".repeat(64);

    @Test
    void replayReturnsTheOriginalReceiptWithoutRepeatingTheCommand() {
        InMemoryReceiptStore store = new InMemoryReceiptStore();
        WorkRegimeCommandCoordinator coordinator = coordinator(store);
        Command command = command(REQUEST_DIGEST);
        AtomicInteger executions = new AtomicInteger();

        CommandReceipt first = coordinator.execute(command, ignored -> {
            executions.incrementAndGet();
            return TerminalOutcome.succeeded("PUBLISHED", RESULT_DIGEST);
        });
        CommandReceipt replay = coordinator.execute(command, ignored -> {
            executions.incrementAndGet();
            return TerminalOutcome.failed("MUST_NOT_RUN", null);
        });

        assertThat(first.state()).isEqualTo(ReceiptState.SUCCEEDED);
        assertThat(replay).isEqualTo(first);
        assertThat(executions).hasValue(1);
        assertThat(store.receiptCount()).isOne();
        assertThat(store.history(first.receiptId()))
                .containsExactly(ReceiptPhase.ACCEPTED, ReceiptPhase.RUNNING, ReceiptPhase.SUCCEEDED);
    }

    @Test
    void digestMismatchCannotBorrowAnExistingIdempotencyKey() {
        InMemoryReceiptStore store = new InMemoryReceiptStore();
        WorkRegimeCommandCoordinator coordinator = coordinator(store);
        coordinator.execute(command(REQUEST_DIGEST),
                ignored -> TerminalOutcome.succeeded("PUBLISHED", RESULT_DIGEST));

        assertThatThrownBy(() -> coordinator.execute(command("c".repeat(64)),
                ignored -> TerminalOutcome.succeeded("PUBLISHED", RESULT_DIGEST)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(store.receiptCount()).isOne();
    }

    @Test
    void replayOriginRequiresExactScopeActorAndExpectedVersionBinding() {
        InMemoryReceiptStore store = new InMemoryReceiptStore();
        WorkRegimeCommandCoordinator coordinator = coordinator(store);
        Command original = command(REQUEST_DIGEST);
        CommandReceipt completed = coordinator.execute(
                original, ignored -> TerminalOutcome.succeeded("PUBLISHED", RESULT_DIGEST));

        assertThat(coordinator.findReplay(original)).contains(completed);
        assertThatThrownBy(() -> coordinator.findReplay(command(
                REQUEST_DIGEST, "TENANT:99", 9001L, 5L)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> coordinator.findReplay(command(
                REQUEST_DIGEST, "TENANT:41", 9002L, 5L)))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> coordinator.findReplay(command(
                REQUEST_DIGEST, "TENANT:41", 9001L, 6L)))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void staleAcceptedAndRunningReceiptsReconcileOnceWithoutRepeatingCommandWork() {
        for (ReceiptPhase abandonedPhase : List.of(
                ReceiptPhase.ACCEPTED, ReceiptPhase.RUNNING)) {
            InMemoryReceiptStore store = new InMemoryReceiptStore();
            Command command = command(REQUEST_DIGEST);
            CommandReceipt abandoned = store.seed(
                    command, abandonedPhase, NOW.minus(Duration.ofMinutes(6)));
            WorkRegimeCommandCoordinator coordinator = coordinator(store);
            AtomicInteger reconciliations = new AtomicInteger();

            CommandReceipt recovered = coordinator.recover(
                    abandoned.tenantId(), abandoned.receiptId(), ignored -> {
                        reconciliations.incrementAndGet();
                        return TerminalOutcome.succeeded("PUBLISHED", RESULT_DIGEST);
                    });
            CommandReceipt replay = coordinator.recover(
                    abandoned.tenantId(), abandoned.receiptId(), ignored -> {
                        reconciliations.incrementAndGet();
                        return TerminalOutcome.failed("MUST_NOT_RUN", null);
                    });

            assertThat(recovered.state()).isEqualTo(ReceiptState.SUCCEEDED);
            assertThat(replay).isEqualTo(recovered);
            assertThat(reconciliations).hasValue(1);
            assertThat(store.history(abandoned.receiptId())).containsExactly(
                    abandonedPhase,
                    ReceiptPhase.RESULT_UNKNOWN,
                    ReceiptPhase.RECONCILING,
                    ReceiptPhase.SUCCEEDED);
        }
    }

    @Test
    void liveAcceptedReceiptIsNotStolenBeforeRecoveryLeaseExpires() {
        InMemoryReceiptStore store = new InMemoryReceiptStore();
        CommandReceipt accepted = store.seed(
                command(REQUEST_DIGEST), ReceiptPhase.ACCEPTED, NOW.minusSeconds(299));
        AtomicInteger reconciliations = new AtomicInteger();

        CommandReceipt observed = coordinator(store).recover(
                accepted.tenantId(), accepted.receiptId(), ignored -> {
                    reconciliations.incrementAndGet();
                    return TerminalOutcome.succeeded("MUST_NOT_RUN", RESULT_DIGEST);
                });

        assertThat(observed).isEqualTo(accepted);
        assertThat(reconciliations).hasValue(0);
        assertThat(store.history(accepted.receiptId()))
                .containsExactly(ReceiptPhase.ACCEPTED);
    }

    @Test
    void recoveryLeaseCannotBeZeroOrUnbounded() {
        InMemoryReceiptStore store = new InMemoryReceiptStore();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        assertThatThrownBy(() -> new WorkRegimeCommandCoordinator(
                store, clock, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorkRegimeCommandCoordinator(
                store, clock, Duration.ofHours(1).plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uncertainExecutionReconcilesTheSameReceiptAndCannotRunAgain() {
        InMemoryReceiptStore store = new InMemoryReceiptStore();
        WorkRegimeCommandCoordinator coordinator = coordinator(store);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger reconciliations = new AtomicInteger();

        CommandReceipt unknown = coordinator.execute(command(REQUEST_DIGEST), ignored -> {
            executions.incrementAndGet();
            throw new IllegalStateException("connection disappeared after dispatch");
        });
        CommandReceipt recovered = coordinator.recover(unknown.tenantId(), unknown.receiptId(), receipt -> {
            reconciliations.incrementAndGet();
            return TerminalOutcome.succeeded("PUBLISHED", RESULT_DIGEST);
        });
        CommandReceipt replay = coordinator.recover(unknown.tenantId(), unknown.receiptId(), receipt -> {
            reconciliations.incrementAndGet();
            return TerminalOutcome.failed("MUST_NOT_RUN", null);
        });

        assertThat(unknown.state()).isEqualTo(ReceiptState.RESULT_UNKNOWN);
        assertThat(recovered.receiptId()).isEqualTo(unknown.receiptId());
        assertThat(recovered.state()).isEqualTo(ReceiptState.SUCCEEDED);
        assertThat(replay).isEqualTo(recovered);
        assertThat(executions).hasValue(1);
        assertThat(reconciliations).hasValue(1);
        assertThat(store.receiptCount()).isOne();
        assertThat(store.history(unknown.receiptId())).containsExactly(
                ReceiptPhase.ACCEPTED,
                ReceiptPhase.RUNNING,
                ReceiptPhase.RESULT_UNKNOWN,
                ReceiptPhase.RECONCILING,
                ReceiptPhase.SUCCEEDED);
    }

    @Test
    void failedReconciliationRemainsUnknownOnTheSameReceipt() {
        InMemoryReceiptStore store = new InMemoryReceiptStore();
        WorkRegimeCommandCoordinator coordinator = coordinator(store);
        CommandReceipt unknown = coordinator.execute(
                command(REQUEST_DIGEST), ignored -> { throw new InterruptedException("uncertain"); });
        Thread.interrupted();

        CommandReceipt stillUnknown = coordinator.recover(
                unknown.tenantId(), unknown.receiptId(),
                ignored -> { throw new IllegalStateException("provider unavailable"); });

        assertThat(stillUnknown.receiptId()).isEqualTo(unknown.receiptId());
        assertThat(stillUnknown.state()).isEqualTo(ReceiptState.RESULT_UNKNOWN);
        assertThat(store.history(unknown.receiptId())).endsWith(
                ReceiptPhase.RESULT_UNKNOWN,
                ReceiptPhase.RECONCILING,
                ReceiptPhase.RESULT_UNKNOWN);
    }

    private static WorkRegimeCommandCoordinator coordinator(ReceiptStore store) {
        return new WorkRegimeCommandCoordinator(
                store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Command command(String digest) {
        return command(digest, "TENANT:41", 9001L, 5L);
    }

    private static Command command(
            String digest, String scopePublicRef, long actorId, Long expectedVersion) {
        Authority authority = new Authority(
                41L,
                actorId,
                Set.of(Duty.TIME_CONFIG_APPROVER),
                Set.of(scopePublicRef),
                WorkRegimeLifecycleGuard.REQUIRED_PURPOSE,
                "decision-tim-016",
                true,
                false);
        return new Command(
                authority,
                UUID.fromString("48de8914-66c1-49f1-bbe1-cf09914f773c"),
                LifecycleAction.PUBLISH,
                UUID.fromString("1b1b0cd6-51cf-43d1-8398-86273f31aac8"),
                scopePublicRef,
                expectedVersion,
                digest,
                UUID.fromString("905a035f-65b8-48fd-916a-f00d860e08d4"));
    }

    private static final class InMemoryReceiptStore implements ReceiptStore {
        private final Map<Origin, UUID> origins = new HashMap<>();
        private final Map<UUID, StoredReceipt> receipts = new HashMap<>();
        private final Map<UUID, List<ReceiptPhase>> transitions = new HashMap<>();
        private int sequence;

        @Override
        public synchronized Reservation reserve(Command command, Instant at) {
            Origin origin = new Origin(command.tenantId(), command.operation(), command.idempotencyKey());
            UUID existingId = origins.get(origin);
            if (existingId != null) {
                StoredReceipt existing = receipts.get(existingId);
                WorkRegimeCommandCoordinator.requireExactBinding(command, existing.receipt());
                return new Reservation(existing, false);
            }
            UUID receiptId = UUID.nameUUIDFromBytes(
                    (origin + ":" + sequence++).getBytes(StandardCharsets.UTF_8));
            CommandReceipt receipt = new CommandReceipt(
                    command.tenantId(), receiptId, command.idempotencyKey(), command.operation(),
                    command.aggregateId(), command.scopePublicRef(), command.expectedVersion(),
                    command.requestDigest(), command.authority().actorId(),
                    command.authority().purpose(), ReceiptState.ACCEPTED,
                    null, null, at);
            StoredReceipt stored = new StoredReceipt(receipt, ReceiptPhase.ACCEPTED);
            origins.put(origin, receiptId);
            receipts.put(receiptId, stored);
            transitions.put(receiptId, new ArrayList<>(List.of(ReceiptPhase.ACCEPTED)));
            return new Reservation(stored, true);
        }

        @Override
        public synchronized Optional<StoredReceipt> findByOrigin(Command command) {
            UUID receiptId = origins.get(new Origin(
                    command.tenantId(), command.operation(), command.idempotencyKey()));
            if (receiptId == null) return Optional.empty();
            StoredReceipt stored = receipts.get(receiptId);
            WorkRegimeCommandCoordinator.requireExactBinding(command, stored.receipt());
            return Optional.of(stored);
        }

        @Override
        public synchronized Optional<StoredReceipt> find(long tenantId, UUID receiptId) {
            StoredReceipt stored = receipts.get(receiptId);
            return stored != null && stored.receipt().tenantId() == tenantId
                    ? Optional.of(stored) : Optional.empty();
        }

        @Override
        public synchronized RecoveryClaim claimStaleForRecovery(
                long tenantId,
                UUID receiptId,
                Instant staleBefore,
                Instant at) {
            StoredReceipt stored = find(tenantId, receiptId)
                    .orElseThrow(() -> new IllegalArgumentException("missing receipt"));
            boolean stale = !stored.receipt().updatedAt().isAfter(staleBefore);
            boolean abandoned = stored.phase() == ReceiptPhase.ACCEPTED
                    || stored.phase() == ReceiptPhase.RUNNING;
            if (!stale || !abandoned) return new RecoveryClaim(stored, false);
            CommandReceipt changed = stored.receipt().withState(
                    ReceiptState.RESULT_UNKNOWN, "RESULT_UNKNOWN", null, at);
            StoredReceipt claimed = new StoredReceipt(changed, ReceiptPhase.RESULT_UNKNOWN);
            receipts.put(receiptId, claimed);
            transitions.get(receiptId).add(ReceiptPhase.RESULT_UNKNOWN);
            return new RecoveryClaim(claimed, true);
        }

        @Override
        public synchronized StoredReceipt transition(
                long tenantId,
                UUID receiptId,
                ReceiptPhase expected,
                ReceiptPhase next,
                String resultCode,
                String resultDigest,
                Instant at) {
            StoredReceipt stored = find(tenantId, receiptId)
                    .orElseThrow(() -> new IllegalArgumentException("missing receipt"));
            if (stored.phase() != expected || stored.phase().terminal()) {
                throw new ReceiptTransitionException(receiptId, stored.phase(), next);
            }
            CommandReceipt changed = stored.receipt().withState(
                    next.publicState(), resultCode, resultDigest, at);
            StoredReceipt updated = new StoredReceipt(changed, next);
            receipts.put(receiptId, updated);
            transitions.get(receiptId).add(next);
            return updated;
        }

        int receiptCount() {
            return receipts.size();
        }

        List<ReceiptPhase> history(UUID receiptId) {
            return List.copyOf(transitions.get(receiptId));
        }

        synchronized CommandReceipt seed(
                Command command, ReceiptPhase phase, Instant updatedAt) {
            Origin origin = new Origin(
                    command.tenantId(), command.operation(), command.idempotencyKey());
            UUID receiptId = UUID.nameUUIDFromBytes(
                    (origin + ":seed:" + sequence++).getBytes(StandardCharsets.UTF_8));
            String resultCode = phase == ReceiptPhase.RESULT_UNKNOWN ? "RESULT_UNKNOWN" : null;
            CommandReceipt receipt = new CommandReceipt(
                    command.tenantId(), receiptId, command.idempotencyKey(), command.operation(),
                    command.aggregateId(), command.scopePublicRef(), command.expectedVersion(),
                    command.requestDigest(), command.authority().actorId(),
                    command.authority().purpose(), phase.publicState(),
                    resultCode, null, updatedAt);
            StoredReceipt stored = new StoredReceipt(receipt, phase);
            origins.put(origin, receiptId);
            receipts.put(receiptId, stored);
            transitions.put(receiptId, new ArrayList<>(List.of(phase)));
            return receipt;
        }

        private record Origin(long tenantId, LifecycleAction operation, UUID idempotencyKey) {
        }
    }
}
