package com.dwp.services.auth.service;

import com.dwp.core.event.DomainEventContractRegistry;
import com.dwp.core.event.DomainEventEnvelope;
import com.dwp.core.event.DomainEventRecorder;
import com.dwp.services.auth.dto.AppGovernanceDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

/** Transactional, privacy-minimal lifecycle events for the preset aggregate. */
@Component
public class AppAdminPresetOutboxPublisher {

    public static final String REQUESTED = "identity.app-admin-preset.requested.v1";
    public static final String DECIDED = "identity.app-admin-preset.decided.v1";
    public static final String ACTIVATED = "identity.app-admin-preset.activated.v1";
    public static final String REVOKED = "identity.app-admin-preset.revoked.v1";
    public static final String EXPIRED = "identity.app-admin-preset.expired.v1";
    public static final String REVIEW_DECIDED =
            "identity.app-admin-preset.review-decided.v1";

    private static final String SOURCE = "urn:dwp:auth:app-admin-preset";

    private final DomainEventRecorder recorder;
    private final ObjectMapper objectMapper;

    public AppAdminPresetOutboxPublisher(
            DomainEventRecorder recorder,
            DomainEventContractRegistry contracts,
            ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        List.of(REQUESTED, DECIDED, ACTIVATED, REVOKED, EXPIRED, REVIEW_DECIDED)
                .forEach(type -> contracts.register(type, 1, 1));
    }

    public void assignment(
            String eventType,
            Long tenantId,
            AppGovernanceDtos.AppAdminPresetAssignment assignment,
            long sequence,
            String correlationId) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("presetAssignmentId", assignment.presetAssignmentId().toString())
                .put("presetCode", assignment.presetCode())
                .put("productKey", assignment.productKey())
                .put("resourceSetId", assignment.resourceSetId().toString())
                .put("lifecycleState", assignment.lifecycleState())
                .put("objectVersion", assignment.version())
                .put("catalogVersion", assignment.catalogVersion())
                .put("validTo", assignment.validTo().toString());
        recorder.record(DomainEventEnvelope.create(
                SOURCE, eventType, 1, tenantId, "APP_ADMIN_PRESET_ASSIGNMENT",
                assignment.presetAssignmentId().toString(), sequence,
                correlation(correlationId, assignment.presetAssignmentId().toString(), sequence),
                null, null, data));
    }

    public void review(
            Long tenantId,
            AppGovernanceDtos.AppAdminPresetReview review,
            String correlationId) {
        ObjectNode data = objectMapper.createObjectNode()
                .put("reviewId", review.reviewId().toString())
                .put("dutyCode", review.dutyCode())
                .put("reasonCode", review.reasonCode())
                .put("resourceSetId", review.resourceSetId() == null
                        ? null : review.resourceSetId().toString())
                .put("lifecycleState", review.lifecycleState())
                .put("objectVersion", review.version());
        recorder.record(DomainEventEnvelope.create(
                SOURCE, REVIEW_DECIDED, 1, tenantId, "APP_ADMIN_PRESET_REVIEW",
                review.reviewId().toString(), review.version(),
                correlation(correlationId, review.reviewId().toString(), review.version()),
                null, null, data));
    }

    private String correlation(String value, String aggregateId, long sequence) {
        return value == null || value.isBlank()
                ? "app-admin-preset:" + aggregateId + ':' + sequence
                : value.strip();
    }
}
