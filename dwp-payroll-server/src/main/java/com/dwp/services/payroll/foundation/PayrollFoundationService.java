package com.dwp.services.payroll.foundation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static com.dwp.services.payroll.foundation.PayrollFoundationAccess.Actor;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.AccessProjection;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceipt;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandReceiptView;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CommandType;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationSnapshot;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ConfigurationView;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.CreateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.DependencyPin;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationAction;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FoundationDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Freshness;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.FreshnessState;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.Lifecycle;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.MutationResult;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReceiptStatus;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.ReversalCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.SimulationReport;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.UpdateConfigurationRequest;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.VersionCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationModels.WorkspaceView;
import static com.dwp.services.payroll.foundation.PayrollFoundationServiceRules.conflict;
import static com.dwp.services.payroll.foundation.PayrollFoundationServiceRules.invalid;
import static com.dwp.services.payroll.foundation.PayrollFoundationServiceRules.normalizeCorrelation;
import static com.dwp.services.payroll.foundation.PayrollFoundationServiceRules.requireCommand;
import static com.dwp.services.payroll.foundation.PayrollFoundationServiceRules.requireDefinition;
import static com.dwp.services.payroll.foundation.PayrollFoundationServiceRules.requireEditable;
import static com.dwp.services.payroll.foundation.PayrollFoundationServiceRules.requireExpectedVersion;

/** Application service for the bounded payroll-foundation lifecycle. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.payroll-foundation.wave1.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PayrollFoundationService {

    private final PayrollFoundationStore store;
    private final PayrollDependencyVersionSource dependencies;
    private final Clock clock;

    @Autowired
    PayrollFoundationService(
            PayrollFoundationStore store,
            PayrollDependencyVersionSource dependencies) {
        this(store, dependencies, Clock.systemUTC());
    }

    PayrollFoundationService(
            PayrollFoundationStore store,
            PayrollDependencyVersionSource dependencies,
            Clock clock) {
        this.store = store;
        this.dependencies = dependencies;
        this.clock = clock;
    }

    WorkspaceView list(Actor actor) {
        List<ConfigurationView> configurations = store.currentForTenant(actor.tenantId()).stream()
                .filter(snapshot -> actor.allows(
                        FoundationAction.VIEW, snapshot.definition().legalEntity().id()))
                .map(snapshot -> view(actor, snapshot))
                .toList();
        return new WorkspaceView(
                configurations,
                new AccessProjection(
                        actor.projectsAny(FoundationAction.CREATE),
                        false,
                        false,
                        false,
                        false,
                        actor.projectsAny(FoundationAction.RECONCILE),
                        "NO_CONFIGURATION_SELECTED"),
                List.of());
    }

    ConfigurationView get(Actor actor, UUID configurationId) {
        ConfigurationSnapshot snapshot = requireConfiguration(actor.tenantId(), configurationId);
        actor.require(FoundationAction.VIEW, snapshot.definition().legalEntity().id());
        return view(actor, snapshot);
    }

    List<ConfigurationView> versions(Actor actor, UUID configurationId) {
        ConfigurationSnapshot current = requireConfiguration(actor.tenantId(), configurationId);
        actor.require(FoundationAction.VIEW, current.definition().legalEntity().id());
        return store.versions(actor.tenantId(), configurationId).stream()
                .filter(snapshot -> actor.allows(
                        FoundationAction.VIEW, snapshot.definition().legalEntity().id()))
                .map(snapshot -> view(actor, snapshot))
                .toList();
    }

    MutationResult create(
            Actor actor,
            UUID commandId,
            String correlationId,
            CreateConfigurationRequest request) {
        FoundationDefinition definition = requireDefinition(request == null ? null : request.definition());
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.CREATE, null, null, definition, null);
        Optional<MutationResult> existing = existingCommand(
                actor, commandId, CommandType.CREATE, digest,
                definition.legalEntity().id());
        if (existing.isPresent()) {
            return existing.get();
        }
        actor.require(FoundationAction.CREATE, definition.legalEntity().id());
        rejectOverlap(actor.tenantId(), null, definition);
        ReservationOutcome reservation = reserve(
                actor, commandId, CommandType.CREATE, digest, null, null, correlationId);
        if (!reservation.created()) {
            return replay(actor, reservation.receipt(), definition.legalEntity().id());
        }

        Instant now = clock.instant();
        ConfigurationSnapshot created = new ConfigurationSnapshot(
                actor.tenantId(), UUID.randomUUID(), 1, Lifecycle.DRAFT, definition,
                actor.actorId(), null, now, now, null, commandId);
        return persist(actor, reservation.receipt(), null, created);
    }

    MutationResult update(
            Actor actor,
            UUID configurationId,
            UUID commandId,
            String correlationId,
            UpdateConfigurationRequest request) {
        FoundationDefinition definition = requireDefinition(request == null ? null : request.definition());
        long expectedVersion = request.expectedVersion();
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.UPDATE, configurationId, expectedVersion, definition, null);
        Optional<MutationResult> existing = existingCommand(
                actor, commandId, CommandType.UPDATE, digest,
                definition.legalEntity().id());
        if (existing.isPresent()) {
            return existing.get();
        }
        ConfigurationSnapshot current = requireConfiguration(actor.tenantId(), configurationId);
        actor.require(FoundationAction.UPDATE, current.definition().legalEntity().id());
        actor.require(FoundationAction.UPDATE, definition.legalEntity().id());
        requireExpectedVersion(current, expectedVersion);
        requireEditable(current);
        rejectOverlap(actor.tenantId(), configurationId, definition);
        ReservationOutcome reservation = reserve(
                actor, commandId, CommandType.UPDATE, digest, configurationId, null, correlationId);
        if (!reservation.created()) {
            return replay(actor, reservation.receipt(), definition.legalEntity().id());
        }

        ConfigurationSnapshot updated = new ConfigurationSnapshot(
                current.tenantId(), current.configurationId(), current.version() + 1,
                Lifecycle.DRAFT, definition, actor.actorId(), null,
                current.createdAt(), clock.instant(), null, commandId);
        return persist(actor, reservation.receipt(), current, updated);
    }

    MutationResult simulate(
            Actor actor,
            UUID configurationId,
            UUID commandId,
            String correlationId,
            VersionCommand command) {
        long expectedVersion = requireCommand(command).expectedVersion();
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.SIMULATE, configurationId, expectedVersion, null, null);
        Optional<MutationResult> existing = existingCommand(
                actor, commandId, CommandType.SIMULATE, digest, null);
        if (existing.isPresent()) {
            return existing.get();
        }
        ConfigurationSnapshot current = requireConfiguration(actor.tenantId(), configurationId);
        actor.require(FoundationAction.SIMULATE, current.definition().legalEntity().id());
        requireExpectedVersion(current, expectedVersion);
        requireEditable(current);
        ReservationOutcome reservation = reserve(
                actor, commandId, CommandType.SIMULATE, digest, configurationId, null, correlationId);
        if (!reservation.created()) {
            return replay(actor, reservation.receipt(), null);
        }

        List<String> findings = simulationFindings(current);
        Instant now = clock.instant();
        SimulationReport report = new SimulationReport(
                UUID.randomUUID(), current.version() + 1,
                PayrollFoundationCanonical.definitionDigest(current.definition()),
                PayrollFoundationCanonical.dependencyDigest(current.definition()),
                findings.isEmpty(), findings, now, actor.actorId());
        ConfigurationSnapshot simulated = new ConfigurationSnapshot(
                current.tenantId(), current.configurationId(), current.version() + 1,
                findings.isEmpty() ? Lifecycle.SIMULATED : Lifecycle.DRAFT,
                current.definition(), current.authorId(), null,
                current.createdAt(), now, report, commandId);
        return persist(actor, reservation.receipt(), current, simulated);
    }

    MutationResult publish(
            Actor actor,
            UUID configurationId,
            UUID commandId,
            String correlationId,
            VersionCommand command) {
        long expectedVersion = requireCommand(command).expectedVersion();
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.PUBLISH, configurationId, expectedVersion, null, null);
        Optional<MutationResult> existing = existingCommand(
                actor, commandId, CommandType.PUBLISH, digest, null);
        if (existing.isPresent()) {
            return existing.get();
        }
        ConfigurationSnapshot current = requireConfiguration(actor.tenantId(), configurationId);
        actor.require(FoundationAction.PUBLISH, current.definition().legalEntity().id());
        requireExpectedVersion(current, expectedVersion);
        if (current.authorId() == actor.actorId()) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The most recent configuration author cannot publish that version.");
        }
        requirePublishable(current);
        ReservationOutcome reservation = reserve(
                actor, commandId, CommandType.PUBLISH, digest, configurationId, null, correlationId);
        if (!reservation.created()) {
            return replay(actor, reservation.receipt(), null);
        }

        ConfigurationSnapshot published = new ConfigurationSnapshot(
                current.tenantId(), current.configurationId(), current.version() + 1,
                Lifecycle.PUBLISHED, current.definition(), current.authorId(), actor.actorId(),
                current.createdAt(), clock.instant(), current.simulation(), commandId);
        return persist(actor, reservation.receipt(), current, published);
    }

    MutationResult reverse(
            Actor actor,
            UUID configurationId,
            UUID commandId,
            String correlationId,
            ReversalCommand command) {
        if (command == null || command.expectedVersion() <= 0
                || command.publishCommandId() == null) {
            throw invalid("A positive expectedVersion and publish command receipt are required.");
        }
        String digest = PayrollFoundationCanonical.requestDigest(
                CommandType.REVERSE, configurationId, command.expectedVersion(), null,
                command.publishCommandId());
        Optional<MutationResult> existing = existingCommand(
                actor, commandId, CommandType.REVERSE, digest, null);
        if (existing.isPresent()) {
            return existing.get();
        }
        ConfigurationSnapshot current = requireConfiguration(actor.tenantId(), configurationId);
        actor.require(FoundationAction.REVERSE, current.definition().legalEntity().id());
        requireExpectedVersion(current, command.expectedVersion());
        ReservationOutcome reservation = reserve(
                actor, commandId, CommandType.REVERSE, digest, configurationId,
                command.publishCommandId(), correlationId);
        if (!reservation.created()) {
            return replay(actor, reservation.receipt(), null);
        }

        Optional<CommandReceipt> publishReceipt = store.receipt(
                actor.tenantId(), command.publishCommandId());
        boolean reversible = current.status() == Lifecycle.PUBLISHED
                && current.authorId() != actor.actorId()
                && publishReceipt.filter(receipt -> receipt.commandType() == CommandType.PUBLISH
                        && receipt.status() == ReceiptStatus.SUCCEEDED
                        && configurationId.equals(receipt.configurationId())
                        && Long.valueOf(current.version()).equals(receipt.resultVersion()))
                        .isPresent();
        if (!reversible) {
            CommandReceipt failed = reservation.receipt().reversalFailed(
                    configurationId, current.version(), clock.instant());
            store.replaceReceipt(failed);
            return new MutationResult(receiptView(failed), view(actor, current));
        }

        ConfigurationSnapshot reversed = new ConfigurationSnapshot(
                current.tenantId(), current.configurationId(), current.version() + 1,
                Lifecycle.REVERSED, current.definition(), current.authorId(), current.publisherId(),
                current.createdAt(), clock.instant(), current.simulation(), commandId);
        return persist(actor, reservation.receipt(), current, reversed);
    }

    MutationResult receipt(Actor actor, UUID commandId) {
        CommandReceipt receipt = requireReceipt(actor.tenantId(), commandId);
        Optional<ConfigurationSnapshot> applied = store.byCommand(
                actor.tenantId(), commandId);
        ConfigurationSnapshot snapshot = applied.isPresent()
                ? applied.get()
                : receiptSnapshot(actor.tenantId(), receipt).orElse(null);
        CommandReceipt effective = receipt;
        if ((receipt.status() == ReceiptStatus.PENDING
                || receipt.status() == ReceiptStatus.RESULT_UNKNOWN)
                && snapshot != null
                && snapshot.lastCommandId().equals(commandId)) {
            effective = receipt.complete(
                    snapshot.configurationId(), snapshot.version(), clock.instant());
        }
        authorizeReceiptLookup(actor, effective, Optional.ofNullable(snapshot));
        if (effective != receipt) {
            store.replaceReceipt(effective);
        }
        return new MutationResult(
                receiptView(effective), snapshot == null ? null : view(actor, snapshot));
    }

    MutationResult reconcile(Actor actor, UUID commandId) {
        CommandReceipt receipt = requireReceipt(actor.tenantId(), commandId);
        authorizeReceiptReconciliation(actor, receipt);
        if (receipt.status() == ReceiptStatus.SUCCEEDED
                || receipt.status() == ReceiptStatus.REVERSAL_FAILED) {
            ConfigurationSnapshot snapshot = receiptSnapshot(actor.tenantId(), receipt).orElse(null);
            requireReconcileScopeIfPresent(actor, snapshot);
            return new MutationResult(
                    receiptView(receipt), snapshot == null ? null : view(actor, snapshot));
        }
        Optional<ConfigurationSnapshot> applied = store.byCommand(actor.tenantId(), commandId);
        CommandReceipt reconciled = applied
                .map(snapshot -> receipt.complete(
                        snapshot.configurationId(), snapshot.version(), clock.instant()))
                .orElseGet(() -> receipt.unknown(
                        receipt.configurationId(), receipt.resultVersion(), clock.instant()));
        ConfigurationSnapshot snapshot = receiptSnapshot(actor.tenantId(), reconciled).orElse(null);
        requireReconcileScopeIfPresent(actor, snapshot);
        store.replaceReceipt(reconciled);
        return new MutationResult(
                receiptView(reconciled), snapshot == null ? null : view(actor, snapshot));
    }

    private MutationResult persist(
            Actor actor,
            CommandReceipt pending,
            ConfigurationSnapshot previous,
            ConfigurationSnapshot updated) {
        try {
            store.save(previous, updated, pending);
        } catch (RuntimeException exception) {
            markUnknown(pending, updated.configurationId(), updated.version());
            throw exception;
        }
        CommandReceipt completed = pending.complete(
                updated.configurationId(), updated.version(), clock.instant());
        try {
            store.replaceReceipt(completed);
        } catch (RuntimeException completionFailure) {
            CommandReceipt unknown = pending.unknown(
                    updated.configurationId(), updated.version(), clock.instant());
            store.replaceReceipt(unknown);
            return new MutationResult(receiptView(unknown), view(actor, updated));
        }
        return new MutationResult(receiptView(completed), view(actor, updated));
    }

    private void markUnknown(CommandReceipt pending, UUID configurationId, Long version) {
        try {
            store.replaceReceipt(pending.unknown(configurationId, version, clock.instant()));
        } catch (RuntimeException ignored) {
            // The PENDING durable row remains the reconciliation handle.
        }
    }

    private ReservationOutcome reserve(
            Actor actor,
            UUID commandId,
            CommandType type,
            String requestDigest,
            UUID configurationId,
            UUID reversalOf,
            String correlationId) {
        if (commandId == null) {
            throw invalid("Idempotency-Key must be a UUID.");
        }
        Instant now = clock.instant();
        CommandReceipt pending = new CommandReceipt(
                actor.tenantId(), actor.actorId(), commandId, type, ReceiptStatus.PENDING,
                requestDigest, configurationId, null, reversalOf, null,
                normalizeCorrelation(correlationId), actor.purpose(),
                actor.legalEntityScopeDigest(), actor.policyRevision(),
                actor.authorizationRevision(), now, null);
        ReceiptReservation reservation = store.reserve(pending);
        CommandReceipt stored = reservation.receipt();
        if (stored.actorId() != actor.actorId()
                || stored.commandType() != type
                || !stored.requestDigest().equals(requestDigest)) {
            throw conflict("Idempotency-Key was already used for a different command.");
        }
        return new ReservationOutcome(stored, reservation.created());
    }

    private Optional<MutationResult> existingCommand(
            Actor actor,
            UUID commandId,
            CommandType type,
            String requestDigest,
            UUID legalEntityScopeHint) {
        if (commandId == null) {
            throw invalid("Idempotency-Key must be a UUID.");
        }
        return store.receipt(actor.tenantId(), commandId).map(receipt -> {
            if (receipt.actorId() != actor.actorId()
                    || receipt.commandType() != type
                    || !receipt.requestDigest().equals(requestDigest)) {
                throw conflict("Idempotency-Key was already used for a different command.");
            }
            return replay(actor, receipt, legalEntityScopeHint);
        });
    }

    private MutationResult replay(
            Actor actor, CommandReceipt receipt, UUID legalEntityScopeHint) {
        CommandReceipt effective = receipt;
        Optional<ConfigurationSnapshot> applied = store.byCommand(
                actor.tenantId(), receipt.commandId());
        Optional<ConfigurationSnapshot> exactResult = applied.isPresent()
                ? applied : receiptSnapshot(actor.tenantId(), effective);
        ConfigurationSnapshot snapshot = exactResult.orElse(null);
        if ((receipt.status() == ReceiptStatus.PENDING
                || receipt.status() == ReceiptStatus.RESULT_UNKNOWN)
                && snapshot != null
                && snapshot.lastCommandId().equals(receipt.commandId())) {
            effective = receipt.complete(
                    snapshot.configurationId(), snapshot.version(), clock.instant());
        }
        FoundationAction action = switch (effective.commandType()) {
            case CREATE -> FoundationAction.CREATE;
            case UPDATE -> FoundationAction.UPDATE;
            case SIMULATE -> FoundationAction.SIMULATE;
            case PUBLISH -> FoundationAction.PUBLISH;
            case REVERSE -> FoundationAction.REVERSE;
        };
        if (snapshot == null) {
            if (legalEntityScopeHint != null) {
                actor.require(action, legalEntityScopeHint);
            } else if (!actor.allowsUnscoped(action)) {
                throw new BaseException(ErrorCode.FORBIDDEN, "Command scope cannot be established.");
            }
        } else {
            actor.require(action, snapshot.definition().legalEntity().id());
        }
        if (effective != receipt) {
            store.replaceReceipt(effective);
        }
        return new MutationResult(
                receiptView(effective), snapshot == null ? null : view(actor, snapshot));
    }

    private void authorizeReceiptLookup(Actor actor, CommandReceipt receipt) {
        authorizeReceiptLookup(actor, receipt, receiptSnapshot(actor.tenantId(), receipt));
    }

    private void authorizeReceiptLookup(
            Actor actor,
            CommandReceipt receipt,
            Optional<ConfigurationSnapshot> configuration) {
        if (receipt.actorId() == actor.actorId()) {
            if (configuration.isPresent()) {
                actor.require(
                        FoundationAction.VIEW,
                        configuration.get().definition().legalEntity().id());
            }
            return;
        }
        requireReconcileAuthority(actor, configuration);
    }

    private void authorizeReceiptReconciliation(Actor actor, CommandReceipt receipt) {
        Optional<ConfigurationSnapshot> configuration = receiptSnapshot(actor.tenantId(), receipt);
        if (configuration.isPresent()) {
            actor.require(
                    FoundationAction.RECONCILE,
                    configuration.get().definition().legalEntity().id());
            return;
        }
        if (receipt.actorId() == actor.actorId()
                && actor.allowsAny(FoundationAction.RECONCILE)) {
            return;
        }
        throw new BaseException(ErrorCode.FORBIDDEN, "Receipt scope cannot be established.");
    }

    private void requireReconcileAuthority(
            Actor actor, Optional<ConfigurationSnapshot> configuration) {
        if (configuration.isPresent()) {
            actor.requireProjected(
                    FoundationAction.RECONCILE,
                    configuration.get().definition().legalEntity().id());
        } else {
            throw new BaseException(ErrorCode.FORBIDDEN, "Receipt scope cannot be established.");
        }
    }

    private void requireReconcileScopeIfPresent(
            Actor actor, ConfigurationSnapshot configuration) {
        if (configuration != null) {
            actor.require(
                    FoundationAction.RECONCILE,
                    configuration.definition().legalEntity().id());
        }
    }

    private Optional<ConfigurationSnapshot> receiptSnapshot(
            long tenantId, CommandReceipt receipt) {
        Optional<ConfigurationSnapshot> applied = store.byCommand(
                tenantId, receipt.commandId());
        if (applied.isPresent()) {
            return applied;
        }
        if ((receipt.status() == ReceiptStatus.SUCCEEDED
                || receipt.status() == ReceiptStatus.REVERSAL_FAILED)
                && receipt.configurationId() != null
                && receipt.resultVersion() != null) {
            return store.version(
                    tenantId, receipt.configurationId(), receipt.resultVersion());
        }
        return Optional.empty();
    }

    private ConfigurationView view(Actor actor, ConfigurationSnapshot snapshot) {
        UUID legalEntityId = snapshot.definition().legalEntity().id();
        boolean canCreate = actor.projects(FoundationAction.CREATE, legalEntityId);
        boolean editableState = snapshot.status() == Lifecycle.DRAFT
                || snapshot.status() == Lifecycle.SIMULATED;
        boolean authorPublisherConflict = snapshot.authorId() == actor.actorId();
        boolean simulationReady = hasExactSuccessfulSimulation(snapshot);
        boolean publishPermission = actor.projects(FoundationAction.PUBLISH, legalEntityId);
        DependencyFreshness dependencyFreshness = dependencyFreshness(snapshot);
        String publishDenial = null;
        if (!publishPermission) {
            publishDenial = "PERMISSION_OR_SCOPE_DENIED";
        } else if (authorPublisherConflict) {
            publishDenial = "AUTHOR_PUBLISHER_SOD";
        } else if (dependencyFreshness.state() != FreshnessState.LIVE) {
            publishDenial = "DEPENDENCY_" + dependencyFreshness.state().name();
        } else if (!simulationReady) {
            publishDenial = "SIMULATION_REQUIRED";
        }
        AccessProjection access = new AccessProjection(
                canCreate,
                editableState && actor.projects(FoundationAction.UPDATE, legalEntityId),
                editableState && actor.projects(FoundationAction.SIMULATE, legalEntityId),
                publishPermission && !authorPublisherConflict && simulationReady
                        && dependencyFreshness.state() == FreshnessState.LIVE,
                snapshot.status() == Lifecycle.PUBLISHED
                        && actor.projects(FoundationAction.REVERSE, legalEntityId)
                        && !authorPublisherConflict,
                actor.projects(FoundationAction.RECONCILE, legalEntityId),
                publishDenial);
        return new ConfigurationView(
                snapshot.configurationId(), snapshot.version(), snapshot.status(),
                snapshot.definition(), snapshot.authorId(), snapshot.publisherId(),
                snapshot.createdAt(), snapshot.updatedAt(), snapshot.simulation(),
                snapshot.lastCommandId(), access,
                new Freshness(
                        dependencyFreshness.state(),
                        dependencyFreshness.state() == FreshnessState.LIVE
                                ? snapshot.updatedAt()
                                : snapshot.simulation() != null
                                && snapshot.simulation().successful()
                                ? snapshot.simulation().simulatedAt() : null));
    }

    private CommandReceiptView receiptView(CommandReceipt receipt) {
        return new CommandReceiptView(
                receipt.commandId(), receipt.commandType(), receipt.status(),
                receipt.configurationId(), receipt.resultVersion(),
                receipt.reversalOfCommandId(), receipt.failureCode(), receipt.correlationId(),
                receipt.createdAt(), receipt.completedAt());
    }

    private void requirePublishable(ConfigurationSnapshot current) {
        if (!hasExactSuccessfulSimulation(current)) {
            throw conflict("A successful simulation of this exact version is required.");
        }
        if (!simulationFindings(current).isEmpty()) {
            throw conflict("A dependency changed after simulation. Simulate again.");
        }
    }

    private boolean hasExactSuccessfulSimulation(ConfigurationSnapshot snapshot) {
        SimulationReport simulation = snapshot.simulation();
        return snapshot.status() == Lifecycle.SIMULATED
                && simulation != null
                && simulation.successful()
                && simulation.configurationVersion() == snapshot.version()
                && simulation.definitionDigest().equals(
                PayrollFoundationCanonical.definitionDigest(snapshot.definition()))
                && simulation.dependencyDigest().equals(
                PayrollFoundationCanonical.dependencyDigest(snapshot.definition()));
    }

    private List<String> simulationFindings(ConfigurationSnapshot snapshot) {
        List<String> findings = new ArrayList<>();
        FoundationDefinition definition = snapshot.definition();
        LocalDate effectiveEnd = definition.effectivePeriod().endsOn();
        definition.payCalendar().periods().forEach(period -> {
            if (period.startsOn().isBefore(definition.effectivePeriod().startsOn())
                    || (effectiveEnd != null && period.endsOn().isAfter(effectiveEnd))) {
                findings.add("CALENDAR_PERIOD_OUTSIDE_EFFECTIVE_PERIOD:" + period.code());
            }
        });
        findings.addAll(dependencyFreshness(snapshot).findings());
        return List.copyOf(findings);
    }

    private DependencyFreshness dependencyFreshness(ConfigurationSnapshot snapshot) {
        List<String> findings = new ArrayList<>();
        int unavailable = 0;
        int stale = 0;
        for (DependencyPin pin : snapshot.definition().dependencies()) {
            OptionalLong actual = dependencies.currentVersion(snapshot.tenantId(), pin);
            if (actual.isEmpty()) {
                unavailable++;
                findings.add("DEPENDENCY_UNAVAILABLE:" + pin.key());
            } else if (actual.getAsLong() != pin.version()) {
                stale++;
                findings.add("STALE_DEPENDENCY:" + pin.key()
                        + ":EXPECTED_" + pin.version() + ":ACTUAL_" + actual.getAsLong());
            }
        }
        int total = snapshot.definition().dependencies().size();
        FreshnessState state;
        if (total == 0 || (unavailable == 0 && stale == 0)) {
            state = FreshnessState.LIVE;
        } else if (unavailable == total) {
            state = FreshnessState.UNAVAILABLE;
        } else if (unavailable > 0) {
            state = FreshnessState.PARTIAL;
        } else {
            state = FreshnessState.STALE;
        }
        return new DependencyFreshness(state, List.copyOf(findings));
    }

    private void rejectOverlap(long tenantId, UUID excludedId, FoundationDefinition definition) {
        for (ConfigurationSnapshot existing : store.currentForTenant(tenantId)) {
            if (!existing.configurationId().equals(excludedId)
                    && existing.status() != Lifecycle.REVERSED
                    && existing.definition().payrollGroup().id()
                    .equals(definition.payrollGroup().id())
                    && existing.definition().effectivePeriod()
                    .overlaps(definition.effectivePeriod())) {
                throw conflict("Payroll group effective periods cannot overlap.");
            }
        }
    }

    private ConfigurationSnapshot requireConfiguration(long tenantId, UUID id) {
        if (id == null) {
            throw invalid("configuration id is required");
        }
        return store.current(tenantId, id)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "Payroll foundation configuration was not found."));
    }

    private CommandReceipt requireReceipt(long tenantId, UUID commandId) {
        if (commandId == null) {
            throw invalid("command id is required");
        }
        return store.receipt(tenantId, commandId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND, "Payroll foundation command receipt was not found."));
    }

    private record ReservationOutcome(CommandReceipt receipt, boolean created) {
    }

    private record DependencyFreshness(FreshnessState state, List<String> findings) {
    }
}
