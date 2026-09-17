package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantWidgetPolicyService {
    private final TenantWidgetPolicyRevisionRepository revisions;
    private final TenantWidgetPolicyHeadRepository heads;
    private final WidgetRegistryDefinitionService definitions;
    private final WidgetReleaseChannelRepository channels;
    private final WidgetRegistryImpactService impacts;
    private final WidgetRegistryResponseMapper mapper;
    private final WidgetRegistryCommandReceiptService receipts;
    private final WidgetRegistryLedger ledger;
    private final ObjectMapper objectMapper;
    private final WidgetRegistryMutationGuard mutationGuard;

    public TenantWidgetPolicyService(
            TenantWidgetPolicyRevisionRepository revisions,
            TenantWidgetPolicyHeadRepository heads,
            WidgetRegistryDefinitionService definitions,
            WidgetReleaseChannelRepository channels,
            WidgetRegistryImpactService impacts,
            WidgetRegistryResponseMapper mapper,
            WidgetRegistryCommandReceiptService receipts,
            WidgetRegistryLedger ledger,
            ObjectMapper objectMapper,
            WidgetRegistryMutationGuard mutationGuard) {
        this.revisions = revisions;
        this.heads = heads;
        this.definitions = definitions;
        this.channels = channels;
        this.impacts = impacts;
        this.mapper = mapper;
        this.receipts = receipts;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
        this.mutationGuard = mutationGuard;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.TenantPolicyResponse get(Long tenantId, UUID definitionId) {
        definitions.requireDefinition(definitionId);
        TenantWidgetPolicyHead head = heads.findByTenantIdAndDefinitionId(tenantId, definitionId)
                .orElse(null);
        if (head == null || head.getCurrentRevisionId() == null) {
            return new WidgetRegistryDtos.TenantPolicyResponse(
                    definitionId, null, null, head == null ? 0 : value(head.getVersion()),
                    List.of("CREATE_REVISION"));
        }
        TenantWidgetPolicyRevision current = revisions.findByPolicyRevisionIdAndTenantId(
                head.getCurrentRevisionId(), tenantId).orElseThrow(notFound());
        return response(head, current);
    }

    @Transactional
    public WidgetRegistryDtos.TenantPolicyRevisionResponse createRevision(
            Long tenantId,
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            WidgetRegistryDtos.TenantPolicyRevisionRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = tenantId + ":" + definitionId;
        var replay = receipts.replay(actorId, commandId, "CREATE_TENANT_POLICY_REVISION", target,
                fingerprint, WidgetRegistryDtos.TenantPolicyRevisionResponse.class);
        if (replay != null) return replay;
        guard(tenantId, definitionId, request.versionId());
        validateSelector(definitionId, request);
        TenantWidgetPolicyHead head = lockOrCreateHead(tenantId, definitionId, actorId);
        requireVersion(head.getVersion(), request.expectedVersion());
        long next = revisions.findByTenantIdAndDefinitionIdOrderByRevisionNumberDesc(tenantId, definitionId)
                .stream().findFirst().map(TenantWidgetPolicyRevision::getRevisionNumber).orElse(0L) + 1;
        TenantWidgetPolicyRevision draft = fromRequest(
                tenantId, definitionId, next, head.getCurrentRevisionId(), request, actorId);
        apply(draft, request);
        saveRevision(draft);
        var response = mapper.policyRevision(draft);
        ledger.append(tenantId, "TENANT_POLICY", draft.getPolicyRevisionId().toString(),
                "TENANT_WIDGET_POLICY_DRAFTED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.POLICY);
        receipts.store(actorId, commandId, "CREATE_TENANT_POLICY_REVISION", target, fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.TenantPolicyRevisionResponse updateRevision(
            Long tenantId,
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            UUID revisionId,
            WidgetRegistryDtos.TenantPolicyRevisionRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = tenantId + ":" + revisionId;
        var replay = receipts.replay(actorId, commandId, "UPDATE_TENANT_POLICY_REVISION", target,
                fingerprint, WidgetRegistryDtos.TenantPolicyRevisionResponse.class);
        if (replay != null) return replay;
        TenantWidgetPolicyRevision draft = revisions.lock(revisionId, tenantId).orElseThrow(notFound());
        if (!definitionId.equals(draft.getDefinitionId()) || !"DRAFT".equals(draft.getPolicyState())) {
            throw invalid("Only this tenant's draft policy can be edited.");
        }
        requireVersion(draft.getVersion(), request.expectedVersion());
        guard(tenantId, definitionId, request.versionId());
        validateSelector(definitionId, request);
        apply(draft, request);
        saveRevision(draft);
        var response = mapper.policyRevision(draft);
        ledger.append(tenantId, "TENANT_POLICY", revisionId.toString(),
                "TENANT_WIDGET_POLICY_DRAFT_UPDATED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.POLICY);
        receipts.store(actorId, commandId, "UPDATE_TENANT_POLICY_REVISION", target, fingerprint, response);
        return response;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse impact(
            Long tenantId, UUID definitionId, UUID revisionId) {
        TenantWidgetPolicyRevision revision = revisions.findByPolicyRevisionIdAndTenantId(revisionId, tenantId)
                .orElseThrow(notFound());
        if (!definitionId.equals(revision.getDefinitionId())) throw notFound().get();
        return impacts.previewTenant(
                tenantId, definitionId, resolveVersion(revision), "TENANT_POLICY_PUBLISH");
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse revokeImpact(Long tenantId, UUID definitionId) {
        TenantWidgetPolicyHead head = heads.findByTenantIdAndDefinitionId(tenantId, definitionId)
                .orElseThrow(notFound());
        TenantWidgetPolicyRevision current = requireCurrent(tenantId, head);
        return impacts.previewTenant(
                tenantId, definitionId, resolveVersion(current), "TENANT_POLICY_REVOKE");
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse rollbackImpact(
            Long tenantId, UUID definitionId, UUID restoreRevisionId) {
        TenantWidgetPolicyHead head = heads.findByTenantIdAndDefinitionId(tenantId, definitionId)
                .orElseThrow(notFound());
        TenantWidgetPolicyRevision current = requireCurrent(tenantId, head);
        TenantWidgetPolicyRevision restore = revisions.findByPolicyRevisionIdAndTenantId(
                restoreRevisionId, tenantId).orElseThrow(notFound());
        if (!definitionId.equals(restore.getDefinitionId())
                || !List.of("PUBLISHED", "SUPERSEDED").contains(restore.getPolicyState())) {
            throw invalid("Rollback target is not a prior published policy revision.");
        }
        return impacts.previewTenant(
                tenantId, definitionId, resolveVersion(current), "TENANT_POLICY_ROLLBACK",
                restoreRevisionId.toString());
    }

    @Transactional
    public WidgetRegistryDtos.TenantPolicyResponse publish(
            Long tenantId,
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            UUID revisionId,
            WidgetRegistryDtos.TenantPolicyPublishRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = tenantId + ":" + revisionId;
        var replay = receipts.replay(actorId, commandId, "PUBLISH_TENANT_POLICY", target,
                fingerprint, WidgetRegistryDtos.TenantPolicyResponse.class);
        if (replay != null) return replay;
        TenantWidgetPolicyHead head = heads.lock(tenantId, definitionId).orElseThrow(notFound());
        guard(tenantId, definitionId, null);
        requireVersion(head.getVersion(), request.expectedVersion());
        TenantWidgetPolicyRevision draft = revisions.lock(revisionId, tenantId).orElseThrow(notFound());
        if (!definitionId.equals(draft.getDefinitionId()) || !"DRAFT".equals(draft.getPolicyState())) {
            throw invalid("Only the selected draft policy can be published.");
        }
        WidgetRegistryDtos.ImpactResponse impact = impacts.calculate(
                tenantId, definitionId, resolveVersion(draft), "TENANT_POLICY_PUBLISH");
        requireImpact(request.expectedImpactRevision(), impact);
        UUID previousRevisionId = head.getCurrentRevisionId();
        if (previousRevisionId != null && !previousRevisionId.equals(revisionId)) {
            TenantWidgetPolicyRevision previous = revisions.lock(previousRevisionId, tenantId)
                    .orElseThrow(notFound());
            if ("PUBLISHED".equals(previous.getPolicyState())) {
                previous.setPolicyState("SUPERSEDED");
                saveRevision(previous);
            }
        }
        draft.setPolicyState("PUBLISHED");
        draft.setImpactRevision(impact.impactRevision());
        saveRevision(draft);
        head.setCurrentRevisionId(revisionId);
        head.setUpdatedBy(actorId);
        saveHead(head);
        var response = response(head, draft);
        ledger.append(tenantId, "TENANT_POLICY", revisionId.toString(),
                "TENANT_WIDGET_POLICY_PUBLISHED", commandId, actorId, correlationId,
                null, response, List.of(), WidgetRegistryLedger.RevisionAxis.POLICY);
        receipts.store(actorId, commandId, "PUBLISH_TENANT_POLICY", target, fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.TenantPolicyResponse revoke(
            Long tenantId,
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            WidgetRegistryDtos.TenantPolicyRevokeRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = tenantId + ":" + definitionId;
        var replay = receipts.replay(actorId, commandId, "REVOKE_TENANT_POLICY", target,
                fingerprint, WidgetRegistryDtos.TenantPolicyResponse.class);
        if (replay != null) return replay;
        TenantWidgetPolicyHead head = heads.lock(tenantId, definitionId).orElseThrow(notFound());
        requireVersion(head.getVersion(), request.expectedVersion());
        TenantWidgetPolicyRevision current = requireCurrent(tenantId, head);
        requireImpact(request.expectedImpactRevision(), impacts.calculate(
                tenantId, definitionId, resolveVersion(current), "TENANT_POLICY_REVOKE"));
        TenantWidgetPolicyRevision tombstone = copyRevision(
                current, nextRevision(tenantId, definitionId), current.getPolicyRevisionId(),
                false, "REVOKED", request.reasonCode(), request.reasonText(), actorId);
        saveRevision(tombstone);
        head.setCurrentRevisionId(tombstone.getPolicyRevisionId());
        head.setUpdatedBy(actorId);
        saveHead(head);
        var response = response(head, tombstone);
        ledger.append(tenantId, "TENANT_POLICY", tombstone.getPolicyRevisionId().toString(),
                "TENANT_WIDGET_POLICY_REVOKED", commandId, actorId, correlationId,
                mapper.policyRevision(current), response, List.of(),
                WidgetRegistryLedger.RevisionAxis.POLICY);
        receipts.store(actorId, commandId, "REVOKE_TENANT_POLICY", target, fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.TenantPolicyResponse rollback(
            Long tenantId,
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            WidgetRegistryDtos.TenantPolicyRollbackRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = tenantId + ":" + definitionId;
        var replay = receipts.replay(actorId, commandId, "ROLLBACK_TENANT_POLICY", target,
                fingerprint, WidgetRegistryDtos.TenantPolicyResponse.class);
        if (replay != null) return replay;
        TenantWidgetPolicyHead head = heads.lock(tenantId, definitionId).orElseThrow(notFound());
        guard(tenantId, definitionId, null);
        requireVersion(head.getVersion(), request.expectedVersion());
        TenantWidgetPolicyRevision current = requireCurrent(tenantId, head);
        TenantWidgetPolicyRevision restore = revisions.findByPolicyRevisionIdAndTenantId(
                request.restoreRevisionId(), tenantId).orElseThrow(notFound());
        if (!definitionId.equals(restore.getDefinitionId())
                || !List.of("PUBLISHED", "SUPERSEDED").contains(restore.getPolicyState())) {
            throw invalid("Rollback target is not a prior published policy revision.");
        }
        requireImpact(request.expectedImpactRevision(), impacts.calculate(
                tenantId, definitionId, resolveVersion(current), "TENANT_POLICY_ROLLBACK",
                request.restoreRevisionId().toString()));
        TenantWidgetPolicyRevision rolled = copyRevision(
                restore, nextRevision(tenantId, definitionId), current.getPolicyRevisionId(),
                restore.isEnabled(), "PUBLISHED", request.reasonCode(), request.reasonText(), actorId);
        saveRevision(rolled);
        head.setCurrentRevisionId(rolled.getPolicyRevisionId());
        head.setUpdatedBy(actorId);
        saveHead(head);
        var response = response(head, rolled);
        ledger.append(tenantId, "TENANT_POLICY", rolled.getPolicyRevisionId().toString(),
                "TENANT_WIDGET_POLICY_ROLLED_BACK", commandId, actorId, correlationId,
                mapper.policyRevision(current), response, List.of(),
                WidgetRegistryLedger.RevisionAxis.POLICY);
        receipts.store(actorId, commandId, "ROLLBACK_TENANT_POLICY", target, fingerprint, response);
        return response;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.TenantPolicyRevisionPage history(
            Long tenantId, UUID definitionId, int page, int size) {
        List<WidgetRegistryDtos.TenantPolicyRevisionResponse> all = revisions
                .findByTenantIdAndDefinitionIdOrderByRevisionNumberDesc(tenantId, definitionId)
                .stream().map(mapper::policyRevision).toList();
        int safeSize = Math.min(100, Math.max(1, size));
        int safePage = Math.max(0, page);
        int from = Math.min(all.size(), safePage * safeSize);
        int to = Math.min(all.size(), from + safeSize);
        return new WidgetRegistryDtos.TenantPolicyRevisionPage(
                all.subList(from, to), safePage, safeSize, all.size(), to < all.size(),
                Long.toString(ledger.state().getPolicyRevision()));
    }

    private TenantWidgetPolicyHead lockOrCreateHead(Long tenantId, UUID definitionId, Long actorId) {
        TenantWidgetPolicyHead existing = heads.lock(tenantId, definitionId).orElse(null);
        if (existing != null) return existing;
        try {
            return heads.saveAndFlush(TenantWidgetPolicyHead.builder()
                    .policyHeadId(UUID.randomUUID()).tenantId(tenantId).definitionId(definitionId)
                    .updatedBy(actorId).build());
        } catch (DataIntegrityViolationException exception) {
            return heads.lock(tenantId, definitionId).orElseThrow(() -> conflict());
        }
    }

    private void guard(Long tenantId, UUID definitionId, UUID versionId) {
        WidgetDefinition definition = definitions.requireDefinition(definitionId);
        mutationGuard.requireAllowed(
                tenantId, definition.getOwnerProductKey(), definitionId, versionId);
    }

    private TenantWidgetPolicyRevision fromRequest(
            Long tenantId, UUID definitionId, long number, UUID predecessor,
            WidgetRegistryDtos.TenantPolicyRevisionRequest request, Long actorId) {
        return TenantWidgetPolicyRevision.builder()
                .policyRevisionId(UUID.randomUUID()).tenantId(tenantId).definitionId(definitionId)
                .revisionNumber(number).policyState("DRAFT").predecessorRevisionId(predecessor)
                .createdBy(actorId).build();
    }

    private void apply(
            TenantWidgetPolicyRevision value,
            WidgetRegistryDtos.TenantPolicyRevisionRequest request) {
        value.setEnabled(request.enabled());
        value.setSelectorType(request.selector());
        value.setChannel(request.channel());
        value.setVersionId(request.versionId());
        value.setSupportedSurfaceKeys(objectMapper.valueToTree(request.supportedSurfaceKeys()));
        value.setAudienceSelector(request.audienceSelector());
        value.setRequiredWidget(request.required());
        value.setLockedConfiguration(request.lockedConfiguration());
        value.setSharingPolicy(request.sharingPolicy());
        value.setReasonCode(request.reasonCode());
        value.setReasonText(request.reasonText());
    }

    private TenantWidgetPolicyRevision copyRevision(
            TenantWidgetPolicyRevision source, long number, UUID predecessor, boolean enabled,
            String state, String reasonCode, String reasonText, Long actorId) {
        return TenantWidgetPolicyRevision.builder()
                .policyRevisionId(UUID.randomUUID()).tenantId(source.getTenantId())
                .definitionId(source.getDefinitionId()).revisionNumber(number).policyState(state)
                .enabled(enabled).selectorType(source.getSelectorType()).channel(source.getChannel())
                .versionId(source.getVersionId()).supportedSurfaceKeys(source.getSupportedSurfaceKeys())
                .audienceSelector(source.getAudienceSelector()).requiredWidget(source.isRequiredWidget())
                .lockedConfiguration(source.getLockedConfiguration()).sharingPolicy(source.getSharingPolicy())
                .predecessorRevisionId(predecessor).reasonCode(reasonCode).reasonText(reasonText)
                .createdBy(actorId).build();
    }

    private WidgetRegistryDtos.TenantPolicyResponse response(
            TenantWidgetPolicyHead head, TenantWidgetPolicyRevision current) {
        return new WidgetRegistryDtos.TenantPolicyResponse(
                head.getDefinitionId(), head.getCurrentRevisionId(), mapper.policyRevision(current),
                value(head.getVersion()), List.of("CREATE_REVISION", "REVOKE", "ROLLBACK"));
    }

    private void validateSelector(
            UUID definitionId, WidgetRegistryDtos.TenantPolicyRevisionRequest request) {
        if ("CHANNEL".equals(request.selector())) {
            if (request.channel() == null || request.versionId() != null
                    || channels.findByDefinitionIdAndChannel(definitionId, request.channel()).isEmpty()) {
                throw invalid("Channel selector must name an existing release channel only.");
            }
        } else if (request.channel() != null || request.versionId() == null) {
            throw invalid("Pinned selector must name one version and no channel.");
        } else {
            WidgetDefinitionVersion selected = definitions.requireVersion(request.versionId());
            if (!definitionId.equals(selected.getDefinitionId())) {
                throw invalid("Pinned version belongs to another definition.");
            }
        }
        if (!request.audienceSelector().isObject() || !request.lockedConfiguration().isObject()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Policy JSON fields must be objects.");
        }
        WidgetAudienceSelectorContract.requireValid(request.audienceSelector());
    }

    private UUID resolveVersion(TenantWidgetPolicyRevision policy) {
        if ("PINNED".equals(policy.getSelectorType())) return policy.getVersionId();
        return channels.findByDefinitionIdAndChannel(policy.getDefinitionId(), policy.getChannel())
                .map(WidgetReleaseChannel::getCurrentVersionId).orElse(null);
    }

    private TenantWidgetPolicyRevision requireCurrent(Long tenantId, TenantWidgetPolicyHead head) {
        return revisions.findByPolicyRevisionIdAndTenantId(head.getCurrentRevisionId(), tenantId)
                .orElseThrow(notFound());
    }

    private long nextRevision(Long tenantId, UUID definitionId) {
        return revisions.findByTenantIdAndDefinitionIdOrderByRevisionNumberDesc(tenantId, definitionId)
                .stream().findFirst().map(TenantWidgetPolicyRevision::getRevisionNumber).orElse(0L) + 1;
    }

    private void saveRevision(TenantWidgetPolicyRevision revision) {
        try {
            revisions.saveAndFlush(revision);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    private void saveHead(TenantWidgetPolicyHead head) {
        try {
            heads.saveAndFlush(head);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    private static void requireImpact(String expected, WidgetRegistryDtos.ImpactResponse actual) {
        if (!Objects.equals(expected, actual.impactRevision())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Impact changed; preview again.");
        }
    }

    private static void requireVersion(Long current, Long expected) {
        if (!Objects.equals(value(current), expected)) throw conflict();
    }

    private static long value(Long value) { return value == null ? 0 : value; }
    private static BaseException conflict() { return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT); }
    private static BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_STATE, message); }
    private static java.util.function.Supplier<BaseException> notFound() {
        return () -> new BaseException(ErrorCode.NOT_FOUND);
    }
}
