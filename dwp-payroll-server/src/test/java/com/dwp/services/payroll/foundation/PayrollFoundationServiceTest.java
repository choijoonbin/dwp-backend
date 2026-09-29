package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationSnapshot;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandType;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CreateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.DependencyPin;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FreshnessState;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Money;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.MutationResult;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReceiptStatus;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReversalCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.RoundingPolicy;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.SimulationReport;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.UpdateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.VersionCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.DEPENDENCY_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.GROUP_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.LEGAL_ENTITY_ID;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.currency;
import static com.dwp.services.payroll.foundation.PayrollFoundationTestSupport.definition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayrollFoundationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T09:00:00Z");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private PayrollFoundationTestSupport.InMemoryStore store;
    private PayrollFoundationTestSupport.MutableDependencies dependencies;
    private PayrollFoundationService service;

    @BeforeEach
    void setUp() {
        store = new PayrollFoundationTestSupport.InMemoryStore();
        dependencies = new PayrollFoundationTestSupport.MutableDependencies();
        service = new PayrollFoundationService(
                store, dependencies, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void isolatesTenantsSupportsMultipleCurrenciesAndRejectsEffectiveOverlap() {
        FoundationDefinition definition = definition(
                LEGAL_ENTITY_ID, GROUP_ID, FROM, TO,
                Set.of(currency("USD"), currency("KRW")), List.of());

        MutationResult tenantOne = service.create(
                author(1, 101, "*"), UUID.randomUUID(), "corr-t1",
                new CreateConfigurationRequest(definition));
        MutationResult tenantTwo = service.create(
                author(2, 201, "*"), UUID.randomUUID(), "corr-t2",
                new CreateConfigurationRequest(definition));

        assertThat(service.list(author(1, 101, "*")).configurations())
                .extracting(view -> view.configurationId())
                .containsExactly(tenantOne.configuration().configurationId());
        assertThat(service.list(author(2, 201, "*")).configurations())
                .extracting(view -> view.configurationId())
                .containsExactly(tenantTwo.configuration().configurationId());
        assertThatThrownBy(() -> service.get(
                author(2, 201, "*"), tenantOne.configuration().configurationId()))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.NOT_FOUND));

        FoundationDefinition overlap = definition(
                LEGAL_ENTITY_ID, GROUP_ID, FROM.plusMonths(1), TO.plusMonths(1),
                Set.of(currency("USD"), currency("KRW")), List.of());
        assertThatThrownBy(() -> service.create(
                author(1, 101, "*"), UUID.randomUUID(), null,
                new CreateConfigurationRequest(overlap)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void usesDecimalCurrencyAndConfiguredRoundingWithoutFloatingPoint() {
        Money amount = new Money(new BigDecimal("12.345"), currency("USD"));
        RoundingPolicy policy = new RoundingPolicy(
                2, RoundingMode.HALF_EVEN, new BigDecimal("0.05"));

        assertThat(amount.rounded(policy).amount()).isEqualByComparingTo("12.35");
        assertThat(amount.rounded(policy).currency()).isEqualTo(currency("USD"));
        assertThatThrownBy(() -> new RoundingPolicy(
                2, RoundingMode.HALF_UP, new BigDecimal("0.001")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresExactSimulationAndCurrentDependencyBeforePublish() {
        DependencyPin pin = new DependencyPin("PEOPLE", DEPENDENCY_ID, 4);
        dependencies.set(1, pin, 4);
        FoundationDefinition definition = definition(
                LEGAL_ENTITY_ID, GROUP_ID, FROM, TO,
                Set.of(currency("USD")), List.of(pin));
        MutationResult created = create(author(1, 101, "*"), definition);
        UUID id = created.configuration().configurationId();

        assertThatThrownBy(() -> service.publish(
                publisher(1, 202, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(1)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        MutationResult simulated = service.simulate(
                author(1, 101, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(1));
        assertThat(simulated.configuration().status()).isEqualTo(Lifecycle.SIMULATED);
        assertThat(simulated.configuration().version()).isEqualTo(2);

        dependencies.set(1, pin, 5);
        assertThat(service.get(publisher(1, 202, "*"), id).freshness().state())
                .isEqualTo(FreshnessState.STALE);
        assertThat(service.get(publisher(1, 202, "*"), id).access().canPublish()).isFalse();
        assertThatThrownBy(() -> service.publish(
                publisher(1, 202, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(2)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void latestVersionAuthorCannotPublishAfterAnotherAuthorUpdates() {
        MutationResult created = create(
                author(1, 101, "*"), defaultDefinition());
        UUID id = created.configuration().configurationId();
        MutationResult updated = service.update(
                author(1, 202, "*"), id, UUID.randomUUID(), "bob-update",
                new UpdateConfigurationRequest(1, defaultDefinition()));
        assertThat(updated.configuration().authorId()).isEqualTo(202);

        MutationResult simulated = service.simulate(
                author(1, 101, "*"), id, UUID.randomUUID(), "alice-simulates",
                new VersionCommand(2));
        assertThat(simulated.configuration().authorId()).isEqualTo(202);

        assertThatThrownBy(() -> service.publish(
                publisher(1, 202, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(3)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SOD_CONFLICT));

        MutationResult published = service.publish(
                publisher(1, 303, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(3));
        assertThat(published.configuration().status()).isEqualTo(Lifecycle.PUBLISHED);
        assertThat(store.versions(1, id))
                .extracting(snapshot -> snapshot.status())
                .containsExactly(
                        Lifecycle.PUBLISHED, Lifecycle.SIMULATED,
                        Lifecycle.DRAFT, Lifecycle.DRAFT);
    }

    @Test
    void optimisticConflictDoesNotReserveAnOrphanReceipt() {
        MutationResult created = create(author(1, 101, "*"), defaultDefinition());
        UUID id = created.configuration().configurationId();
        UUID rejectedCommand = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(
                author(1, 101, "*"), id, rejectedCommand, null,
                new UpdateConfigurationRequest(99, defaultDefinition())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertThat(store.receipt(1, rejectedCommand)).isEmpty();
    }

    @Test
    void scopedDuplicatePendingCreateUsesRequestScopeHintWithoutReturningCurrentState() {
        FoundationDefinition definition = defaultDefinition();
        UUID key = UUID.randomUUID();
        PayrollFoundationAccess.Actor scoped = author(
                1, 101, LEGAL_ENTITY_ID.toString());
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.CREATE, null, null, definition, null);
        store.reserve(pendingReceipt(scoped, key, CommandType.CREATE, digest, null));

        MutationResult replay = service.create(
                scoped, key, null, new CreateConfigurationRequest(definition));

        assertThat(replay.receipt().status()).isEqualTo(ReceiptStatus.PENDING);
        assertThat(replay.receipt().configurationId()).isNull();
        assertThat(replay.receipt().resultVersion()).isNull();
        assertThat(replay.configuration()).isNull();

        UUID otherLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000099");
        assertThatThrownBy(() -> service.create(
                author(1, 101, otherLegalEntity.toString()), key, null,
                new CreateConfigurationRequest(definition)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void unappliedUnknownCanOnlyBeReconciledByOwnerWithReconcileAuthority() {
        UUID key = UUID.randomUUID();
        PayrollFoundationAccess.Actor original = author(
                1, 101, LEGAL_ENTITY_ID.toString());
        CommandReceipt pending = pendingReceipt(
                original, key, CommandType.CREATE, "a".repeat(64), null);
        store.reserve(pending);
        store.replaceReceipt(pending.unknown(UUID.randomUUID(), 1L, NOW));

        assertThatThrownBy(() -> service.reconcile(
                reconciler(1, 202, "*"), key))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.reconcile(
                author(1, 101, LEGAL_ENTITY_ID.toString()), key))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        MutationResult reconciled = service.reconcile(
                reconciler(1, 101, LEGAL_ENTITY_ID.toString()), key);

        assertThat(reconciled.receipt().status()).isEqualTo(ReceiptStatus.RESULT_UNKNOWN);
        assertThat(reconciled.receipt().configurationId()).isNotNull();
        assertThat(reconciled.receipt().resultVersion()).isEqualTo(1L);
        assertThat(reconciled.configuration()).isNull();
    }

    @Test
    void filtersHistoricalVersionsOutsideTheViewersLegalEntityScope() {
        UUID movedLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000009");
        UUID movedGroup = UUID.fromString(
                "20000000-0000-0000-0000-000000000009");
        MutationResult created = create(author(1, 101, "*"), defaultDefinition());
        FoundationDefinition moved = definition(
                movedLegalEntity, movedGroup, FROM, TO,
                Set.of(currency("USD")), List.of());
        service.update(
                author(1, 101, "*"), created.configuration().configurationId(),
                UUID.randomUUID(), null,
                new UpdateConfigurationRequest(1, moved));

        List<PayrollFoundationModels.ConfigurationView> visible = service.versions(
                author(1, 202, movedLegalEntity.toString()),
                created.configuration().configurationId());

        assertThat(visible)
                .extracting(view -> view.definition().legalEntity().id())
                .containsExactly(movedLegalEntity);
        assertThat(visible).extracting(PayrollFoundationModels.ConfigurationView::version)
                .containsExactly(2L);
    }

    @Test
    void replaysEverySuccessfulMutationBeforeMutablePreconditions() {
        UUID createKey = UUID.randomUUID();
        MutationResult created = service.create(
                author(1, 101, "*"), createKey, null,
                new CreateConfigurationRequest(defaultDefinition()));
        assertThat(service.create(
                author(1, 101, "*"), createKey, null,
                new CreateConfigurationRequest(defaultDefinition())))
                .isEqualTo(created);

        UUID id = created.configuration().configurationId();
        UUID updateKey = UUID.randomUUID();
        UpdateConfigurationRequest update = new UpdateConfigurationRequest(1, defaultDefinition());
        MutationResult updated = service.update(
                author(1, 202, "*"), id, updateKey, null, update);
        assertThat(service.update(author(1, 202, "*"), id, updateKey, null, update))
                .isEqualTo(updated);

        UUID simulationKey = UUID.randomUUID();
        MutationResult simulated = service.simulate(
                author(1, 101, "*"), id, simulationKey, null, new VersionCommand(2));
        assertThat(service.simulate(
                author(1, 101, "*"), id, simulationKey, null, new VersionCommand(2)))
                .isEqualTo(simulated);

        UUID publishKey = UUID.randomUUID();
        MutationResult published = service.publish(
                publisher(1, 303, "*"), id, publishKey, null, new VersionCommand(3));
        assertThat(service.publish(
                publisher(1, 303, "*"), id, publishKey, null, new VersionCommand(3)))
                .isEqualTo(published);
    }

    @Test
    void oldCreateAndUpdateReplayExactHistoricalResultsAndHistoricalScope() {
        FoundationDefinition original = defaultDefinition();
        UUID movedLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000008");
        UUID movedGroup = UUID.fromString(
                "20000000-0000-0000-0000-000000000008");
        FoundationDefinition moved = definition(
                movedLegalEntity, movedGroup, FROM, TO,
                Set.of(currency("USD")), List.of());
        FoundationDefinition movedAgain = definition(
                movedLegalEntity, movedGroup, FROM, TO.minusDays(1),
                Set.of(currency("USD")), List.of());
        UUID createKey = UUID.randomUUID();
        MutationResult created = service.create(
                author(1, 101, "*"), createKey, null,
                new CreateConfigurationRequest(original));
        UUID updateKey = UUID.randomUUID();
        service.update(
                author(1, 101, "*"), created.configuration().configurationId(),
                updateKey, null, new UpdateConfigurationRequest(1, moved));
        service.update(
                author(1, 101, "*"), created.configuration().configurationId(),
                UUID.randomUUID(), null,
                new UpdateConfigurationRequest(2, movedAgain));

        MutationResult createReplay = service.create(
                author(1, 101, LEGAL_ENTITY_ID.toString()), createKey, null,
                new CreateConfigurationRequest(original));
        MutationResult updateReplay = service.update(
                author(1, 101, movedLegalEntity.toString()),
                created.configuration().configurationId(), updateKey, null,
                new UpdateConfigurationRequest(1, moved));

        assertExactBinding(createReplay, 1, LEGAL_ENTITY_ID);
        assertExactBinding(updateReplay, 2, movedLegalEntity);
        assertThat(updateReplay.configuration().definition().effectivePeriod().endsOn())
                .isEqualTo(TO);
        assertThatThrownBy(() -> service.create(
                author(1, 101, movedLegalEntity.toString()), createKey, null,
                new CreateConfigurationRequest(original)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentPayload() {
        MutationResult created = create(author(1, 101, "*"), defaultDefinition());
        UUID id = created.configuration().configurationId();
        UUID key = UUID.randomUUID();
        service.update(
                author(1, 101, "*"), id, key, null,
                new UpdateConfigurationRequest(1, defaultDefinition()));

        FoundationDefinition changed = definition(
                LEGAL_ENTITY_ID, GROUP_ID, FROM, TO.minusDays(1),
                Set.of(currency("USD")), List.of());
        assertThatThrownBy(() -> service.update(
                author(1, 101, "*"), id, key, null,
                new UpdateConfigurationRequest(1, changed)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void ownerLookupResolvesAppliedUnknownAndAuditorCanReconcile() {
        UUID key = UUID.randomUUID();
        store.failNextSuccessfulReceiptWrite();
        MutationResult result = service.create(
                author(1, 101, "*"), key, "unknown-correlation",
                new CreateConfigurationRequest(defaultDefinition()));

        assertThat(result.receipt().status()).isEqualTo(ReceiptStatus.RESULT_UNKNOWN);
        MutationResult ownerLookup = service.receipt(author(1, 101, "*"), key);
        assertThat(ownerLookup.receipt().status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertExactBinding(ownerLookup, 1, LEGAL_ENTITY_ID);
        assertThatThrownBy(() -> service.receipt(author(1, 202, "*"), key))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        MutationResult reconciled = service.reconcile(auditor(1, 900, "*"), key);
        assertThat(reconciled.receipt().status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertThat(reconciled.configuration()).isNotNull();
    }

    @Test
    void ownerLookupCompletesAnAppliedPendingReceiptWithExactResultBinding() {
        PayrollFoundationAccess.Actor actor = author(1, 101, LEGAL_ENTITY_ID.toString());
        UUID commandId = UUID.randomUUID();
        UUID configurationId = UUID.randomUUID();
        CommandReceipt pending = pendingReceipt(
                actor, commandId, CommandType.CREATE, "a".repeat(64), null);
        ConfigurationSnapshot applied = new ConfigurationSnapshot(
                actor.tenantId(), configurationId, 1, Lifecycle.DRAFT,
                defaultDefinition(), actor.actorId(), null, NOW, NOW, null, commandId);
        store.reserve(pending);
        store.save(null, applied, pending);

        MutationResult result = service.receipt(actor, commandId);

        assertThat(result.receipt().status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertExactBinding(result, 1, LEGAL_ENTITY_ID);
        assertThat(store.receipt(actor.tenantId(), commandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.SUCCEEDED);
    }

    @Test
    void ownerLookupCannotPromoteOrReadAnAppliedReceiptOutsideCurrentScope() {
        PayrollFoundationAccess.Actor original = author(1, 101, LEGAL_ENTITY_ID.toString());
        UUID commandId = UUID.randomUUID();
        CommandReceipt pending = pendingReceipt(
                original, commandId, CommandType.CREATE, "a".repeat(64), null);
        ConfigurationSnapshot applied = new ConfigurationSnapshot(
                original.tenantId(), UUID.randomUUID(), 1, Lifecycle.DRAFT,
                defaultDefinition(), original.actorId(), null, NOW, NOW, null, commandId);
        store.reserve(pending);
        store.save(null, applied, pending);
        UUID otherLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000099");

        assertThatThrownBy(() -> service.receipt(
                author(1, 101, otherLegalEntity.toString()), commandId))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(store.receipt(original.tenantId(), commandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.PENDING);
    }

    @Test
    void replayCannotPromoteAnAppliedReceiptOutsideCurrentScope() {
        FoundationDefinition definition = defaultDefinition();
        PayrollFoundationAccess.Actor original = author(1, 101, LEGAL_ENTITY_ID.toString());
        UUID commandId = UUID.randomUUID();
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.CREATE, null, null, definition, null);
        CommandReceipt pending = pendingReceipt(
                original, commandId, CommandType.CREATE, digest, null);
        ConfigurationSnapshot applied = new ConfigurationSnapshot(
                original.tenantId(), UUID.randomUUID(), 1, Lifecycle.DRAFT,
                definition, original.actorId(), null, NOW, NOW, null, commandId);
        store.reserve(pending);
        store.save(null, applied, pending);
        UUID otherLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000099");

        assertThatThrownBy(() -> service.create(
                author(1, 101, otherLegalEntity.toString()), commandId, null,
                new CreateConfigurationRequest(definition)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(store.receipt(original.tenantId(), commandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.PENDING);
    }

    @Test
    void reconciliationRechecksScopeBeforePromotingARacingAppliedReceipt() {
        PayrollFoundationAccess.Actor original = author(1, 101, LEGAL_ENTITY_ID.toString());
        UUID commandId = UUID.randomUUID();
        CommandReceipt pending = pendingReceipt(
                original, commandId, CommandType.CREATE, "a".repeat(64), null);
        ConfigurationSnapshot applied = new ConfigurationSnapshot(
                original.tenantId(), UUID.randomUUID(), 1, Lifecycle.DRAFT,
                defaultDefinition(), original.actorId(), null, NOW, NOW, null, commandId);
        store.reserve(pending);
        store.save(null, applied, pending);
        store.hideNextByCommand();
        UUID otherLegalEntity = UUID.fromString(
                "10000000-0000-0000-0000-000000000099");

        assertThatThrownBy(() -> service.reconcile(
                reconciler(1, 101, otherLegalEntity.toString()), commandId))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        assertThat(store.receipt(original.tenantId(), commandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.PENDING);
    }

    @Test
    void staleReconciliationCannotOverwriteARacingTerminalReversalReceipt() {
        PayrollFoundationAccess.Actor actor = reconciler(1, 303, LEGAL_ENTITY_ID.toString());
        UUID configurationId = UUID.randomUUID();
        UUID publishCommandId = UUID.randomUUID();
        UUID reversalCommandId = UUID.randomUUID();
        ConfigurationSnapshot published = new ConfigurationSnapshot(
                actor.tenantId(), configurationId, 3, Lifecycle.PUBLISHED,
                defaultDefinition(), 101, 202L, NOW, NOW, null, publishCommandId);
        CommandReceipt pending = new CommandReceipt(
                actor.tenantId(), actor.actorId(), reversalCommandId, CommandType.REVERSE,
                ReceiptStatus.PENDING, "a".repeat(64), configurationId, null,
                publishCommandId, null, null, actor.purpose(), actor.legalEntityScopeDigest(),
                actor.policyRevision(), actor.authorizationRevision(), NOW, null);
        CommandReceipt terminal = pending.reversalFailed(configurationId, 3L, NOW);
        store.replaceUnchecked(published);
        store.reserve(pending);
        store.receiptBeforeNextReceiptWrite(terminal);

        assertThatThrownBy(() -> service.reconcile(actor, reversalCommandId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.receipt(actor.tenantId(), reversalCommandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.REVERSAL_FAILED);
        MutationResult lookup = service.receipt(actor, reversalCommandId);
        assertThat(lookup.receipt().status()).isEqualTo(ReceiptStatus.REVERSAL_FAILED);
        assertThat(lookup.configuration().version()).isEqualTo(3);
    }

    @Test
    void definitiveReversalFailureCanReplaceARacingUnknownReceipt() {
        PayrollFoundationAccess.Actor actor = publisher(
                1, 303, LEGAL_ENTITY_ID.toString());
        UUID configurationId = UUID.randomUUID();
        UUID publishCommandId = UUID.randomUUID();
        UUID reversalCommandId = UUID.randomUUID();
        ConfigurationSnapshot published = new ConfigurationSnapshot(
                actor.tenantId(), configurationId, 3, Lifecycle.PUBLISHED,
                defaultDefinition(), 101, 202L, NOW, NOW, null, publishCommandId);
        ReversalCommand command = new ReversalCommand(3, publishCommandId);
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.REVERSE, configurationId, command.expectedVersion(), null,
                command.publishCommandId());
        CommandReceipt pending = new CommandReceipt(
                actor.tenantId(), actor.actorId(), reversalCommandId, CommandType.REVERSE,
                ReceiptStatus.PENDING, digest, configurationId, null,
                publishCommandId, null, null, actor.purpose(), actor.legalEntityScopeDigest(),
                actor.policyRevision(), actor.authorizationRevision(), NOW, null);
        store.replaceUnchecked(published);
        store.receiptBeforeNextReceiptWrite(pending.unknown(configurationId, 3L, NOW));

        MutationResult failed = service.reverse(
                actor, configurationId, reversalCommandId, null, command);

        assertThat(failed.receipt().status()).isEqualTo(ReceiptStatus.REVERSAL_FAILED);
        assertThat(failed.configuration().status()).isEqualTo(Lifecycle.PUBLISHED);
        assertThat(store.receipt(actor.tenantId(), reversalCommandId).orElseThrow().status())
                .isEqualTo(ReceiptStatus.REVERSAL_FAILED);
    }

    @Test
    void reconciliationAfterLaterMutationReturnsOriginalImmutableCommandResult() {
        UUID createKey = UUID.randomUUID();
        store.failNextSuccessfulReceiptWrite();
        MutationResult unknown = service.create(
                author(1, 101, "*"), createKey, "lost-create-ack",
                new CreateConfigurationRequest(defaultDefinition()));
        FoundationDefinition changed = definition(
                LEGAL_ENTITY_ID, GROUP_ID, FROM, TO.minusDays(1),
                Set.of(currency("USD")), List.of());
        service.update(
                author(1, 101, "*"), unknown.configuration().configurationId(),
                UUID.randomUUID(), null,
                new UpdateConfigurationRequest(1, changed));

        UUID unrelatedScope = UUID.fromString(
                "10000000-0000-0000-0000-000000000077");
        assertThatThrownBy(() -> service.reconcile(
                reconciler(1, 101, unrelatedScope.toString()), createKey))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        MutationResult reconciled = service.reconcile(
                auditor(1, 900, LEGAL_ENTITY_ID.toString()), createKey);

        assertThat(reconciled.receipt().status()).isEqualTo(ReceiptStatus.SUCCEEDED);
        assertExactBinding(reconciled, 1, LEGAL_ENTITY_ID);
        assertThat(reconciled.configuration().definition().effectivePeriod().endsOn())
                .isEqualTo(TO);
        assertThat(service.get(
                author(1, 101, LEGAL_ENTITY_ID.toString()),
                unknown.configuration().configurationId()).version()).isEqualTo(2);
    }

    @Test
    void recordsTerminalReversalFailureForStalePrecondition() {
        MutationResult created = create(author(1, 101, "*"), defaultDefinition());
        UUID id = created.configuration().configurationId();
        MutationResult simulated = service.simulate(
                author(1, 101, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(1));
        MutationResult published = service.publish(
                publisher(1, 202, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(simulated.configuration().version()));
        UUID reversalKey = UUID.randomUUID();

        MutationResult failed = service.reverse(
                publisher(1, 202, "*"), id, reversalKey, null,
                new ReversalCommand(
                        published.configuration().version(),
                        UUID.randomUUID()));

        assertThat(failed.receipt().status()).isEqualTo(ReceiptStatus.REVERSAL_FAILED);
        assertThat(failed.configuration().status()).isEqualTo(Lifecycle.PUBLISHED);
        assertThat(service.receipt(publisher(1, 202, "*"), reversalKey).receipt().status())
                .isEqualTo(ReceiptStatus.REVERSAL_FAILED);
    }

    @Test
    void staleReversalVersionIsRejectedBeforeCommandAdmission() {
        MutationResult created = create(author(1, 101, "*"), defaultDefinition());
        UUID id = created.configuration().configurationId();
        MutationResult simulated = service.simulate(
                author(1, 101, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(1));
        MutationResult published = service.publish(
                publisher(1, 202, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(simulated.configuration().version()));
        UUID reversalKey = UUID.randomUUID();

        assertThatThrownBy(() -> service.reverse(
                publisher(1, 202, "*"), id, reversalKey, null,
                new ReversalCommand(
                        published.configuration().version() - 1,
                        published.receipt().commandId())))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.OBJECT_VERSION_CONFLICT));
        assertThat(store.receipt(1, reversalKey)).isEmpty();
    }

    @Test
    void successfulReversalPreservesPublisherLineageAndReplaysIdempotently() {
        MutationResult created = create(author(1, 101, "*"), defaultDefinition());
        UUID id = created.configuration().configurationId();
        MutationResult simulated = service.simulate(
                author(1, 101, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(1));
        MutationResult published = service.publish(
                publisher(1, 202, "*"), id, UUID.randomUUID(), "publisher-202",
                new VersionCommand(simulated.configuration().version()));
        UUID reversalKey = UUID.randomUUID();
        ReversalCommand command = new ReversalCommand(
                published.configuration().version(), published.receipt().commandId());

        MutationResult reversed = service.reverse(
                publisher(1, 303, "*"), id, reversalKey, "reverser-303", command);
        MutationResult replayed = service.reverse(
                publisher(1, 303, "*"), id, reversalKey, "reverser-303", command);

        assertThat(reversed.configuration().status()).isEqualTo(Lifecycle.REVERSED);
        assertThat(reversed.configuration().publisherId()).isEqualTo(202L);
        assertThat(replayed).isEqualTo(reversed);
        assertThat(store.versions(1, id).getFirst().publisherId()).isEqualTo(202L);
    }

    @Test
    void reportsPartialAndUnavailableDependencyFreshness() {
        DependencyPin available = new DependencyPin("PEOPLE", DEPENDENCY_ID, 3);
        DependencyPin absent = new DependencyPin(
                "TIME", UUID.fromString("40000000-0000-0000-0000-000000000002"), 8);
        dependencies.set(1, available, 3);
        FoundationDefinition mixed = definition(
                LEGAL_ENTITY_ID, GROUP_ID, FROM, TO,
                Set.of(currency("USD")), List.of(available, absent));
        MutationResult partial = create(author(1, 101, "*"), mixed);
        assertThat(partial.configuration().freshness().state())
                .isEqualTo(FreshnessState.PARTIAL);

        PayrollFoundationTestSupport.InMemoryStore otherStore =
                new PayrollFoundationTestSupport.InMemoryStore();
        PayrollFoundationService unavailableService = new PayrollFoundationService(
                otherStore, new PayrollFoundationTestSupport.MutableDependencies(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        MutationResult unavailable = unavailableService.create(
                author(1, 101, "*"), UUID.randomUUID(), null,
                new CreateConfigurationRequest(mixed));
        assertThat(unavailable.configuration().freshness().state())
                .isEqualTo(FreshnessState.UNAVAILABLE);
    }

    @Test
    void publishProjectionRejectsMismatchedSimulationEvidence() {
        MutationResult created = create(author(1, 101, "*"), defaultDefinition());
        UUID id = created.configuration().configurationId();
        service.simulate(
                author(1, 101, "*"), id, UUID.randomUUID(), null,
                new VersionCommand(1));
        ConfigurationSnapshot current = store.current(1, id).orElseThrow();
        SimulationReport mismatched = new SimulationReport(
                current.simulation().simulationId(),
                current.version() - 1,
                current.simulation().definitionDigest(),
                current.simulation().dependencyDigest(),
                true,
                List.of(),
                current.simulation().simulatedAt(),
                current.simulation().simulatedBy());
        store.replaceUnchecked(new ConfigurationSnapshot(
                current.tenantId(), current.configurationId(), current.version(),
                Lifecycle.SIMULATED, current.definition(), current.authorId(), null,
                current.createdAt(), current.updatedAt(), mismatched,
                current.lastCommandId()));

        assertThat(service.get(publisher(1, 202, "*"), id).access().canPublish()).isFalse();
        assertThat(service.get(publisher(1, 202, "*"), id).access().publishDenialCode())
                .isEqualTo("SIMULATION_REQUIRED");
    }

    private MutationResult create(
            PayrollFoundationAccess.Actor actor, FoundationDefinition definition) {
        return service.create(
                actor, UUID.randomUUID(), null, new CreateConfigurationRequest(definition));
    }

    private FoundationDefinition defaultDefinition() {
        return definition(
                LEGAL_ENTITY_ID, GROUP_ID, FROM, TO,
                Set.of(currency("USD")), List.of());
    }

    private CommandReceipt pendingReceipt(
            PayrollFoundationAccess.Actor actor,
            UUID commandId,
            CommandType type,
            String requestDigest,
            UUID configurationId) {
        return new CommandReceipt(
                actor.tenantId(), actor.actorId(), commandId, type, ReceiptStatus.PENDING,
                requestDigest, configurationId, null, null, null, null,
                actor.purpose(), actor.legalEntityScopeDigest(), actor.policyRevision(),
                actor.authorizationRevision(), NOW, null);
    }

    private void assertExactBinding(
            MutationResult result, long version, UUID legalEntityId) {
        assertThat(result.configuration()).isNotNull();
        assertThat(result.configuration().version()).isEqualTo(version);
        assertThat(result.configuration().definition().legalEntity().id())
                .isEqualTo(legalEntityId);
        assertThat(result.receipt().configurationId())
                .isEqualTo(result.configuration().configurationId());
        assertThat(result.receipt().resultVersion())
                .isEqualTo(result.configuration().version());
    }

    private PayrollFoundationAccess.Actor author(long tenant, long actor, String scope) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "CONFIGURATION_AUTHOR",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:EDIT "
                        + "PAYROLL_FOUNDATION:SIMULATE",
                "PAYROLL_CONFIGURATION", scope);
    }

    private PayrollFoundationAccess.Actor publisher(long tenant, long actor, String scope) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "CONFIGURATION_PUBLISHER",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:PUBLISH "
                        + "PAYROLL_FOUNDATION:REVERSE PAYROLL_FOUNDATION:RECONCILE",
                "PAYROLL_CONFIGURATION", scope);
    }

    private PayrollFoundationAccess.Actor auditor(long tenant, long actor, String scope) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "PAYROLL_AUDITOR",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:VIEW PAYROLL_FOUNDATION:RECONCILE",
                "PAYROLL_AUDIT", scope);
    }

    private PayrollFoundationAccess.Actor reconciler(long tenant, long actor, String scope) {
        return PayrollFoundationAccess.actor(
                tenant, actor, "TENANT_RECONCILER",
                "APP.HRIS:VIEW PAYROLL_FOUNDATION:RECONCILE",
                "PAYROLL_AUDIT", scope);
    }
}
