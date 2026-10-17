package com.dwp.services.auth.tenantfeatureactivation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Three-person maker/checker/activator orchestration with CAS and immutable receipts. */
@Service
public class TenantFeatureActivationService {

    private final TenantFeatureActivationControlRepository repository;
    private final Clock clock;

    @Autowired
    public TenantFeatureActivationService(TenantFeatureActivationControlRepository repository) {
        this(repository, Clock.systemUTC());
    }

    TenantFeatureActivationService(
            TenantFeatureActivationControlRepository repository,
            Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public TenantFeatureActivationDtos.GlobalChange requestGlobal(
            String featureKey,
            long targetBundleVersion,
            String justification,
            String requesterRef,
            String correlationId) {
        String feature = feature(featureKey);
        String actor = actor(requesterRef);
        long policyVersion = repository.lockActivePolicy(feature);
        Instant now = clock.instant();
        var change = repository.insertGlobal(
                feature, policyVersion, targetBundleVersion,
                reason(justification), actor, now);
        event(change, "REQUESTED", actor, correlation(correlationId), null);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.GlobalChange submitGlobal(
            UUID changeId, long expectedVersion, String requesterRef, String correlationId) {
        String actor = actor(requesterRef);
        var before = repository.requireGlobal(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.submitGlobal(changeId, expectedVersion, actor, clock.instant());
        event(change, "SUBMITTED", actor, correlation(correlationId), null);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.GlobalChange approveGlobal(
            UUID changeId, long expectedVersion, String reviewerRef, String correlationId) {
        String actor = actor(reviewerRef);
        var before = repository.requireGlobal(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.approveGlobal(changeId, expectedVersion, actor, clock.instant());
        event(change, "APPROVED", actor, correlation(correlationId), null);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.GlobalChange activateGlobal(
            UUID changeId,
            long expectedVersion,
            String activatorRef,
            UUID receiptId,
            String correlationId) {
        String actor = actor(activatorRef);
        if (receiptId == null) throw new IllegalArgumentException("Activation receipt is required.");
        var before = repository.requireGlobal(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.activateGlobal(
                changeId, expectedVersion, actor, receiptId, clock.instant());
        event(change, "ACTIVATED", actor, correlation(correlationId), receiptId);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.GlobalChange revokeGlobal(
            UUID changeId,
            long expectedVersion,
            String actorRef,
            String revocationReason,
            String correlationId) {
        String actor = actor(actorRef);
        var before = repository.requireGlobal(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.revokeGlobal(
                changeId, expectedVersion, actor, reason(revocationReason), clock.instant());
        event(change, "REVOKED", actor, correlation(correlationId), null);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.TenantEnrollment requestTenant(
            Long tenantId,
            String featureKey,
            String justification,
            Long requesterId,
            String correlationId) {
        requireId(tenantId, "tenant");
        requireId(requesterId, "requester");
        String feature = feature(featureKey);
        long policyVersion = repository.lockActivePolicy(feature);
        var change = repository.insertTenant(
                tenantId, feature, policyVersion, reason(justification),
                requesterId, clock.instant());
        event(change, "REQUESTED", requesterId, correlation(correlationId), null);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.TenantEnrollment submitTenant(
            UUID changeId, long expectedVersion, Long requesterId, String correlationId) {
        requireId(requesterId, "requester");
        var before = repository.requireTenant(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.submitTenant(
                changeId, expectedVersion, requesterId, clock.instant());
        event(change, "SUBMITTED", requesterId, correlation(correlationId), null);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.TenantEnrollment approveTenant(
            UUID changeId, long expectedVersion, Long reviewerId, String correlationId) {
        requireId(reviewerId, "reviewer");
        var before = repository.requireTenant(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.approveTenant(
                changeId, expectedVersion, reviewerId, clock.instant());
        event(change, "APPROVED", reviewerId, correlation(correlationId), null);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.TenantEnrollment activateTenant(
            UUID changeId,
            long expectedVersion,
            Long activatorId,
            UUID receiptId,
            String correlationId) {
        requireId(activatorId, "activator");
        if (receiptId == null) throw new IllegalArgumentException("Activation receipt is required.");
        var before = repository.requireTenant(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.activateTenant(
                changeId, expectedVersion, activatorId, receiptId, clock.instant());
        event(change, "ACTIVATED", activatorId, correlation(correlationId), receiptId);
        return change;
    }

    @Transactional
    public TenantFeatureActivationDtos.TenantEnrollment revokeTenant(
            UUID changeId,
            long expectedVersion,
            Long actorId,
            String revocationReason,
            String correlationId) {
        requireId(actorId, "actor");
        var before = repository.requireTenant(changeId);
        repository.lockActivePolicy(before.featureKey());
        var change = repository.revokeTenant(
                changeId, expectedVersion, actorId,
                reason(revocationReason), clock.instant());
        event(change, "REVOKED", actorId, correlation(correlationId), null);
        return change;
    }

    private void event(
            TenantFeatureActivationDtos.GlobalChange change,
            String eventType,
            String actorRef,
            String correlationId,
            UUID receiptId) {
        repository.appendEvent(
                "GLOBAL_ACTIVATION", change.changeId(), null, change.featureKey(),
                eventType, actorRef, correlationId, change.version(), receiptId);
    }

    private void event(
            TenantFeatureActivationDtos.TenantEnrollment change,
            String eventType,
            Long actorId,
            String correlationId,
            UUID receiptId) {
        repository.appendEvent(
                "TENANT_ENROLLMENT", change.changeId(), change.tenantId(),
                change.featureKey(), eventType, String.valueOf(actorId), correlationId,
                change.version(), receiptId);
    }

    private static String feature(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Exact feature key is required.");
        }
        return value;
    }

    private static String actor(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Exact actor reference is required.");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String reason(String value) {
        if (value == null || value.strip().length() < 10) {
            throw new IllegalArgumentException("A meaningful reason is required.");
        }
        return value.strip();
    }

    private static String correlation(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Exact correlation id is required.");
        }
        return value;
    }

    private static void requireId(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("A positive " + name + " id is required.");
        }
    }
}
