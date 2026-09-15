package com.dwp.services.platform.widgetregistry;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WidgetRegistryReleaseService {
    private final WidgetRegistryDefinitionService definitions;
    private final WidgetReleaseChannelRepository channels;
    private final WidgetRegistryImpactService impacts;
    private final WidgetRegistryResponseMapper mapper;
    private final WidgetRegistryCommandReceiptService receipts;
    private final WidgetRegistryLedger ledger;
    private final WidgetRegistryMutationGuard mutationGuard;

    public WidgetRegistryReleaseService(
            WidgetRegistryDefinitionService definitions,
            WidgetReleaseChannelRepository channels,
            WidgetRegistryImpactService impacts,
            WidgetRegistryResponseMapper mapper,
            WidgetRegistryCommandReceiptService receipts,
            WidgetRegistryLedger ledger,
            WidgetRegistryMutationGuard mutationGuard) {
        this.definitions = definitions;
        this.channels = channels;
        this.impacts = impacts;
        this.mapper = mapper;
        this.receipts = receipts;
        this.ledger = ledger;
        this.mutationGuard = mutationGuard;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse impact(
            UUID versionId, String operation) {
        WidgetDefinitionVersion value = definitions.requireVersion(versionId);
        return impacts.preview(value.getDefinitionId(), versionId, operation);
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ImpactResponse channelImpact(
            UUID definitionId, String channelKey, String operation, UUID targetVersionId) {
        WidgetReleaseChannel channel = channels.findByDefinitionIdAndChannel(definitionId, channelKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        WidgetDefinitionVersion target = definitions.requireVersion(targetVersionId);
        if (!definitionId.equals(target.getDefinitionId())) {
            throw invalid("Channel impact target belongs to another definition.");
        }
        UUID impactedVersion = "ROLLBACK".equals(operation)
                ? channel.getCurrentVersionId() : targetVersionId;
        return impacts.preview(definitionId, impactedVersion, operation, targetVersionId.toString());
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse publish(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID versionId,
            WidgetRegistryDtos.PublishRequest request) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, "PUBLISH", versionId.toString(), fingerprint,
                WidgetRegistryDtos.VersionResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = definitions.lockVersion(versionId, request.expectedVersion());
        guard(value);
        if (!"APPROVED".equals(value.getWorkflowState())
                || !"UNPUBLISHED".equals(value.getReleaseState())
                || !"CLEAR".equals(value.getSafetyState())
                || !"PASS".equals(value.getCertificationStatus())
                || !definitions.hasCurrentCertificationEvidence(value)
                || !definitions.hasActiveRendererBinding(value)
                || !request.manifestHash().equals(value.getManifestHash())
                || !request.validationRunId().equals(value.getValidationRunId())) {
            throw invalid("Only the current approved, certified and safe manifest can be published.");
        }
        WidgetDefinition definition = definitions.requireDefinition(value.getDefinitionId());
        if (actorId.equals(value.getCreatedBy())
                || ("HIGH".equals(definition.getRiskTier()) && actorId.equals(value.getApprovedBy()))) {
            throw new BaseException(
                    ErrorCode.SOD_CONFLICT,
                    "The author and high-risk approver cannot publish this widget version.");
        }
        definitions.requireEvidence(value, request.evidenceIds());
        requireImpact(request.expectedImpactRevision(), impacts.calculate(
                null, value.getDefinitionId(), versionId, "PUBLISH"));
        WidgetReleaseChannel channel = channels.lock(value.getDefinitionId(), request.channel())
                .orElseGet(() -> WidgetReleaseChannel.builder()
                        .releaseChannelId(UUID.randomUUID())
                        .definitionId(value.getDefinitionId())
                        .channel(request.channel())
                        .build());
        UUID previous = channel.getCurrentVersionId();
        value.setReleaseState("PUBLISHED");
        value.setImmutable(true);
        value.setUpdatedBy(actorId);
        definitions.saveVersion(value);
        channel.setPreviousVersionId(previous);
        channel.setCurrentVersionId(versionId);
        channel.setUpdatedBy(actorId);
        saveChannel(channel);
        var response = mapper.version(value);
        ledger.append(null, "VERSION", versionId.toString(), "WIDGET_VERSION_PUBLISHED",
                commandId, actorId, correlationId, null, response, request.evidenceIds(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "PUBLISH", versionId.toString(), fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse block(
            Long actorId, UUID commandId, String correlationId, UUID versionId,
            WidgetRegistryDtos.SafetyTransitionRequest request) {
        return safetyTransition(actorId, commandId, correlationId, versionId, request,
                "BLOCK", "BLOCKED", "CLEAR", "WIDGET_VERSION_BLOCKED");
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse quarantine(
            Long actorId, UUID commandId, String correlationId, UUID versionId,
            WidgetRegistryDtos.SafetyTransitionRequest request) {
        return safetyTransition(actorId, commandId, correlationId, versionId, request,
                "QUARANTINE", "BLOCKED", "QUARANTINED", "WIDGET_VERSION_QUARANTINED");
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse revoke(
            Long actorId, UUID commandId, String correlationId, UUID versionId,
            WidgetRegistryDtos.SafetyTransitionRequest request) {
        return safetyTransition(actorId, commandId, correlationId, versionId, request,
                "REVOKE", "BLOCKED", "REVOKED", "WIDGET_VERSION_REVOKED");
    }

    @Transactional
    public WidgetRegistryDtos.VersionResponse deprecate(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID versionId,
            WidgetRegistryDtos.DeprecateRequest request) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, "DEPRECATE", versionId.toString(), fingerprint,
                WidgetRegistryDtos.VersionResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = definitions.lockVersion(versionId, request.expectedVersion());
        guard(value);
        if (!"PUBLISHED".equals(value.getReleaseState())
                || !"CLEAR".equals(value.getSafetyState())
                || !request.deprecationEndsAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))
                || request.deprecationEndsAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusDays(365))) {
            throw invalid("Deprecation is not allowed for this version or expiry.");
        }
        WidgetDefinitionVersion replacement = definitions.requireVersion(request.replacementVersionId());
        if (replacement.getVersionId().equals(versionId)) {
            throw invalid("Deprecation replacement must be another eligible version.");
        }
        requireReleaseCandidate(value.getDefinitionId(), replacement);
        value.setReleaseState("DEPRECATED");
        value.setReplacementVersionId(replacement.getVersionId());
        value.setUpdatedBy(actorId);
        definitions.saveVersion(value);
        var response = mapper.version(value);
        ledger.append(null, "VERSION", versionId.toString(), "WIDGET_VERSION_DEPRECATED",
                commandId, actorId, correlationId, null, response, List.of(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "DEPRECATE", versionId.toString(), fingerprint, response);
        return response;
    }

    @Transactional(readOnly = true)
    public WidgetRegistryDtos.ReleaseChannelResponse channel(UUID definitionId, String channel) {
        definitions.requireDefinition(definitionId);
        return channels.findByDefinitionIdAndChannel(definitionId, channel)
                .map(mapper::channel).orElseGet(() -> new WidgetRegistryDtos.ReleaseChannelResponse(
                        definitionId, channel, null, null, 0, null, List.of("PROMOTE")));
    }

    @Transactional
    public WidgetRegistryDtos.ReleaseChannelResponse promote(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            String channelKey,
            WidgetRegistryDtos.ChannelTransitionRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = definitionId + ":" + channelKey;
        var replay = receipts.replay(actorId, commandId, "PROMOTE", target, fingerprint,
                WidgetRegistryDtos.ReleaseChannelResponse.class);
        if (replay != null) return replay;
        WidgetReleaseChannel channel = channels.lock(definitionId, channelKey)
                .orElseGet(() -> WidgetReleaseChannel.builder()
                        .releaseChannelId(UUID.randomUUID()).definitionId(definitionId)
                        .channel(channelKey).build());
        guard(definitions.requireVersion(request.versionId()));
        requireVersion(channel.getVersion(), request.expectedVersion());
        WidgetDefinitionVersion candidate = definitions.requireVersion(request.versionId());
        requireReleaseCandidate(definitionId, candidate);
        if (!request.manifestHash().equals(candidate.getManifestHash())
                || !request.validationRunId().equals(candidate.getValidationRunId())) {
            throw invalid("Channel target does not match the approved manifest.");
        }
        requireImpact(request.expectedImpactRevision(), impacts.calculate(
                null, definitionId, request.versionId(), "PROMOTE", request.versionId().toString()));
        channel.setPreviousVersionId(channel.getCurrentVersionId());
        channel.setCurrentVersionId(request.versionId());
        channel.setUpdatedBy(actorId);
        saveChannel(channel);
        var response = mapper.channel(channel);
        ledger.append(null, "RELEASE_CHANNEL", target, "WIDGET_CHANNEL_PROMOTED",
                commandId, actorId, correlationId, null, response, List.of(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "PROMOTE", target, fingerprint, response);
        return response;
    }

    @Transactional
    public WidgetRegistryDtos.ReleaseChannelResponse rollback(
            Long actorId,
            UUID commandId,
            String correlationId,
            UUID definitionId,
            String channelKey,
            WidgetRegistryDtos.ChannelRollbackRequest request) {
        String fingerprint = receipts.fingerprint(request);
        String target = definitionId + ":" + channelKey;
        var replay = receipts.replay(actorId, commandId, "ROLLBACK", target, fingerprint,
                WidgetRegistryDtos.ReleaseChannelResponse.class);
        if (replay != null) return replay;
        WidgetReleaseChannel channel = channels.lock(definitionId, channelKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        guard(definitions.requireVersion(request.restoreVersionId()));
        requireVersion(channel.getVersion(), request.expectedVersion());
        if (!request.expectedCurrentVersionId().equals(channel.getCurrentVersionId())) throw conflict();
        WidgetDefinitionVersion restore = definitions.requireVersion(request.restoreVersionId());
        requireReleaseCandidate(definitionId, restore);
        requireImpact(request.expectedImpactRevision(), impacts.calculate(
                null, definitionId, channel.getCurrentVersionId(), "ROLLBACK",
                request.restoreVersionId().toString()));
        UUID before = channel.getCurrentVersionId();
        channel.setPreviousVersionId(before);
        channel.setCurrentVersionId(restore.getVersionId());
        channel.setUpdatedBy(actorId);
        saveChannel(channel);
        var response = mapper.channel(channel);
        ledger.append(null, "RELEASE_CHANNEL", target, "WIDGET_CHANNEL_ROLLED_BACK",
                commandId, actorId, correlationId,
                java.util.Map.of("currentVersionId", before), response, List.of(),
                WidgetRegistryLedger.RevisionAxis.REGISTRY);
        receipts.store(actorId, commandId, "ROLLBACK", target, fingerprint, response);
        return response;
    }

    private WidgetRegistryDtos.VersionResponse safetyTransition(
            Long actorId, UUID commandId, String correlationId, UUID versionId,
            WidgetRegistryDtos.SafetyTransitionRequest request, String operation,
            String releaseState, String safetyState, String eventType) {
        String fingerprint = receipts.fingerprint(request);
        var replay = receipts.replay(actorId, commandId, operation, versionId.toString(), fingerprint,
                WidgetRegistryDtos.VersionResponse.class);
        if (replay != null) return replay;
        WidgetDefinitionVersion value = definitions.lockVersion(versionId, request.expectedVersion());
        boolean allowed = switch (operation) {
            case "BLOCK" -> "CLEAR".equals(value.getSafetyState())
                    && List.of("PUBLISHED", "DEPRECATED").contains(value.getReleaseState());
            case "QUARANTINE" -> "CLEAR".equals(value.getSafetyState())
                    && List.of("PUBLISHED", "DEPRECATED", "BLOCKED").contains(value.getReleaseState());
            case "REVOKE" -> !"REVOKED".equals(value.getSafetyState())
                    && List.of("PUBLISHED", "DEPRECATED", "BLOCKED").contains(value.getReleaseState());
            default -> false;
        };
        if (!allowed) {
            throw invalid("Safety transition is not allowed from the current state.");
        }
        requireImpact(request.expectedImpactRevision(), impacts.calculate(
                null, value.getDefinitionId(), versionId, operation));
        if (request.replacementVersionId() != null) {
            WidgetDefinitionVersion replacement = definitions.requireVersion(request.replacementVersionId());
            requireReleaseCandidate(value.getDefinitionId(), replacement);
            if (replacement.getVersionId().equals(versionId)) {
                throw invalid("Safety replacement must be another eligible version.");
            }
        }
        value.setReleaseState(releaseState);
        value.setSafetyState(safetyState);
        value.setReplacementVersionId(request.replacementVersionId());
        value.setUpdatedBy(actorId);
        definitions.saveVersion(value);
        var response = mapper.version(value);
        ledger.append(null, "VERSION", versionId.toString(), eventType, commandId, actorId,
                correlationId, null, response, List.of(), WidgetRegistryLedger.RevisionAxis.SAFETY);
        receipts.store(actorId, commandId, operation, versionId.toString(), fingerprint, response);
        return response;
    }

    private void requireReleaseCandidate(UUID definitionId, WidgetDefinitionVersion candidate) {
        if (!definitionId.equals(candidate.getDefinitionId())
                || !"PUBLISHED".equals(candidate.getReleaseState())
                || !"CLEAR".equals(candidate.getSafetyState())
                || !"PASS".equals(candidate.getCertificationStatus())
                || !definitions.hasCurrentCertificationEvidence(candidate)
                || !definitions.hasActiveRendererBinding(candidate)
                || !candidate.isImmutable()) {
            throw invalid("Release target must be an immutable, certified, published and safe version.");
        }
    }

    private void guard(WidgetDefinitionVersion value) {
        WidgetDefinition definition = definitions.requireDefinition(value.getDefinitionId());
        mutationGuard.requireAllowed(
                null, definition.getOwnerProductKey(), definition.getDefinitionId(), value.getVersionId());
    }

    private void saveChannel(WidgetReleaseChannel channel) {
        try {
            channels.saveAndFlush(channel);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw conflict();
        }
    }

    private static void requireImpact(String expected, WidgetRegistryDtos.ImpactResponse actual) {
        if (expected == null || !expected.equals(actual.impactRevision())) {
            throw new BaseException(ErrorCode.DECISION_REVISION_CONFLICT, "Impact changed; preview again.");
        }
    }

    private static void requireVersion(Long current, Long expected) {
        long normalized = current == null ? 0 : current;
        if (expected == null || expected != normalized) throw conflict();
    }

    private static BaseException conflict() {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_STATE, message);
    }
}
