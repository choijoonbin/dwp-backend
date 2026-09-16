package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryManifestContract;
import com.dwp.services.platform.widgetregistry.internal.security.WidgetRegistryManifestContract.ValidatedManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WidgetRegistryDefinitionService {
    private static final Set<String> REQUIRED_EVIDENCE = Set.of(
            "MANIFEST", "SECURITY", "PRIVACY", "A11Y", "PERFORMANCE", "LOCALIZATION");

    private final WidgetDefinitionRepository definitions;
    private final WidgetDefinitionVersionRepository versions;
    private final WidgetRendererBindingRepository bindings;
    private final WidgetEvidenceRepository evidence;
    private final WidgetRegistryResponseMapper mapper;
    private final WidgetRegistryCommandReceiptService receipts;
    private final WidgetRegistryLedger ledger;
    private final ObjectMapper objectMapper;
    private final WidgetRegistryMutationGuard mutationGuard;
    private final WidgetRegistryImpactService impacts;
    private final WidgetRegistryOwnerScopeGuard ownerScope;

    public WidgetRegistryDefinitionService(
            WidgetDefinitionRepository definitions,
            WidgetDefinitionVersionRepository versions,
            WidgetRendererBindingRepository bindings,
            WidgetEvidenceRepository evidence,
            WidgetRegistryResponseMapper mapper,
            WidgetRegistryCommandReceiptService receipts,
            WidgetRegistryLedger ledger,
            ObjectMapper objectMapper,
            WidgetRegistryMutationGuard mutationGuard,
            WidgetRegistryImpactService impacts,
            WidgetRegistryOwnerScopeGuard ownerScope) {
        this.definitions = definitions;
        this.versions = versions;
        this.bindings = bindings;
        this.evidence = evidence;
        this.mapper = mapper;
        this.receipts = receipts;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        this.mutationGuard = mutationGuard;
        this.impacts = impacts;
        this.ownerScope = ownerScope;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.DefinitionPage list(int page, int size, String state) {
        var pageable = PageRequest.of(
                Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by("definitionKey").ascending());
        var providerOwners = ownerScope.currentProviderOwners();
        var result = providerOwners.isPresent()
                ? state == null
                        ? definitions.findByOwnerProductKeyIn(providerOwners.get(), pageable)
                        : definitions.findByDefinitionStateAndOwnerProductKeyIn(
                                state, providerOwners.get(), pageable)
                : state == null
                        ? definitions.findAll(pageable)
                        : definitions.findByDefinitionState(state, pageable);
        String revision = Long.toString(ledger.state().getRegistryRevision());
        return new WidgetRegistryDtos.DefinitionPage(
                result.stream().map(mapper::definition).toList(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.hasNext(), revision);
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.DefinitionResponse get(UUID definitionId) {
        return mapper.definition(requireDefinition(definitionId));
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse retirementImpact(
            UUID definitionId, UUID replacementDefinitionId) {
        requireDefinition(definitionId);
        if (replacementDefinitionId != null) requireDefinition(replacementDefinitionId);
        return impacts.preview(definitionId, null, "RETIRE",
                replacementDefinitionId == null ? null : replacementDefinitionId.toString());
    }

    @Transactional
    public WidgetRegistryDtos.DefinitionResponse retire(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            WidgetRegistryDtos.DefinitionRetireRequest request) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, "RETIRE_DEFINITION", definitionId.toString(),
                fingerprint, WidgetRegistryDtos.DefinitionResponse.class);
        if (replay != null) return replay;
        WidgetDefinition definition = definitions.lockById(definitionId).orElseThrow(notFound());
        ownerScope.requireOwner(definition.getOwnerProductKey());
        mutationGuard.requireAllowed(null, definition.getOwnerProductKey(), definitionId, null);
        requireVersion(definition, request.expectedVersion());
        WidgetRegistryDtos.ImpactResponse impact = impacts.calculate(
                null, definitionId, null, "RETIRE",
                request.replacementDefinitionId() == null
                        ? null : request.replacementDefinitionId().toString());
        if (!request.impactRevision().equals(impact.impactRevision())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Impact changed; preview again.");
        }
        if (impact.activeChannelCount() > 0) {
            throw invalidState("Definition with an active release channel cannot be retired.");
        }
        if ((impact.tenantPolicyReferenceCount() > 0 || impact.instanceReferenceCount() > 0)
                && request.replacementDefinitionId() == null) {
            throw invalidState("Referenced definition requires an explicit replacement.");
        }
        if (request.replacementDefinitionId() != null) {
            WidgetDefinition replacement = requireDefinition(request.replacementDefinitionId());
            if (replacement.getDefinitionId().equals(definitionId)
                    || !"ACTIVE".equals(replacement.getDefinitionState())) {
                throw invalidState("Replacement definition is not eligible.");
            }
        }
        var before = mapper.definition(definition);
        definition.setDefinitionState("RETIRED");
        definition.setUpdatedBy(actorId);
        try {
            definitions.saveAndFlush(definition);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw conflict();
        }
        var response = mapper.definition(definition);
        ledger.append(null, "DEFINITION", definitionId.toString(), "WIDGET_DEFINITION_RETIRED",
                commandId, actorId, correlationId, before, response, List.of(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "RETIRE_DEFINITION", definitionId.toString(), fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.DefinitionResponse create(
            Long actorId,
            UUID commandId,
            String correlationId,
            WidgetRegistryDtos.DefinitionCreateRequest request) {
        String fingerprint = receipts.fingerprint(request);
        WidgetRegistryDtos.DefinitionResponse replay = receipts.replay(
                actorId, commandId, "CREATE_DEFINITION", request.definitionKey(), fingerprint,
                WidgetRegistryDtos.DefinitionResponse.class);
        if (replay != null) return replay;
        ownerScope.requireOwner(request.ownerProductKey());
        mutationGuard.requireAllowed(null, request.ownerProductKey(), null, null);
        if (request.expectedVersion() != 0 || definitions.findByDefinitionKey(request.definitionKey()).isPresent()) {
            throw conflict();
        }
        WidgetDefinition value = WidgetDefinition.builder()
                .definitionId(UUID.randomUUID())
                .definitionKey(request.definitionKey())
                .legacyWidgetKey(request.legacyWidgetKey())
                .ownerProductKey(request.ownerProductKey())
                .ownerTeamKey(request.ownerTeamKey())
                .riskTier(request.riskTier())
                .dataClassification(request.dataClassification())
                .definitionState("ACTIVE")
                .build();
        value.setCreatedBy(actorId);
        value.setUpdatedBy(actorId);
        try {
            definitions.saveAndFlush(value);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
        var response = mapper.definition(value);
        ledger.append(null, "DEFINITION", value.getDefinitionId().toString(),
                "WIDGET_DEFINITION_CREATED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "CREATE_DEFINITION", request.definitionKey(), fingerprint, response);
        return response;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.VersionPage versions(UUID definitionId, int page, int size) {
        requireDefinition(definitionId);
        List<WidgetRegistryDtos.VersionResponse> all = versions
                .findByDefinitionIdOrderByCreatedAtDesc(definitionId).stream()
                .map(mapper::version).toList();
        int from = Math.min(all.size(), Math.max(0, page) * Math.min(100, Math.max(1, size)));
        int to = Math.min(all.size(), from + Math.min(100, Math.max(1, size)));
        return new WidgetRegistryDtos.VersionPage(
                all.subList(from, to), Math.max(0, page), Math.min(100, Math.max(1, size)),
                all.size(), to < all.size(), Long.toString(ledger.state().getRegistryRevision()));
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.VersionResponse getVersion(UUID versionId) {
        return mapper.version(requireVersion(versionId));
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse createVersion(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            WidgetRegistryDtos.VersionCreateRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = definitionId + ":" + request.semanticVersion();
        var replay = receipts.replay(actorId, commandId, "CREATE_VERSION", target, fingerprint,
                WidgetRegistryDtos.VersionResponse.class);
        if (replay != null) return replay;
        WidgetDefinition definition = definitions.lockById(definitionId)
                .orElseThrow(notFound());
        ownerScope.requireOwner(definition.getOwnerProductKey());
        mutationGuard.requireAllowed(null, definition.getOwnerProductKey(), definitionId, null);
        requireVersion(definition, request.expectedVersion());
        if (!"ACTIVE".equals(definition.getDefinitionState())) throw invalidState("Definition is retired.");
        ValidatedManifest manifest = validateManifest(definition, request.manifest());
        requirePredecessor(definitionId, request.predecessorVersionId());
        WidgetDefinitionVersion value = WidgetDefinitionVersion.builder()
                .versionId(UUID.randomUUID())
                .definitionId(definitionId)
                .semanticVersion(request.semanticVersion())
                .manifest(request.manifest())
                .manifestHash(manifest.manifestHash())
                .rendererKey(manifest.rendererKey())
                .workflowState("DRAFT")
                .releaseState("UNPUBLISHED")
                .safetyState("CLEAR")
                .immutable(false)
                .predecessorVersionId(request.predecessorVersionId())
                .attestation(objectMapper.createObjectNode())
                .certificationStatus("NOT_RUN")
                .build();
        value.setCreatedBy(actorId);
        value.setUpdatedBy(actorId);
        definition.setUpdatedBy(actorId);
        try {
            versions.saveAndFlush(value);
            definitions.saveAndFlush(definition);
        } catch (DataIntegrityViolationException exception) {
            throw conflict();
        }
        var response = mapper.version(value);
        ledger.append(null, "VERSION", value.getVersionId().toString(),
                "WIDGET_VERSION_DRAFTED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "CREATE_VERSION", target, fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse updateVersion(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID versionId,
            WidgetRegistryDtos.VersionUpdateRequest request) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, "UPDATE_VERSION", versionId.toString(), fingerprint,
                WidgetRegistryDtos.VersionResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = lockVersion(versionId, request.expectedVersion());
        guard(value);
        if (value.isImmutable() || !"DRAFT".equals(value.getWorkflowState())) {
            throw invalidState("Only mutable draft content can be edited.");
        }
        WidgetDefinition definition = requireDefinition(value.getDefinitionId());
        ValidatedManifest manifest = validateManifest(definition, request.manifest());
        requirePredecessor(value.getDefinitionId(), request.predecessorVersionId());
        var before = mapper.version(value);
        value.setManifest(request.manifest());
        value.setManifestHash(manifest.manifestHash());
        value.setRendererKey(manifest.rendererKey());
        value.setPredecessorVersionId(request.predecessorVersionId());
        value.setValidationRunId(null);
        value.setCertificationStatus("NOT_RUN");
        value.setUpdatedBy(actorId);
        saveVersion(value);
        var response = mapper.version(value);
        ledger.append(null, "VERSION", versionId.toString(), "WIDGET_VERSION_UPDATED",
                commandId, actorId, correlationId, before, response, List.of(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "UPDATE_VERSION", versionId.toString(), fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.ValidationResponse validate(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID versionId,
            WidgetRegistryDtos.ValidateRequest request) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, "VALIDATE", versionId.toString(), fingerprint,
                WidgetRegistryDtos.ValidationResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = lockVersion(versionId, request.expectedVersion());
        guard(value);
        if (!"DRAFT".equals(value.getWorkflowState()) || !request.manifestHash().equals(value.getManifestHash())) {
            throw invalidState("Validation requires the current draft manifest hash.");
        }
        WidgetDefinition definition = requireDefinition(value.getDefinitionId());
        validateManifest(definition, value.getManifest());
        UUID runId = UUID.randomUUID();
        value.setValidationRunId(runId);
        value.setWorkflowState("VALIDATED");
        value.setUpdatedBy(actorId);
        saveVersion(value);
        String bindingRevision = bindings.findByRendererKeyAndBindingState(value.getRendererKey(), "ACTIVE")
                .orElseThrow(() -> invalidState("Renderer binding is disabled."))
                .getBindingRevision();
        var response = new WidgetRegistryDtos.ValidationResponse(
                runId, versionId, value.getManifestHash(), "PASS", bindingRevision,
                List.of(), OffsetDateTime.now(ZoneOffset.UTC));
        ledger.append(null, "VERSION", versionId.toString(), "WIDGET_VERSION_VALIDATED",
                commandId, actorId, correlationId, null, response, List.of(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "VALIDATE", versionId.toString(), fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse submit(
            Long actorId, UUID commandId, String correlationId, UUID versionId,
            WidgetRegistryDtos.WidgetVersionTransitionRequest request) {
        return transition(actorId, commandId, correlationId, versionId, request,
                "SUBMIT", "VALIDATED", "SUBMITTED", "WIDGET_VERSION_SUBMITTED");
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse rework(
            Long actorId, UUID commandId, String correlationId, UUID versionId,
            WidgetRegistryDtos.WidgetVersionTransitionRequest request) {
        return transition(actorId, commandId, correlationId, versionId, request,
                "REWORK", "REJECTED", "DRAFT", "WIDGET_VERSION_REWORKED");
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse decide(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID versionId,
            WidgetRegistryDtos.ReviewDecisionRequest request) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, "DECIDE", versionId.toString(), fingerprint,
                WidgetRegistryDtos.VersionResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = lockVersion(versionId, request.expectedVersion());
        guard(value);
        if (!"SUBMITTED".equals(value.getWorkflowState())
                || !request.validationRunId().equals(value.getValidationRunId())) {
            throw invalidState("Review must bind the current submitted validation run.");
        }
        if (actorId.equals(value.getCreatedBy())) {
            throw new BaseException(ErrorCode.SOD_CONFLICT, "Author cannot review the same widget version.");
        }
        if ("APPROVE".equals(request.decision())) {
            requireEvidence(value, request.evidenceIds());
            value.setWorkflowState("APPROVED");
            value.setCertificationStatus("PASS");
            value.setApprovedBy(actorId);
        } else {
            value.setWorkflowState("REJECTED");
            value.setCertificationStatus("FAIL");
            value.setApprovedBy(null);
        }
        value.setUpdatedBy(actorId);
        saveVersion(value);
        var response = mapper.version(value);
        ledger.append(null, "VERSION", versionId.toString(), "WIDGET_VERSION_" + request.decision() + "D",
                commandId, actorId, correlationId, null, response, request.evidenceIds(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "DECIDE", versionId.toString(), fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.EvidenceResponse recordEvidence(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID versionId,
            WidgetRegistryDtos.EvidenceCreateRequest request) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, "RECORD_EVIDENCE", versionId.toString(), fingerprint,
                WidgetRegistryDtos.EvidenceResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = lockVersion(versionId, request.expectedVersion());
        guard(value);
        if (!request.manifestHash().equals(value.getManifestHash())
                || "REVOKED".equals(value.getSafetyState())) {
            throw invalidState("Evidence must bind the current non-revoked manifest.");
        }
        WidgetEvidence created = evidence.save(WidgetEvidence.builder()
                .evidenceId(UUID.randomUUID())
                .versionId(versionId)
                .evidenceType(request.evidenceType())
                .evidenceStatus(request.decision())
                .manifestHash(request.manifestHash())
                .evidenceRef(request.evidenceRef())
                .evidenceSha256(request.evidenceSha256())
                .expiresAt(request.expiresAt())
                .decisionRevision(value.getVersion() + 1)
                .reviewedBy(actorId)
                .build());
        if ("FAIL".equals(request.decision())) {
            value.setCertificationStatus("FAIL");
        }
        value.setUpdatedBy(actorId);
        saveVersion(value);
        var response = mapper.evidence(created);
        ledger.append(null, "EVIDENCE", created.getEvidenceId().toString(),
                "WIDGET_EVIDENCE_RECORDED", commandId, actorId, correlationId,
                null, response, List.of(created.getEvidenceId()),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "RECORD_EVIDENCE", versionId.toString(), fingerprint, response);
        return response;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.EvidencePage evidence(UUID versionId, int page, int size) {
        requireVersion(versionId);
        List<WidgetRegistryDtos.EvidenceResponse> all = evidence
                .findByVersionIdOrderByCreatedAtDescEvidenceIdDesc(versionId).stream()
                .map(mapper::evidence).toList();
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(0, page);
        int from = Math.min(all.size(), safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        return new WidgetRegistryDtos.EvidencePage(
                all.subList(from, to), safePage, safeSize, all.size(), to < all.size(),
                Long.toString(ledger.state().getRegistryRevision()));
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.EvidenceResponse evidence(UUID versionId, UUID evidenceId) {
        requireVersion(versionId);
        return evidence.findByEvidenceIdAndVersionId(evidenceId, versionId)
                .map(mapper::evidence).orElseThrow(notFound());
    }

    WidgetDefinition requireDefinition(UUID id) {
        WidgetDefinition value = definitions.findById(id).orElseThrow(notFound());
        ownerScope.requireOwner(value.getOwnerProductKey());
        return value;
    }

    WidgetDefinitionVersion requireVersion(UUID id) {
        WidgetDefinitionVersion value = versions.findById(id).orElseThrow(notFound());
        ownerScope.requireVersion(value);
        return value;
    }

    WidgetDefinitionVersion lockVersion(UUID id, Long expectedVersion) {
        WidgetDefinitionVersion value = versions.lockById(id).orElseThrow(notFound());
        ownerScope.requireVersion(value);
        requireVersion(value, expectedVersion);
        return value;
    }

    void saveVersion(WidgetDefinitionVersion value) {
        try {
            versions.saveAndFlush(value);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    void requireEvidence(WidgetDefinitionVersion value, List<UUID> evidenceIds) {
        List<WidgetEvidence> selected = evidence.findAllById(evidenceIds);
        Set<String> passing = selected.stream()
                .filter(item -> item.getVersionId().equals(value.getVersionId()))
                .filter(item -> item.getManifestHash().equals(value.getManifestHash()))
                .filter(item -> "PASS".equals(item.getEvidenceStatus()))
                .filter(item -> item.getExpiresAt() == null
                        || item.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))
                .map(WidgetEvidence::getEvidenceType).collect(java.util.stream.Collectors.toSet());
        if (selected.size() != evidenceIds.size()
                || !passing.containsAll(REQUIRED_EVIDENCE)
                || !hasCurrentCertificationEvidence(value)) {
            throw invalidState("All six current certification evidence types must pass.");
        }
    }

    boolean hasCurrentCertificationEvidence(WidgetDefinitionVersion value) {
        Map<String, WidgetEvidence> latest = new LinkedHashMap<>();
        evidence.findByVersionIdOrderByCreatedAtDescEvidenceIdDesc(value.getVersionId()).forEach(item ->
                latest.putIfAbsent(item.getEvidenceType(), item));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return REQUIRED_EVIDENCE.stream().allMatch(type -> {
            WidgetEvidence item = latest.get(type);
            return item != null
                    && item.getManifestHash().equals(value.getManifestHash())
                    && List.of("PASS", "WAIVED").contains(item.getEvidenceStatus())
                    && (item.getExpiresAt() == null || item.getExpiresAt().isAfter(now));
        });
    }

    boolean hasActiveRendererBinding(WidgetDefinitionVersion value) {
        return bindings.findByRendererKeyAndBindingState(value.getRendererKey(), "ACTIVE")
                .filter(binding -> "NATIVE".equals(binding.getKind()))
                .isPresent();
    }

    private WidgetRegistryDtos.VersionResponse transition(
            Long actorId, UUID commandId, String correlationId, UUID versionId,
            WidgetRegistryDtos.WidgetVersionTransitionRequest request, String operation,
            String sourceState, String targetState, String eventType) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, operation, versionId.toString(), fingerprint,
                WidgetRegistryDtos.VersionResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = lockVersion(versionId, request.expectedVersion());
        guard(value);
        if (value.isImmutable() || !sourceState.equals(value.getWorkflowState())) {
            throw invalidState("Lifecycle transition is not allowed from the current state.");
        }
        value.setWorkflowState(targetState);
        if ("DRAFT".equals(targetState)) {
            value.setValidationRunId(null);
            value.setCertificationStatus("NOT_RUN");
            value.setApprovedBy(null);
        }
        value.setUpdatedBy(actorId);
        saveVersion(value);
        var response = mapper.version(value);
        ledger.append(null, "VERSION", versionId.toString(), eventType, commandId, actorId,
                correlationId, null, response, List.of(), WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, operation, versionId.toString(), fingerprint, response);
        return response;
    }

    private ValidatedManifest validateManifest(WidgetDefinition definition, com.fasterxml.jackson.databind.JsonNode node) {
        final ValidatedManifest manifest;
        try {
            manifest = WidgetRegistryManifestContract.validate(node);
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, exception.getMessage());
        }
        if (!definition.getDefinitionKey().equals(manifest.definitionKey())
                || !definition.getOwnerProductKey().equals(manifest.ownerProductKey())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Manifest owner or definition does not match its parent.");
        }
        WidgetRendererBinding binding = bindings
                .findByRendererKeyAndBindingState(manifest.rendererKey(), "ACTIVE")
                .orElseThrow(() -> invalidState("Renderer is not in the native allowlist."));
        if (!"NATIVE".equals(binding.getKind())
                || !manifest.ownerProductKey().equals(binding.getOwnerProductKey())
                || !manifest.sourceAppResourceKey().equals(binding.getSourceAppResourceKey())
                || manifest.minimumHostApiVersion() < binding.getMinimumHostApiVersion()
                || manifest.minimumHostApiVersion() > binding.getMaximumHostApiVersion()) {
            throw invalidState("Manifest renderer binding is incompatible with the native host.");
        }
        return manifest;
    }

    private void guard(WidgetDefinitionVersion value) {
        WidgetDefinition definition = requireDefinition(value.getDefinitionId());
        mutationGuard.requireAllowed(
                null, definition.getOwnerProductKey(), definition.getDefinitionId(), value.getVersionId());
    }

    private void requirePredecessor(UUID definitionId, UUID predecessorId) {
        if (predecessorId == null) return;
        WidgetDefinitionVersion predecessor = requireVersion(predecessorId);
        if (!definitionId.equals(predecessor.getDefinitionId())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Predecessor belongs to another definition.");
        }
    }

    private static void requireVersion(Object entity, Long expected) {
        Long current = entity instanceof WidgetDefinition definition
                ? definition.getVersion() : ((WidgetDefinitionVersion) entity).getVersion();
        if (expected == null || !expected.equals(current == null ? 0L : current)) throw conflict();
    }

    private static BaseException conflict() {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
    }

    private static BaseException invalidState(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }

    private static java.util.function.Supplier<BaseException> notFound() {
        return () -> new BaseException(ErrorCode.NOT_FOUND);
    }
}
