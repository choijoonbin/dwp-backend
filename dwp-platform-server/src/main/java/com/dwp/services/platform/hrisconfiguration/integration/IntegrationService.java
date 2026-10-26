package com.dwp.services.platform.hrisconfiguration.integration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationOwnerAuthorityFilter.Authority;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.CommandSeal;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.DefinitionRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRepository.MappingRecord;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.BoundaryResolutionPort;
import com.dwp.services.platform.hrisconfiguration.integration.IntegrationRuntimePorts.DefinitionEvidencePort;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ReferenceType;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolutionRequest;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.ResolvedReference;
import com.dwp.services.platform.hrisconfiguration.integration.Sys008OpaqueReferenceOwnerPort.UnpinnedReferenceConstraint;
import com.dwp.platform.contracts.hris.generated.ConnectorExecutionRequestV1;
import com.dwp.platform.contracts.hris.generated.ConnectorMappingVersionV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.function.Supplier;

import static com.dwp.services.platform.hrisconfiguration.integration.IntegrationDtos.*;

/** Transactional owner for definition, mapping and receipt-first execution commands. */
@Service
@ConditionalOnProperty(
        name = "dwp.hris.integration-control-plane.enabled",
        havingValue = "true",
        matchIfMissing = false)
public class IntegrationService {

    private static final Duration EFFECT_CLAIM_RECOVERY_AFTER =
            Duration.ofMinutes(5);
    private static final java.util.Set<String> CATALOG_FIELDS = java.util.Set.of(
            "publicId", "key", "type", "state", "version", "updatedAt");

    static final String CREATE_CONNECTOR =
            "hris.action.sys.post.platform.v1.admin.hris.connectors";
    static final String TEST_CONNECTOR = CREATE_CONNECTOR + ".resource.test";
    static final String DRY_RUN_MAPPING =
            CREATE_CONNECTOR + ".resource.mappings.resource.dry-runs";
    static final String CREATE_EXECUTION = CREATE_CONNECTOR + ".resource.executions";
    static final String VALIDATE_DEFINITION = CREATE_CONNECTOR + ".resource.validate";
    static final String SUBMIT_DEFINITION = CREATE_CONNECTOR + ".resource.submit";
    static final String APPROVE_DEFINITION = CREATE_CONNECTOR + ".resource.approve";
    static final String ACTIVATE_DEFINITION = CREATE_CONNECTOR + ".resource.activate";
    static final String CREATE_MAPPING = CREATE_CONNECTOR + ".resource.mappings";
    static final String VALIDATE_MAPPING = CREATE_MAPPING + ".resource.validate";
    static final String SUBMIT_MAPPING = CREATE_MAPPING + ".resource.submit";
    static final String APPROVE_MAPPING = CREATE_MAPPING + ".resource.approve";
    static final String ACTIVATE_MAPPING = CREATE_MAPPING + ".resource.activate";
    static final String RECONCILE_EXECUTION =
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.reconcile";
    static final String RETRY_EXECUTION =
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.retry";
    static final String CANCEL_EXECUTION =
            "hris.action.sys.post.platform.v1.admin.hris.connector-executions.resource.cancel";

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
        bind(authority);
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
        bind(authority);
        requireAuthor(authority);
        ReservedCommand reserved = reserve(authority, "CREATE_CONNECTOR",
                CREATE_CONNECTOR, "CONNECTOR_COLLECTION", command);
        if (!reserved.owner()) return reserved.receipt();
        UUID connectorId = UUID.randomUUID();
        var boundary = boundaryPort().resolveDefinition(
                authority.tenantId(), connectorId, 1,
                authority.authorizationRevision(), authority.purposeCode());
        if (!constant(boundary.egressPolicyKey(), command.egressPolicyKey())
                || !constant(boundary.secretRef(), command.secretRef())) {
            throw notFound("SYS008_EGRESS_POLICY_NOT_AVAILABLE");
        }
        URI canonicalEndpoint = canonicalEndpoint(
                command.endpoint(), boundary.allowlist(),
                boundary.resolvedDestinations());
        URI resolvedEndpoint = canonicalEndpoint(
                boundary.endpoint(), boundary.allowlist(),
                boundary.resolvedDestinations());
        if (!canonicalEndpoint.equals(resolvedEndpoint)) {
            throw notFound("SYS008_DEFINITION_BOUNDARY_CHANGED");
        }
        ConnectorCommand canonicalCommand = new ConnectorCommand(
                command.connectorKey(), command.type(), canonicalEndpoint,
                command.secretRef(), command.egressPolicyKey());
        String secretDigest = canonical.digest(
                connectorId.toString(), canonicalEndpoint.toString(),
                command.egressPolicyKey(), command.secretRef());
        ResolvedReference secret = resolveReference(
                authority, connectorId, connectorId.toString(),
                ReferenceType.SECRET_REF, "sys008.connector-secret",
                command.secretRef(), secretDigest);
        if (!secret.providerHandle().equals(boundary.secretHandle())) {
            throw notFound("SYS008_SECRET_REFERENCE_NOT_AVAILABLE");
        }
        DefinitionRecord definition = repository.insertDefinition(
                authority.tenantId(), connectorId, authority.actorId(),
                authority.populationScopeDigest(), canonicalCommand,
                canonical.digest(canonical.digest(canonicalCommand),
                        Long.toString(boundary.endpointPolicyRevision()),
                        Long.toString(boundary.secretRevision())),
                boundary.endpointPolicyRevision(), boundary.secretRevision(),
                reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_DEFINITION",
                definition.connectorId(), definition.aggregateRevision(),
                "DEFINITION_CREATED", definition, reserved.now());
        return repository.finishCommand(reserved.seal(), reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, definition.connectorId(),
                definition.aggregateRevision(), connectorRef(definition.connectorId()),
                reserved.now());
    }

    public CommandReceipt validateDefinition(
            Authority authority,
            UUID connectorId,
            DefinitionValidationCommand command) {
        requireTrustedAuthority(authority);
        requireAuthor(authority);
        DefinitionEffectClaim claim = inTransaction(authority, () -> {
            ReservedCommand reserved = reserve(authority, "VALIDATE_DEFINITION",
                    VALIDATE_DEFINITION,
                    definitionTarget(connectorId, authority), command);
            if (!canRunEffect(reserved)) {
                return DefinitionEffectClaim.replay(reserved.receipt());
            }
            DefinitionRecord current = repository.lockDefinition(
                    authority.tenantId(), connectorId);
            requireRevision(current.aggregateRevision(), authority);
            if (!constant(current.definitionDigest(), command.definitionDigest())) {
                throw conflict("SYS008_DEFINITION_DIGEST_CHANGED");
            }
            ReservedCommand claimed = claimEffect(
                    reserved, connectorId, current.aggregateRevision(),
                    connectorRef(connectorId));
            if (!claimed.owner()) {
                return DefinitionEffectClaim.replay(claimed.receipt());
            }
            return new DefinitionEffectClaim(claimed, current, null, false);
        });
        if (claim.replay()) return claim.receipt();
        DefinitionRecord current = claim.current();
        IntegrationRuntimePorts.ResolvedDefinitionBoundary boundary;
        try {
            boundary = withoutTransaction(() -> resolveFrozenDefinitionBoundary(
                    authority, current));
            if (boundary.endpointPolicyRevision()
                    != command.endpointPolicyRevision()
                    || boundary.secretRevision() != command.secretRevision()) {
                throw conflict("SYS008_DEFINITION_POLICY_REVISION_CHANGED");
            }
        } catch (BaseException rejected) {
            return finishOrRethrowEffectFailure(
                    authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), connectorRef(connectorId), rejected);
        }
        IntegrationRuntimePorts.ValidationEvidence validation;
        try {
            validation = withoutTransaction(() -> evidencePort().validate(
                    new IntegrationRuntimePorts.DefinitionValidationRequest(
                            authority.tenantId(), connectorId,
                            current.definitionVersion(), current.definitionDigest(),
                            current.endpoint(), boundary.allowlist(),
                            boundary.resolvedDestinations(), boundary.secretHandle(),
                            boundary.endpointPolicyRevision(),
                            boundary.secretRevision())));
        } catch (RuntimeException ambiguous) {
            return finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), connectorRef(connectorId),
                    ReceiptStatus.RESULT_UNKNOWN);
        }
        if (!validation.valid() || validation.evidenceDigest() == null) {
            return finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), connectorRef(connectorId),
                    ReceiptStatus.REJECTED);
        }
        return finalizeEffect(authority, claim.reserved(), connectorId,
                current.aggregateRevision(), connectorRef(connectorId), () -> {
                    DefinitionRecord locked = repository.lockDefinition(
                            authority.tenantId(), connectorId);
                    requireUnchanged(current, locked);
                    return transitionDefinition(authority, locked,
                            atDatabaseNow(claim.reserved()),
                            DefinitionState.VALIDATED,
                            null, validation.evidenceDigest());
                });
    }

    public CommandReceipt testConnector(
            Authority authority,
            UUID connectorId,
            ConnectorTestCommand command) {
        requireTrustedAuthority(authority);
        requireAuthor(authority);
        DefinitionEffectClaim claim = inTransaction(authority, () -> {
            ReservedCommand reserved = reserve(authority, "TEST_CONNECTOR",
                    TEST_CONNECTOR, definitionTarget(connectorId, authority), command);
            if (!canRunEffect(reserved)) {
                return DefinitionEffectClaim.replay(reserved.receipt());
            }
            DefinitionRecord current = repository.lockDefinition(
                    authority.tenantId(), connectorId);
            if (current.state() != DefinitionState.VALIDATED) {
                throw conflict(
                        "SYS008_CONNECTION_TEST_REQUIRES_VALIDATED_DEFINITION");
            }
            ReservedCommand claimed = claimEffect(
                    reserved, connectorId, current.aggregateRevision(),
                    connectorRef(connectorId));
            if (!claimed.owner()) {
                return DefinitionEffectClaim.replay(claimed.receipt());
            }
            return new DefinitionEffectClaim(claimed, current, null, false);
        });
        if (claim.replay()) return claim.receipt();
        DefinitionRecord current = claim.current();
        IntegrationRuntimePorts.DefinitionValidationRequest request;
        ResolvedReference sample;
        try {
            var boundary = withoutTransaction(() ->
                    resolveFrozenDefinitionBoundary(authority, current));
            request = new IntegrationRuntimePorts.DefinitionValidationRequest(
                    authority.tenantId(), connectorId, current.definitionVersion(),
                    current.definitionDigest(), current.endpoint(), boundary.allowlist(),
                    boundary.resolvedDestinations(), boundary.secretHandle(),
                    boundary.endpointPolicyRevision(), boundary.secretRevision());
            sample = withoutTransaction(() -> resolveReference(
                    authority, connectorId, connectorId.toString(),
                    ReferenceType.SAMPLE_REF,
                    "sys008.connection-test-sample", command.sampleRef(),
                    command.sampleDigest()));
        } catch (BaseException rejected) {
            return finishOrRethrowEffectFailure(
                    authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), connectorRef(connectorId), rejected);
        }
        IntegrationRuntimePorts.ConnectionTestEvidence tested;
        try {
            tested = withoutTransaction(() -> evidencePort().test(
                    new IntegrationRuntimePorts.ConnectionTestRequest(
                            request, command.testMode(), sample.providerHandle(),
                            command.sampleDigest())));
        } catch (RuntimeException ambiguous) {
            return finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), connectorRef(connectorId),
                    ReceiptStatus.RESULT_UNKNOWN);
        }
        if (!tested.succeeded() || tested.receiptRef() == null
                || tested.receiptDigest() == null) {
            return finishUnchanged(authority, claim.reserved(), connectorId,
                    current.aggregateRevision(), connectorRef(connectorId),
                    ReceiptStatus.REJECTED);
        }
        return finalizeEffect(authority, claim.reserved(), connectorId,
                current.aggregateRevision(), connectorRef(connectorId), () -> {
                    DefinitionRecord locked = repository.lockDefinition(
                            authority.tenantId(), connectorId);
                    requireUnchanged(current, locked);
                    OffsetDateTime now = repository.databaseNow();
                    IntegrationDomainPolicy.requireTransition(
                            locked.state(), DefinitionState.TESTED);
                    DefinitionRecord changed = repository.recordConnectionTest(
                            locked, authority.actorId(), claim.receipt().receiptId(),
                            tested.receiptRef(), tested.receiptDigest(), now);
                    repository.appendControlAudit(claim.reserved().seal(),
                            "CONNECTOR_DEFINITION", connectorId,
                            changed.aggregateRevision(), "DEFINITION_TESTED", changed,
                            now);
                    return repository.finishCommand(claim.reserved().seal(),
                            claim.receipt().receiptId(), ReceiptStatus.SUCCEEDED,
                            connectorId, changed.aggregateRevision(),
                            connectorRef(connectorId), now);
                });
    }

    @Transactional
    public CommandReceipt submitDefinition(
            Authority authority,
            UUID connectorId,
            ApprovalRequestCommand command) {
        bind(authority);
        requireAuthor(authority);
        ReservedCommand reserved = reserve(authority, "SUBMIT_DEFINITION",
                SUBMIT_DEFINITION, definitionTarget(connectorId, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord current = repository.lockDefinition(
                authority.tenantId(), connectorId);
        requireRevision(current.aggregateRevision(), authority);
        if (command.subjectRevision() != current.subjectRevision()
                || !constant(command.payloadDigest(), current.definitionDigest())
                || current.connectionTestReceiptDigest() == null) {
            throw conflict("SYS008_DEFINITION_SUBMISSION_EVIDENCE_STALE");
        }
        return transitionDefinition(authority, current, reserved,
                DefinitionState.PENDING_APPROVAL, null, command.payloadDigest());
    }

    @Transactional
    public CommandReceipt approveDefinition(
            Authority authority,
            UUID connectorId,
            ApprovalDecisionCommand command) {
        bind(authority);
        requireExecutor(authority);
        ReservedCommand reserved = reserve(authority, "APPROVE_DEFINITION",
                APPROVE_DEFINITION, definitionTarget(connectorId, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord current = repository.lockDefinition(
                authority.tenantId(), connectorId);
        requireRevision(current.aggregateRevision(), authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        return transitionDefinition(authority, current, reserved,
                DefinitionState.APPROVED, command.approvalReceiptId(),
                command.decisionDigest());
    }

    @Transactional
    public CommandReceipt activateDefinition(
            Authority authority,
            UUID connectorId,
            ActivationCommand command) {
        bind(authority);
        requireExecutor(authority);
        ReservedCommand reserved = reserve(authority, "ACTIVATE_DEFINITION",
                ACTIVATE_DEFINITION, definitionTarget(connectorId, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord current = repository.lockDefinition(
                authority.tenantId(), connectorId);
        requireRevision(current.aggregateRevision(), authority);
        requireStepUp(authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        if (!command.approvalReceiptId().equals(current.approvalReceiptId())
                || command.effectiveAt().isAfter(reserved.now().plusMinutes(5))) {
            throw conflict("SYS008_DEFINITION_ACTIVATION_EVIDENCE_STALE");
        }
        return transitionDefinition(authority, current, reserved,
                DefinitionState.ACTIVE, null,
                current.definitionDigest());
    }

    @Transactional
    public CommandReceipt createMapping(
            Authority authority,
            UUID connectorId,
            MappingCommand command) {
        bind(authority);
        requireAuthor(authority);
        ReservedCommand reserved = reserve(authority, "CREATE_MAPPING",
                CREATE_MAPPING, connectorId.toString(), command);
        if (!reserved.owner()) return reserved.receipt();
        DefinitionRecord definition = repository.lockDefinition(
                authority.tenantId(), connectorId);
        var resolved = boundaryPort().resolveMapping(
                authority.tenantId(), connectorId, 0,
                authority.authorizationRevision(), authority.purposeCode());
        if (!constant(resolved.mappingRef(), command.mappingRef())
                || !constant(resolved.mappingDigest(), command.mappingDigest())) {
            throw notFound("SYS008_MAPPING_REFERENCE_NOT_AVAILABLE");
        }
        ResolvedReference mappingReference = resolveReference(
                authority, connectorId, connectorId.toString(),
                ReferenceType.MAPPING_REF, "sys008.mapping",
                command.mappingRef(), command.mappingDigest());
        if (!mappingReference.providerHandle().equals(resolved.mappingHandle())) {
            throw notFound("SYS008_MAPPING_REFERENCE_NOT_AVAILABLE");
        }
        MappingRecord mapping = repository.insertMapping(
                definition, authority.actorId(), authority.populationScopeDigest(),
                command, resolved.direction(), resolved.sourceSchemaDigest(),
                resolved.targetSchemaDigest(), reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_MAPPING",
                connectorId, mapping.objectRevision(),
                "MAPPING_CREATED", mapping, reserved.now());
        return repository.finishCommand(reserved.seal(), reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, connectorId, mapping.objectRevision(),
                mappingRef(connectorId, mapping.version()), reserved.now());
    }

    @Transactional
    public CommandReceipt validateMapping(
            Authority authority,
            UUID connectorId,
            long version,
            MappingValidationCommand command) {
        bind(authority);
        requireAuthor(authority);
        ReservedCommand reserved = reserve(authority, "VALIDATE_MAPPING",
                VALIDATE_MAPPING,
                mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        requireRevision(current.objectRevision(), authority);
        if (!constant(current.mappingDigest(), command.mappingDigest())) {
            throw conflict("SYS008_MAPPING_DIGEST_CHANGED");
        }
        var resolved = resolveFrozenMappingBoundary(authority, current);
        if (!constant(current.sourceSchemaDigest(), command.sourceSchemaDigest())
                || !constant(current.targetSchemaDigest(), command.targetSchemaDigest())
                || !constant(resolved.sourceSchemaDigest(), command.sourceSchemaDigest())
                || !constant(resolved.targetSchemaDigest(), command.targetSchemaDigest())) {
            throw conflict("SYS008_MAPPING_SCHEMA_DIGEST_CHANGED");
        }
        return transitionMapping(authority, current, reserved,
                MappingState.VALIDATED, null,
                canonical.digest(command.mappingDigest(),
                        command.sourceSchemaDigest(), command.targetSchemaDigest()));
    }

    public CommandReceipt dryRunMapping(
            Authority authority,
            UUID connectorId,
            long version,
            MappingDryRunCommand command) {
        requireTrustedAuthority(authority);
        requireAuthor(authority);
        if (!"TENANT".equals(authority.populationKind())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_DRY_RUN_REQUIRES_TENANT_POPULATION");
        }
        MappingEffectClaim claim = inTransaction(authority, () -> {
            ReservedCommand reserved = reserve(authority, "DRY_RUN_MAPPING",
                    DRY_RUN_MAPPING,
                    mappingTarget(connectorId, version, authority), command);
            if (!canRunEffect(reserved)) {
                return MappingEffectClaim.replay(reserved.receipt());
            }
            MappingRecord current = repository.lockMapping(
                    authority.tenantId(), connectorId, version);
            requireRevision(current.objectRevision(), authority);
            if (current.state() != MappingState.VALIDATED) {
                throw conflict("SYS008_DRY_RUN_REQUIRES_VALIDATED_MAPPING");
            }
            ReservedCommand claimed = claimEffect(
                    reserved, connectorId, current.objectRevision(),
                    mappingRef(connectorId, version));
            if (!claimed.owner()) {
                return MappingEffectClaim.replay(claimed.receipt());
            }
            return new MappingEffectClaim(claimed, current, null, false);
        });
        if (claim.replay()) return claim.receipt();
        MappingRecord current = claim.current();
        IntegrationRuntimePorts.ResolvedMappingBoundary mappingBoundary;
        ResolvedReference sampleReference;
        try {
            mappingBoundary = withoutTransaction(() ->
                    resolveFrozenMappingBoundary(authority, current));
            sampleReference = withoutTransaction(() -> resolveReference(
                    authority, connectorId, current.mappingId().toString(),
                    ReferenceType.SAMPLE_REF, "sys008.mapping-sample",
                    command.sampleRef(), command.sampleDigest()));
        } catch (BaseException rejected) {
            return finishOrRethrowEffectFailure(
                    authority, claim.reserved(), connectorId,
                    current.objectRevision(), mappingRef(connectorId, version),
                    rejected);
        }
        ConnectorExecutionRequestV1 dryRun = dryRunArtifact(
                authority, current, command, claim.reserved().now());
        IntegrationRuntimePorts.DryRunEvidence result;
        try {
            result = withoutTransaction(() -> evidencePort().dryRun(
                    new IntegrationRuntimePorts.DryRunRequest(
                            dryRun, mappingBoundary.mappingHandle(),
                            sampleReference.providerHandle(), command.maxRows(),
                            command.asOf())));
        } catch (RuntimeException ambiguous) {
            return finishUnchanged(authority, claim.reserved(), connectorId,
                    current.objectRevision(), mappingRef(connectorId, version),
                    ReceiptStatus.RESULT_UNKNOWN);
        }
        if (!result.valid() || result.resultRef() == null
                || result.resultDigest() == null) {
            return finishUnchanged(authority, claim.reserved(), connectorId,
                    current.objectRevision(), mappingRef(connectorId, version),
                    ReceiptStatus.REJECTED);
        }
        return finalizeEffect(authority, claim.reserved(), connectorId,
                current.objectRevision(), mappingRef(connectorId, version), () -> {
                    MappingRecord locked = repository.lockMapping(
                            authority.tenantId(), connectorId, version);
                    requireUnchanged(current, locked);
                    OffsetDateTime now = repository.databaseNow();
                    MappingRecord changed = repository.recordDryRun(
                            locked, authority.actorId(), claim.receipt().receiptId(),
                            result.resultRef(), result.resultDigest(), now);
                    repository.appendControlAudit(claim.reserved().seal(),
                            "CONNECTOR_MAPPING", connectorId,
                            changed.objectRevision(), "MAPPING_DRY_RUN_RECORDED",
                            changed, now);
                    return repository.finishCommand(claim.reserved().seal(),
                            claim.receipt().receiptId(), ReceiptStatus.SUCCEEDED,
                            connectorId, changed.objectRevision(),
                            mappingRef(connectorId, version), now);
                });
    }

    @Transactional
    public CommandReceipt submitMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ApprovalRequestCommand command) {
        bind(authority);
        requireAuthor(authority);
        ReservedCommand reserved = reserve(authority, "SUBMIT_MAPPING",
                SUBMIT_MAPPING,
                mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        requireRevision(current.objectRevision(), authority);
        if (command.subjectRevision() != current.subjectRevision()
                || !constant(command.payloadDigest(), current.mappingDigest())
                || current.dryRunResultDigest() == null) {
            throw conflict("SYS008_MAPPING_SUBMISSION_EVIDENCE_STALE");
        }
        return transitionMapping(authority, current, reserved,
                MappingState.PENDING_APPROVAL, null, command.payloadDigest());
    }

    @Transactional
    public CommandReceipt approveMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ApprovalDecisionCommand command) {
        bind(authority);
        requireExecutor(authority);
        ReservedCommand reserved = reserve(authority, "APPROVE_MAPPING",
                APPROVE_MAPPING,
                mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        requireRevision(current.objectRevision(), authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        return transitionMapping(authority, current, reserved,
                MappingState.APPROVED, command.approvalReceiptId(),
                command.decisionDigest());
    }

    @Transactional
    public CommandReceipt activateMapping(
            Authority authority,
            UUID connectorId,
            long version,
            ActivationCommand command) {
        bind(authority);
        requireExecutor(authority);
        requireStepUp(authority);
        ReservedCommand reserved = reserve(authority, "ACTIVATE_MAPPING",
                ACTIVATE_MAPPING,
                mappingTarget(connectorId, version, authority), command);
        if (!reserved.owner()) return reserved.receipt();
        MappingRecord current = repository.lockMapping(
                authority.tenantId(), connectorId, version);
        requireRevision(current.objectRevision(), authority);
        IntegrationDomainPolicy.requireMakerChecker(
                current.authorActorId(), authority.actorId(),
                current.authorScopeDigest(), authority.populationScopeDigest());
        if (!command.approvalReceiptId().equals(current.approvalReceiptId())) {
            throw conflict("SYS008_MAPPING_ACTIVATION_EVIDENCE_STALE");
        }
        if (command.effectiveAt().isBefore(current.effectiveFrom())
                || current.effectiveTo() != null
                && !command.effectiveAt().isBefore(current.effectiveTo())
                || command.effectiveAt().isAfter(reserved.now().plusMinutes(5))) {
            throw conflict("SYS008_MAPPING_EFFECTIVE_WINDOW_INVALID");
        }
        return transitionMapping(authority, current, reserved,
                MappingState.ACTIVE, null,
                current.mappingDigest());
    }

    @Transactional
    public CommandReceipt createExecution(
            Authority authority,
            UUID connectorId,
            ExecutionCommand command) {
        bind(authority);
        requireExecutor(authority);
        ReservedCommand reserved = reserve(authority,
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
        resolveFrozenDefinitionBoundary(authority, definition);
        resolveFrozenMappingBoundary(authority, mapping);
        ResolvedReference payloadReference = resolveReference(
                authority, connectorId, executionPublicId.toString(),
                ReferenceType.PAYLOAD_REF, "sys008.execution-payload",
                command.payloadRef(), command.payloadDigest());
        var sourceManifest = boundaryPort().resolvePayload(
                authority.tenantId(), connectorId, executionPublicId,
                command.payloadRef(), command.payloadDigest(),
                authority.authorizationRevision(), authority.purposeCode());
        if (!payloadReference.providerHandle().equals(
                sourceManifest.payloadHandle())) {
            throw notFound("SYS008_PAYLOAD_MANIFEST_NOT_AVAILABLE");
        }
        ConnectorExecutionRequestV1 artifact = executionArtifact(
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
                execution.aggregateRevision(), executionRef(execution.executionId()),
                reserved.now());
    }

    @Transactional(readOnly = true)
    public ExecutionResponse execution(
            Authority authority, UUID executionId) {
        bind(authority);
        requireExecutor(authority);
        return repository.executionResponseAuthorized(
                authority.tenantId(), executionId,
                authority.populationScopeDigest(), authority.populationKind(),
                authority.legalEntityPublicId());
    }

    private CommandReceipt transitionDefinition(
            Authority authority,
            DefinitionRecord current,
            ReservedCommand reserved,
            DefinitionState target,
            UUID evidenceReceiptId,
            String evidenceDigest) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        DefinitionRecord changed = repository.transitionDefinition(
                current, target, authority.actorId(),
                evidenceReceiptId == null
                        ? reserved.receipt().receiptId() : evidenceReceiptId,
                evidenceDigest, authority.stepUpEvidenceDigest(), reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_DEFINITION",
                current.connectorId(), changed.aggregateRevision(),
                "DEFINITION_" + target.name(), changed, reserved.now());
        return repository.finishCommand(reserved.seal(),
                reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, current.connectorId(),
                changed.aggregateRevision(), connectorRef(current.connectorId()),
                reserved.now());
    }

    private CommandReceipt transitionMapping(
            Authority authority,
            MappingRecord current,
            ReservedCommand reserved,
            MappingState target,
            UUID evidenceReceiptId,
            String evidenceDigest) {
        IntegrationDomainPolicy.requireTransition(current.state(), target);
        MappingRecord changed = repository.transitionMapping(
                current, target, authority.actorId(),
                evidenceReceiptId == null
                        ? reserved.receipt().receiptId() : evidenceReceiptId,
                evidenceDigest, authority.stepUpEvidenceDigest(), reserved.now());
        repository.appendControlAudit(reserved.seal(), "CONNECTOR_MAPPING",
                current.connectorId(), changed.objectRevision(),
                "MAPPING_" + target.name(), changed, reserved.now());
        return repository.finishCommand(reserved.seal(),
                reserved.receipt().receiptId(),
                ReceiptStatus.SUCCEEDED, current.connectorId(),
                changed.objectRevision(),
                mappingRef(current.connectorId(), current.version()), reserved.now());
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

    private ReservedCommand reserve(
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

    private ConnectorExecutionRequestV1 dryRunArtifact(
            Authority authority,
            MappingRecord mapping,
            MappingDryRunCommand command,
            OffsetDateTime now) {
        var context = Sys008TypedArtifactContract.issueOwnerExecutionContext(
                UUID.randomUUID(), mapping.connectorId(), mapping.mappingId(),
                mapping.version(), now.toInstant(),
                "actor:" + authority.actorId(), authority.idempotencyKey(),
                authority.correlationId());
        var population = Sys008TypedArtifactContract.issueCurrentPopulationContext(
                mapping.connectorId(), java.util.Set.of(),
                authority.authorizationRevision());
        var source = Sys008TypedArtifactContract.issueTenantSourceEnvelope(
                population, Sys008TypedArtifactContract.freezeOwnerSource(
                        command.sampleRef(), command.sampleDigest()));
        ConnectorMappingVersionV1 ownerMapping = mappingArtifact(mapping);
        var pinned = "INBOUND".equals(mapping.direction())
                ? Sys008TypedArtifactContract.pinInboundOwnerMapping(ownerMapping)
                : Sys008TypedArtifactContract.pinOutboundOwnerMapping(ownerMapping);
        return Sys008TypedArtifactContract.projectDryRun(context, pinned, source);
    }

    private ConnectorExecutionRequestV1 executionArtifact(
            Authority authority,
            UUID executionId,
            MappingRecord mapping,
            ExecutionCommand command,
            OffsetDateTime now) {
        var context = Sys008TypedArtifactContract.issueOwnerExecutionContext(
                executionId, mapping.connectorId(), mapping.mappingId(),
                mapping.version(), now.toInstant(),
                "actor:" + authority.actorId(), authority.idempotencyKey(),
                authority.correlationId());
        var population = Sys008TypedArtifactContract.issueCurrentPopulationContext(
                mapping.connectorId(), authority.legalEntityPublicId() == null
                        ? java.util.Set.of()
                        : java.util.Set.of(authority.legalEntityPublicId()),
                authority.authorizationRevision());
        var frozenSource = Sys008TypedArtifactContract.freezeOwnerSource(
                command.payloadRef(), command.payloadDigest());
        var source = "TENANT".equals(authority.populationKind())
                ? Sys008TypedArtifactContract.issueTenantSourceEnvelope(
                        population, frozenSource)
                : Sys008TypedArtifactContract.issueLegalEntitySourceEnvelope(
                        population, authority.legalEntityPublicId(), frozenSource);
        return IntegrationExecutionArtifacts.project(
                context, population, source, command.operation(),
                mapping.direction());
    }

    private static ConnectorMappingVersionV1 mappingArtifact(
            MappingRecord mapping) {
        return Sys008TypedArtifactContract.requireInteroperable(
                new ConnectorMappingVersionV1(
                        mapping.connectorId(), mapping.mappingId(),
                        mapping.version(),
                        Sys008TypedArtifactContract.projectMappingState(
                                mapping.state().name()),
                        "OwnerSource.v" + mapping.sourceSchemaVersion(),
                        "ProviderTarget.v" + mapping.targetSchemaVersion(),
                        mapping.mappingDigest(), mapping.dryRunResultDigest(),
                        "actor:" + mapping.authorActorId(),
                        mapping.approvedByActorId() == null ? null
                                : "actor:" + mapping.approvedByActorId(),
                        mapping.approvalReceiptId(),
                        mapping.effectiveFrom().toLocalDate(),
                        mapping.effectiveTo() == null ? null
                                : mapping.effectiveTo().toLocalDate(),
                        mapping.compatibilityMode().name()));
    }

    private ResolvedReference resolveReference(
            Authority authority,
            UUID connectorId,
            String subjectRef,
            ReferenceType type,
            String namespace,
            String reference,
            String digest) {
        ResolutionRequest request = new ResolutionRequest(
                reference, authority.tenantId(), connectorId, subjectRef,
                authority.purposeCode(), type, namespace, digest,
                authority.authorizationRevision(),
                UnpinnedReferenceConstraint.INSTANCE, true);
        return referencePort().resolveAuthorized(request)
                .orElseThrow(() -> notFound(
                        "SYS008_OPAQUE_REFERENCE_NOT_AVAILABLE"));
    }

    private boolean canRunEffect(ReservedCommand reserved) {
        if (reserved.owner()
                || reserved.receipt().status() == ReceiptStatus.ACCEPTED) {
            return true;
        }
        return reserved.receipt().status() == ReceiptStatus.RUNNING
                && !reserved.now().isBefore(
                        reserved.receipt().createdAt()
                                .plus(EFFECT_CLAIM_RECOVERY_AFTER));
    }

    private ReservedCommand claimEffect(
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

    private CommandReceipt finalizeEffect(
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

    private IntegrationRuntimePorts.ResolvedDefinitionBoundary
            resolveFrozenDefinitionBoundary(
                    Authority authority, DefinitionRecord current) {
        IntegrationRuntimePorts.ResolvedDefinitionBoundary resolved =
                boundaryPort().resolveDefinition(
                        authority.tenantId(), current.connectorId(),
                        current.definitionVersion(),
                        authority.authorizationRevision(),
                        authority.purposeCode());
        URI canonicalStored = canonicalEndpoint(
                current.endpoint(), resolved.allowlist(),
                resolved.resolvedDestinations());
        URI canonicalResolved = canonicalEndpoint(
                resolved.endpoint(), resolved.allowlist(),
                resolved.resolvedDestinations());
        if (!canonicalStored.equals(current.endpoint())
                || !canonicalResolved.equals(current.endpoint())
                || !constant(current.egressPolicyKey(),
                        resolved.egressPolicyKey())
                || !constant(current.secretRef(), resolved.secretRef())
                || current.endpointPolicyRevision()
                        != resolved.endpointPolicyRevision()
                || current.secretRevision() != resolved.secretRevision()) {
            throw conflict("SYS008_DEFINITION_BOUNDARY_CHANGED");
        }
        String secretDigest = canonical.digest(
                current.connectorId().toString(), current.endpoint().toString(),
                current.egressPolicyKey(), current.secretRef());
        ResolvedReference secret = resolveReference(
                authority, current.connectorId(),
                current.connectorId().toString(), ReferenceType.SECRET_REF,
                "sys008.connector-secret", current.secretRef(), secretDigest);
        if (!secret.providerHandle().equals(resolved.secretHandle())) {
            throw conflict("SYS008_DEFINITION_BOUNDARY_CHANGED");
        }
        return resolved;
    }

    private IntegrationRuntimePorts.ResolvedMappingBoundary
            resolveFrozenMappingBoundary(
                    Authority authority, MappingRecord current) {
        IntegrationRuntimePorts.ResolvedMappingBoundary resolved =
                boundaryPort().resolveMapping(
                        authority.tenantId(), current.connectorId(),
                        current.version(), authority.authorizationRevision(),
                        authority.purposeCode());
        if (!constant(current.mappingRef(), resolved.mappingRef())
                || !constant(current.mappingDigest(), resolved.mappingDigest())
                || !constant(current.direction(), resolved.direction())
                || !constant(current.sourceSchemaDigest(),
                        resolved.sourceSchemaDigest())
                || !constant(current.targetSchemaDigest(),
                        resolved.targetSchemaDigest())) {
            throw conflict("SYS008_MAPPING_BOUNDARY_CHANGED");
        }
        ResolvedReference mapping = resolveReference(
                authority, current.connectorId(),
                current.mappingId().toString(), ReferenceType.MAPPING_REF,
                "sys008.mapping", current.mappingRef(),
                current.mappingDigest());
        if (!mapping.providerHandle().equals(resolved.mappingHandle())) {
            throw conflict("SYS008_MAPPING_BOUNDARY_CHANGED");
        }
        return resolved;
    }

    private CommandReceipt finishUnchanged(
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

    private CommandReceipt finishOrRethrowEffectFailure(
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

    private ReservedCommand atDatabaseNow(ReservedCommand reserved) {
        return new ReservedCommand(reserved.seal(), reserved.receipt(),
                repository.databaseNow(), true);
    }

    private static void requireUnchanged(
            DefinitionRecord expected, DefinitionRecord current) {
        if (!expected.definitionVersionId().equals(current.definitionVersionId())
                || expected.aggregateRevision() != current.aggregateRevision()
                || expected.objectRevision() != current.objectRevision()
                || expected.state() != current.state()
                || !constant(expected.definitionDigest(),
                        current.definitionDigest())) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_DEFINITION_CHANGED_DURING_EXTERNAL_EFFECT");
        }
    }

    private static void requireUnchanged(
            MappingRecord expected, MappingRecord current) {
        if (!expected.mappingId().equals(current.mappingId())
                || expected.objectRevision() != current.objectRevision()
                || expected.state() != current.state()
                || !constant(expected.mappingDigest(), current.mappingDigest())) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_MAPPING_CHANGED_DURING_EXTERNAL_EFFECT");
        }
    }

    private <T> T inTransaction(Authority authority, Supplier<T> work) {
        T result = transactions.execute(ignored -> {
            repository.bindTenantPrincipal(
                    authority.tenantId(), authority.subjectPrincipalPublicId());
            return work.get();
        });
        return Objects.requireNonNull(result,
                "SYS008 transaction returned no result");
    }

    private <T> T withoutTransaction(Supplier<T> effect) {
        T result = effects.execute(status -> effect.get());
        return Objects.requireNonNull(result,
                "SYS008 validation-effect phase returned no result");
    }

    private void bind(Authority authority) {
        requireTrustedAuthority(authority);
        repository.bindTenantPrincipal(
                authority.tenantId(), authority.subjectPrincipalPublicId());
    }

    private static void requireTrustedAuthority(Authority authority) {
        if (authority == null || !IntegrationOwnerAuthorityFilter.REQUIRED_PURPOSE
                .equals(authority.purposeCode())) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_OWNER_AUTHORITY_REQUIRED");
        }
    }

    private static void requireAuthor(Authority authority) {
        if (!authority.author()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_AUTHOR_AUTHORITY_REQUIRED");
        }
    }

    private static void requireExecutor(Authority authority) {
        if (!authority.executor()) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "SYS008_EXECUTOR_AUTHORITY_REQUIRED");
        }
    }

    private static void requireStepUp(Authority authority) {
        if (authority.stepUpEvidenceDigest() == null) {
            throw new BaseException(ErrorCode.STEP_UP_REQUIRED,
                    "SYS008_TARGET_BOUND_STEP_UP_REQUIRED");
        }
    }

    private static void requireRevision(long current, Authority authority) {
        if (authority.expectedObjectRevision() == null
                || authority.expectedObjectRevision() != current) {
            throw new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT,
                    "SYS008_EXPECTED_REVISION_CONFLICT");
        }
    }

    private BoundaryResolutionPort boundaryPort() {
        BoundaryResolutionPort port = boundaries.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_BOUNDARY_RESOLUTION_UNAVAILABLE");
        }
        return port;
    }

    private DefinitionEvidencePort evidencePort() {
        DefinitionEvidencePort port = evidence.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_VALIDATION_PROVIDER_UNAVAILABLE");
        }
        return port;
    }

    private Sys008OpaqueReferenceOwnerPort referencePort() {
        Sys008OpaqueReferenceOwnerPort port = references.getIfAvailable();
        if (port == null) {
            throw new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE,
                    "SYS008_REFERENCE_OWNER_UNAVAILABLE");
        }
        return port;
    }

    static String actionForRoute(String route) {
        return switch (route) {
            case "route.hcm.configuration.connector-definition-create.action" -> CREATE_CONNECTOR;
            case "route.hcm.configuration.connector-test.action" -> TEST_CONNECTOR;
            case "route.hcm.configuration.connector-mapping-dry-run.action" -> DRY_RUN_MAPPING;
            case "route.hcm.configuration.connector-execution-create.action" -> CREATE_EXECUTION;
            case "route.hcm.configuration.connector-definition-validate.action" -> VALIDATE_DEFINITION;
            case "route.hcm.configuration.connector-definition-submit.action" -> SUBMIT_DEFINITION;
            case "route.hcm.configuration.connector-definition-approve.action" -> APPROVE_DEFINITION;
            case "route.hcm.configuration.connector-definition-activate.action" -> ACTIVATE_DEFINITION;
            case "route.hcm.configuration.connector-mapping-version-create.action" -> CREATE_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-validate.action" -> VALIDATE_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-submit.action" -> SUBMIT_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-approve.action" -> APPROVE_MAPPING;
            case "route.hcm.configuration.connector-mapping-version-activate.action" -> ACTIVATE_MAPPING;
            case "route.hcm.configuration.connector-execution-reconcile.action" -> RECONCILE_EXECUTION;
            case "route.hcm.configuration.connector-execution-retry.action" -> RETRY_EXECUTION;
            case "route.hcm.configuration.connector-execution-cancel.action" -> CANCEL_EXECUTION;
            default -> "";
        };
    }

    private static long activeMappingVersion(DefinitionRecord definition) {
        if (definition.activeMappingVersion() < 1) {
            throw conflict("SYS008_ACTIVE_MAPPING_NOT_AVAILABLE");
        }
        return definition.activeMappingVersion();
    }

    private static UUID requireUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException invalid) {
            throw invalid("SYS008_CURSOR_INVALID");
        }
    }

    private static boolean constant(String left, String right) {
        return left != null && right != null && java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String definitionTarget(UUID id, Authority authority) {
        return id + ":revision:" + String.valueOf(
                authority.expectedObjectRevision());
    }

    private static String mappingTarget(
            UUID id, long version, Authority authority) {
        return id + ":" + version + ":revision:"
                + String.valueOf(authority.expectedObjectRevision());
    }

    private static String connectorRef(UUID id) {
        return "/v1/admin/hris/connectors/" + id;
    }

    private static String mappingRef(UUID id, long version) {
        return connectorRef(id) + "/mappings/" + version;
    }

    private static String executionRef(UUID id) {
        return "/v1/admin/hris/connector-executions/" + id;
    }

    private static URI canonicalEndpoint(
            URI endpoint,
            Sys008EgressBoundary.Allowlist allowlist,
            List<java.net.InetAddress> destinations) {
        if (endpoint == null || endpoint.isOpaque() || !endpoint.isAbsolute()
                || endpoint.getUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null
                || endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw invalid("SYS008_EGRESS_ENDPOINT_INVALID");
        }
        try {
            URI canonicalEndpoint = new URI(
                    endpoint.getScheme().toLowerCase(java.util.Locale.ROOT),
                    null,
                    endpoint.getHost().toLowerCase(java.util.Locale.ROOT),
                    endpoint.getPort(),
                    endpoint.getRawPath() == null
                            || endpoint.getRawPath().isBlank()
                            ? null : endpoint.getRawPath(),
                    null,
                    null).normalize();
            Sys008EgressBoundary.requireAllowed(
                    canonicalEndpoint, allowlist, destinations);
            return canonicalEndpoint;
        } catch (java.net.URISyntaxException | IllegalArgumentException invalid) {
            throw IntegrationService.invalid("SYS008_EGRESS_ENDPOINT_INVALID");
        }
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.RESOURCE_NOT_AVAILABLE, message);
    }

    private record ReservedCommand(
            CommandSeal seal,
            CommandReceipt receipt,
            OffsetDateTime now,
            boolean owner) {}

    private record DefinitionEffectClaim(
            ReservedCommand reserved,
            DefinitionRecord current,
            CommandReceipt replayReceipt,
            boolean replay) {

        static DefinitionEffectClaim replay(CommandReceipt receipt) {
            return new DefinitionEffectClaim(null, null, receipt, true);
        }

        CommandReceipt receipt() {
            return replay ? replayReceipt : reserved.receipt();
        }
    }

    private record MappingEffectClaim(
            ReservedCommand reserved,
            MappingRecord current,
            CommandReceipt replayReceipt,
            boolean replay) {

        static MappingEffectClaim replay(CommandReceipt receipt) {
            return new MappingEffectClaim(null, null, receipt, true);
        }

        CommandReceipt receipt() {
            return replay ? replayReceipt : reserved.receipt();
        }
    }
}
