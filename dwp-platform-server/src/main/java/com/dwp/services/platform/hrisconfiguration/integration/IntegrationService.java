package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.MappingRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.DefinitionEvidencePort;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;
import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationServiceInternals.*;

/** Transactional owner for definition, mapping and receipt-first execution commands. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class IntegrationService {

    private final IntegrationServiceOperations operations;

    static final String CREATE_CONNECTOR =
            IntegrationServiceInternals.CREATE_CONNECTOR;
    static final String TEST_CONNECTOR =
            IntegrationServiceInternals.TEST_CONNECTOR;
    static final String DRY_RUN_MAPPING =
            IntegrationServiceInternals.DRY_RUN_MAPPING;
    static final String CREATE_EXECUTION =
            IntegrationServiceInternals.CREATE_EXECUTION;
    static final String VALIDATE_DEFINITION =
            IntegrationServiceInternals.VALIDATE_DEFINITION;
    static final String SUBMIT_DEFINITION =
            IntegrationServiceInternals.SUBMIT_DEFINITION;
    static final String APPROVE_DEFINITION =
            IntegrationServiceInternals.APPROVE_DEFINITION;
    static final String ACTIVATE_DEFINITION =
            IntegrationServiceInternals.ACTIVATE_DEFINITION;
    static final String CREATE_MAPPING =
            IntegrationServiceInternals.CREATE_MAPPING;
    static final String VALIDATE_MAPPING =
            IntegrationServiceInternals.VALIDATE_MAPPING;
    static final String SUBMIT_MAPPING =
            IntegrationServiceInternals.SUBMIT_MAPPING;
    static final String APPROVE_MAPPING =
            IntegrationServiceInternals.APPROVE_MAPPING;
    static final String ACTIVATE_MAPPING =
            IntegrationServiceInternals.ACTIVATE_MAPPING;
    static final String RECONCILE_EXECUTION =
            IntegrationServiceInternals.RECONCILE_EXECUTION;
    static final String RETRY_EXECUTION =
            IntegrationServiceInternals.RETRY_EXECUTION;
    static final String CANCEL_EXECUTION =
            IntegrationServiceInternals.CANCEL_EXECUTION;

    static String actionForRoute(String route) {
        return IntegrationServiceInternals.actionForRoute(route);
    }
    private final IntegrationDefinitionCommands definitions;
    private final IntegrationMappingCommands mappings;
    private final IntegrationCommandArtifacts artifacts;

    private final IntegrationRepository repository;
    private final ObjectProvider<BoundaryResolutionPort> boundaries;
    private final ObjectProvider<DefinitionEvidencePort> evidence;
    private final ObjectProvider<Sys008OpaqueReferenceOwnerPort> references;
    private final IntegrationCursorCodec cursors;
    private final TransactionTemplate transactions;
    private final TransactionTemplate effects;
    private final IntegrationCanonicalJson canonical;

    public IntegrationService(
            IntegrationRepository repository,
            ObjectProvider<BoundaryResolutionPort> boundaries,
            ObjectProvider<DefinitionEvidencePort> evidence,
            ObjectProvider<Sys008OpaqueReferenceOwnerPort> references,
            IntegrationCursorCodec cursors,
            PlatformTransactionManager transactionManager,
            ObjectMapper json) {
        this.repository = repository;
        this.boundaries = boundaries;
        this.evidence = evidence;
        this.references = references;
        this.cursors = cursors;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.effects = new TransactionTemplate(transactionManager);
        this.effects.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.canonical = new IntegrationCanonicalJson(json);
        this.operations = new OwnerOperations();
        this.definitions = new IntegrationDefinitionCommands(operations, repository, canonical);
        this.mappings = new IntegrationMappingCommands(operations, repository, canonical);
        this.artifacts = new IntegrationCommandArtifacts(operations, canonical);
    }

    @Transactional(readOnly = true)
    public CatalogResponse catalog(
            Authority authority,
            OffsetDateTime asOf,
            List<String> fields,
            String freshness,
            int limit,
            String cursor,
            String filter) {
        operations.bind(authority);
        requireAuthor(authority);
        OffsetDateTime now = repository.databaseNow();
        if (asOf == null || asOf.isAfter(now.plusMinutes(1))
                || fields == null || fields.stream().anyMatch(Objects::isNull)
                || fields.stream().distinct().count() != fields.size()
                || !java.util.Set.copyOf(fields).equals(CATALOG_FIELDS)
                || !java.util.Set.of("FRESH_ONLY", "ALLOW_STALE")
                    .contains(freshness)
                || filter != null && (filter.isBlank()
                    || filter.length() > 1000
                    || !filter.equals(filter.strip()))
                || limit < 1 || limit > 100) {
            throw invalid("SYS008_CATALOG_QUERY_INVALID");
        }
        List<String> canonicalFields = fields.stream().sorted().toList();
        IntegrationCursorCodec.CatalogCursorBinding binding;
        try {
            binding = cursor == null ? null : cursors.decodeCatalog(cursor);
        } catch (IllegalArgumentException invalidCursor) {
            throw invalid("SYS008_CATALOG_CURSOR_INVALID");
        }
        if (binding != null && !binding.matches(
                authority, canonicalFields, freshness, filter, asOf.toInstant())) {
            throw invalid("SYS008_CATALOG_CURSOR_BINDING_CHANGED");
        }
        String after = binding == null
                ? null : binding.lastItemPublicId().toString();
        IntegrationRepository.CatalogPage page = repository.catalog(
                authority.tenantId(), asOf, limit, after, filter);
        OffsetDateTime freshUntil = now.plusSeconds(30);
        String next = page.nextItemPublicId() == null ? null
                : cursors.encodeCatalog(
                        new IntegrationCursorCodec.CatalogCursorBinding(
                                authority.tenantId(), authority.actorId(),
                                canonicalFields, freshness, filter,
                                asOf.toInstant(), authority.scopeRevision(),
                                authority.fieldPolicyRevision(),
                                authority.authorizationRevision(),
                                requireUuid(page.nextItemPublicId())));
        OffsetDateTime staleAfter = "FRESH_ONLY".equals(freshness)
                ? freshUntil : freshUntil.plusMinutes(5);
        return new CatalogResponse(asOf, page.items(), next,
                freshUntil, staleAfter);
    }

    @Transactional
    public CommandReceipt createConnector(Authority authority, ConnectorCommand command) {
        return definitions.createConnector(authority, command);
    }

    public CommandReceipt validateDefinition(
            Authority authority,
            UUID connectorId,
            DefinitionValidationCommand command) {
        return definitions.validateDefinition(authority, connectorId, command);
    }

    public CommandReceipt testConnector(
            Authority authority,
            UUID connectorId,
            ConnectorTestCommand command) {
        return definitions.testConnector(authority, connectorId, command);
    }

    @Transactional
    public CommandReceipt submitDefinition(
            Authority authority,
            UUID connectorId,
            ApprovalRequestCommand command) {
        return definitions.submitDefinition(authority, connectorId, command);
    }

    @Transactional
    public CommandReceipt approveDefinition(
            Authority authority,
            UUID connectorId,
            ApprovalDecisionCommand command) {
        return definitions.approveDefinition(authority, connectorId, command);
    }

    @Transactional
    public CommandReceipt activateDefinition(
            Authority authority,
            UUID connectorId,
            ActivationCommand command) {
        return definitions.activateDefinition(authority, connectorId, command);
    }

    @Transactional
    public CommandReceipt createMapping(
            Authority authority,
            UUID connectorId,
            MappingCommand command) {
        return mappings.createMapping(authority, connectorId, command);
    }

    @Transactional
    public CommandReceipt validateMapping(
            Authority authority,
            UUID connectorId,
            long version,
            MappingValidationCommand command) {
        return mappings.validateMapping(authority, connectorId, version, command);
    }

    public CommandReceipt dryRunMapping(
            Authority authority,
            UUID connectorId,
            long version,
            MappingDryRunCommand command) {
        return mappings.dryRunMapping(authority, connectorId, version, command);
    }

    @Transactional
    public CommandReceipt submitMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ApprovalRequestCommand command) {
        return mappings.submitMapping(authority, connectorId, version, command);
    }

    @Transactional
    public CommandReceipt approveMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ApprovalDecisionCommand command) {
        return mappings.approveMapping(authority, connectorId, version, command);
    }

    @Transactional
    public CommandReceipt activateMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ActivationCommand command) {
        return mappings.activateMapping(authority, connectorId, version, command);
    }

    @Transactional
    public CommandReceipt createExecution(
            Authority authority,
            UUID connectorId,
            ExecutionCommand command) {
        operations.bind(authority);
        requireExecutor(authority);
        ReservedCommand reserved = operations.reserve(authority,
                "CREATE_CONNECTOR_EXECUTION", CREATE_EXECUTION,
                connectorId.toString(), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), connectorId);
        if (definition.state() != DefinitionState.ACTIVE) {
            throw conflict("SYS008_EXECUTION_REQUIRES_ACTIVE_DEFINITION");
        }
        MappingRecord mapping = repository.lockMapping(
                authority.tenantId(), connectorId, activeMappingVersion(definition));
        if (mapping.state() != MappingState.ACTIVE) {
            throw conflict("SYS008_EXECUTION_REQUIRES_ACTIVE_MAPPING");
        }
        if (command.asOf().isBefore(mapping.effectiveFrom())
                || mapping.effectiveTo() != null
                && !command.asOf().isBefore(mapping.effectiveTo())) {
            throw conflict("SYS008_EXECUTION_MAPPING_NOT_EFFECTIVE");
        }
        IntegrationDomainPolicy.requireMakerChecker(
                mapping.authorActorId(), authority.actorId(),
                mapping.authorScopeDigest(), authority.populationScopeDigest());
        UUID executionPublicId = UUID.randomUUID();
        artifacts.resolveFrozenDefinitionBoundary(authority, definition);
        artifacts.resolveFrozenMappingBoundary(authority, mapping);
        ResolvedReference payloadReference = artifacts.resolveReference(
                authority, connectorId, executionPublicId.toString(),
                ReferenceType.PAYLOAD_REF, "sys008.execution-payload",
                command.payloadRef(), command.payloadDigest());
        var sourceManifest = operations.boundaryPort().resolvePayload(
                authority.tenantId(), connectorId, executionPublicId,
                command.payloadRef(), command.payloadDigest(),
                authority.authorizationRevision(), authority.purposeCode());
        if (!payloadReference.providerHandle().equals(
                sourceManifest.payloadHandle())) {
            throw notFound("SYS008_PAYLOAD_MANIFEST_NOT_AVAILABLE");
        }
        ConnectorExecutionRequestV1 artifact = artifacts.executionArtifact(
                authority, executionPublicId, mapping, command, reserved.now());
        String providerKey = "sys008-" + canonical.digest(
                Long.toString(authority.tenantId()), connectorId.toString(),
                command.operation().name(), authority.idempotencyKey());
        String requestDigest = canonical.digest(java.util.Map.of(
                "artifact", artifact,
                "definitionVersion", definition.definitionVersion(),
                "mappingVersion", mapping.version(),
                "endpointPolicyRevision", definition.endpointPolicyRevision(),
                "secretRevision", definition.secretRevision(),
                "sourceManifest", sourceManifest.items()));
        var execution = repository.insertExecution(
                definition, mapping, executionPublicId,
                authority.actorId(), reserved.receipt().receiptId(),
                command, providerKey, requestDigest,
                authority.authorizationRevision(),
                authority.populationScopeDigest(), authority.populationKind(),
                authority.legalEntityPublicId(), sourceManifest.items(),
                reserved.now());
        repository.appendExecutionEventAndAudit(
                reserved.seal(), execution, "EXECUTION_ACCEPTED", reserved.now());
        return repository.progressCommand(reserved.seal(),
                reserved.receipt().receiptId(),
                execution.executionId(),
                execution.aggregateRevision(), IntegrationServiceInternals.executionRef(execution.executionId()),
                reserved.now());
    }

    @Transactional(readOnly = true)
    public ExecutionResponse execution(
            Authority authority, UUID executionId) {
        operations.bind(authority);
        requireExecutor(authority);
        return repository.executionResponseAuthorized(
                authority.tenantId(), executionId,
                authority.populationScopeDigest(), authority.populationKind(),
                authority.legalEntityPublicId());
    }

    private CommandSeal seal(
            Authority authority,
            String commandType,
            String action,
            String target,
            Object body) {
        if (!action.equals(actionForRoute(authority.routeContractKey()))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_ACTION_ROUTE_BINDING_MISMATCH");
        }
        return new CommandSeal(authority.tenantId(),
                authority.subjectPrincipalPublicId(), commandType, action,
                authority.routeContractKey(), authority.contextKey(),
                authority.contextScopeKey(), authority.decisionRevision(),
                authority.populationScopeDigest(), authority.fieldPolicyRevision(),
                authority.authorizationRevision(), authority.purposeCode(),
                authority.idempotencyKey(), canonical.digest(target, canonical.digest(body)),
                authority.correlationId());
    }

    /** Keeps collaboration behind the original service bean API. */
    private final class OwnerOperations implements IntegrationServiceOperations {

        @Override
        public ReservedCommand reserve(
                Authority authority,
                String commandType,
                String action,
                String target,
                Object body) {
            CommandSeal seal = seal(authority, commandType, action, target, body);
            OffsetDateTime now = repository.databaseNow();
            IntegrationRepository.CommandReservation reservation =
                    repository.beginCommand(seal, now);
            return new ReservedCommand(
                    seal, reservation.receipt(), now, reservation.owner());
        }

        @Override
        public boolean canRunEffect(ReservedCommand reserved) {
            if (reserved.owner()
                    || reserved.receipt().status() == ReceiptStatus.ACCEPTED) {
                return true;
            }
            return reserved.receipt().status() == ReceiptStatus.RUNNING
                    && !reserved.now().isBefore(
                            reserved.receipt().createdAt()
                                    .plus(EFFECT_CLAIM_RECOVERY_AFTER));
        }

        @Override
        public ReservedCommand claimEffect(
                ReservedCommand reserved,
                UUID aggregatePublicId,
                long aggregateRevision,
                String resultRef) {
            if (reserved.receipt().status() == ReceiptStatus.RUNNING) {
                CommandReceipt unknown = repository.finishCommand(
                        reserved.seal(), reserved.receipt().receiptId(),
                        ReceiptStatus.RESULT_UNKNOWN, aggregatePublicId,
                        aggregateRevision, resultRef, reserved.now());
                return new ReservedCommand(
                        reserved.seal(), unknown, reserved.now(), false);
            }
            if (reserved.receipt().status() != ReceiptStatus.ACCEPTED) {
                throw conflict("SYS008_EFFECT_CLAIM_NOT_RECOVERABLE");
            }
            try {
                CommandReceipt running = repository.progressCommand(
                        reserved.seal(), reserved.receipt().receiptId(),
                        aggregatePublicId, aggregateRevision, resultRef,
                        reserved.now());
                return new ReservedCommand(
                        reserved.seal(), running, reserved.now(), true);
            } catch (BaseException claimConflict) {
                CommandReceipt replay = repository.findReplay(reserved.seal())
                        .filter(receipt -> receipt.status()
                                != ReceiptStatus.ACCEPTED)
                        .orElseThrow(() -> claimConflict);
                return new ReservedCommand(
                        reserved.seal(), replay, reserved.now(), false);
            }
        }

        @Override
        public CommandReceipt finalizeEffect(
                Authority authority,
                ReservedCommand reserved,
                UUID aggregatePublicId,
                long aggregateRevision,
                String resultRef,
                Supplier<CommandReceipt> finalization) {
            try {
                return inTransaction(authority, finalization);
            } catch (RuntimeException finalizationFailure) {
                try {
                    return finishUnchanged(authority, reserved, aggregatePublicId,
                            aggregateRevision, resultRef,
                            ReceiptStatus.RESULT_UNKNOWN);
                } catch (RuntimeException terminalizationFailure) {
                    finalizationFailure.addSuppressed(terminalizationFailure);
                    throw finalizationFailure;
                }
            }
        }

        @Override
        public CommandReceipt finishUnchanged(
                Authority authority,
                ReservedCommand reserved,
                UUID aggregatePublicId,
                long aggregateRevision,
                String resultRef,
                ReceiptStatus status) {
            return inTransaction(authority, () -> {
                OffsetDateTime now = repository.databaseNow();
                return repository.finishCommand(
                        reserved.seal(), reserved.receipt().receiptId(), status,
                        aggregatePublicId, aggregateRevision, resultRef, now);
            });
        }

        @Override
        public CommandReceipt finishOrRethrowEffectFailure(
                Authority authority,
                ReservedCommand reserved,
                UUID aggregatePublicId,
                long aggregateRevision,
                String resultRef,
                BaseException failure) {
            if ("SYS008_BOUNDARY_RESOLUTION_UNAVAILABLE".equals(failure.getMessage())
                    || "SYS008_VALIDATION_PROVIDER_UNAVAILABLE"
                        .equals(failure.getMessage())) {
                return finishUnchanged(authority, reserved, aggregatePublicId,
                        aggregateRevision, resultRef, ReceiptStatus.RESULT_UNKNOWN);
            }
            finishUnchanged(authority, reserved, aggregatePublicId,
                    aggregateRevision, resultRef, ReceiptStatus.REJECTED);
            throw failure;
        }

        @Override
        public ReservedCommand atDatabaseNow(ReservedCommand reserved) {
            return new ReservedCommand(reserved.seal(), reserved.receipt(),
                    repository.databaseNow(), true);
        }

        @Override
        public <T> T inTransaction(Authority authority, Supplier<T> work) {
            T result = transactions.execute(ignored -> {
                repository.bindTenantPrincipal(
                        authority.tenantId(), authority.subjectPrincipalPublicId());
                return work.get();
            });
            return Objects.requireNonNull(result,
                    "SYS008 transaction returned no result");
        }

        @Override
        public <T> T withoutTransaction(Supplier<T> effect) {
            T result = effects.execute(status -> effect.get());
            return Objects.requireNonNull(result,
                    "SYS008 validation-effect phase returned no result");
        }

        @Override
        public void bind(Authority authority) {
            requireTrustedAuthority(authority);
            repository.bindTenantPrincipal(
                    authority.tenantId(), authority.subjectPrincipalPublicId());
        }

        @Override
        public BoundaryResolutionPort boundaryPort() {
            BoundaryResolutionPort port = boundaries.getIfAvailable();
            if (port == null) {
                throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "SYS008_BOUNDARY_RESOLUTION_UNAVAILABLE");
            }
            return port;
        }

        @Override
        public DefinitionEvidencePort evidencePort() {
            DefinitionEvidencePort port = evidence.getIfAvailable();
            if (port == null) {
                throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "SYS008_VALIDATION_PROVIDER_UNAVAILABLE");
            }
            return port;
        }

        @Override
        public Sys008OpaqueReferenceOwnerPort referencePort() {
            Sys008OpaqueReferenceOwnerPort port = references.getIfAvailable();
            if (port == null) {
                throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                        "SYS008_REFERENCE_OWNER_UNAVAILABLE");
            }
            return port;
        }

        @Override
        public ConnectorExecutionRequestV1 dryRunArtifact(
                Authority authority,
                MappingRecord mapping,
                MappingDryRunCommand command,
                OffsetDateTime now) {
            return artifacts.dryRunArtifact(authority, mapping, command, now);
        }

        @Override
        public IntegrationRuntimePorts.ResolvedMappingBoundary
                resolveFrozenMappingBoundary(
                        Authority authority, MappingRecord current) {
            return artifacts.resolveFrozenMappingBoundary(authority, current);
        }

        @Override
        public ResolvedReference resolveReference(
                Authority authority,
                UUID connectorId,
                String subjectRef,
                ReferenceType type,
                String namespace,
                String reference,
                String digest) {
            return artifacts.resolveReference(
                    authority, connectorId, subjectRef, type, namespace, reference, digest);
        }

        @Override
        public IntegrationRuntimePorts.ResolvedDefinitionBoundary
                resolveFrozenDefinitionBoundary(
                        Authority authority, DefinitionRecord current) {
            return artifacts.resolveFrozenDefinitionBoundary(authority, current);
        }
    }
}
