package com.dwp.services.platform.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.announcement.Announcement;
import com.dwp.services.platform.announcement.AnnouncementAudienceType;
import com.dwp.services.platform.announcement.AnnouncementLifecycle;
import com.dwp.services.platform.announcement.AnnouncementRepository;
import com.dwp.services.platform.servicecenter.ServiceCenterRepository;
import com.dwp.services.platform.servicecenter.ServiceCenterTypes.RequestStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Product-owner evaluation for the Platform predicates referenced by Canary routes. */
@Component
public class PlatformRoutePredicateEvaluator {

    private static final String VISIBLE_COMMUNICATION =
            "predicate.visible-communication.v1";
    private static final String COMMUNICATION_READER_ACTION =
            "predicate.communication-reader-action.v1";
    private static final String OBJECT_VERSION =
            "predicate.platform.object-version.v1";
    private static final String OWN_REQUEST =
            "predicate.platform.own-request.v1";
    private static final String ASSIGNED_SERVICE_REQUEST =
            "predicate.platform.assigned-service-request.v1";
    private static final Pattern ROLE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_:-]{0,79}");
    private static final Map<RequestStatus, Set<RequestStatus>> OPERATOR_TRANSITIONS = Map.of(
            RequestStatus.SUBMITTED, Set.of(
                    RequestStatus.TRIAGED, RequestStatus.IN_PROGRESS, RequestStatus.CANCELLED),
            RequestStatus.TRIAGED, Set.of(
                    RequestStatus.IN_PROGRESS, RequestStatus.AWAITING_REQUESTER,
                    RequestStatus.CANCELLED),
            RequestStatus.IN_PROGRESS, Set.of(
                    RequestStatus.AWAITING_REQUESTER, RequestStatus.RESOLVED,
                    RequestStatus.CANCELLED),
            RequestStatus.AWAITING_REQUESTER, Set.of(
                    RequestStatus.IN_PROGRESS, RequestStatus.RESOLVED,
                    RequestStatus.CANCELLED),
            RequestStatus.RESOLVED, Set.of(RequestStatus.IN_PROGRESS, RequestStatus.CLOSED));

    private final AnnouncementRepository announcementRepository;
    private final ServiceCenterRepository serviceCenterRepository;
    private final PlatformCanaryPepRegistry canaryPepRegistry;
    private final Clock clock;

    @Autowired
    public PlatformRoutePredicateEvaluator(
            AnnouncementRepository announcementRepository,
            ServiceCenterRepository serviceCenterRepository,
            PlatformCanaryPepRegistry canaryPepRegistry) {
        this(
                announcementRepository,
                serviceCenterRepository,
                canaryPepRegistry,
                Clock.systemUTC());
    }

    PlatformRoutePredicateEvaluator(
            AnnouncementRepository announcementRepository,
            ServiceCenterRepository serviceCenterRepository,
            PlatformCanaryPepRegistry canaryPepRegistry,
            Clock clock) {
        this.announcementRepository = announcementRepository;
        this.serviceCenterRepository = serviceCenterRepository;
        this.canaryPepRegistry = canaryPepRegistry;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Announcement requireVisibleCommunication(
            Long tenantId,
            String rolesHeader,
            Long communicationId) {
        requireBound(VISIBLE_COMMUNICATION);
        return evaluateCommunicationVisibility(tenantId, rolesHeader, communicationId);
    }

    @Transactional(readOnly = true)
    public Announcement requireCommunicationReaderAction(
            Long tenantId,
            String rolesHeader,
            Long communicationId) {
        requireBound(COMMUNICATION_READER_ACTION);
        return evaluateCommunicationVisibility(tenantId, rolesHeader, communicationId);
    }

    private Announcement evaluateCommunicationVisibility(
            Long tenantId,
            String rolesHeader,
            Long communicationId) {
        Announcement announcement = requireAnnouncement(tenantId, communicationId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean inWindow = (announcement.getStartsAt() == null
                || !announcement.getStartsAt().isAfter(now))
                && (announcement.getEndsAt() == null
                || announcement.getEndsAt().isAfter(now));
        List<String> roles = parseRoles(rolesHeader);
        boolean inAudience = announcement.getAudienceType() == AnnouncementAudienceType.ALL
                || roles.contains(announcement.getAudienceValue());
        if (announcement.getLifecycleState() != AnnouncementLifecycle.PUBLISHED
                || !inWindow
                || !inAudience) {
            throw forbidden();
        }
        return announcement;
    }

    @Transactional(readOnly = true)
    public Announcement requireAnnouncementObjectVersion(
            Long tenantId,
            Long announcementId,
            Long expectedVersion) {
        requireBound(OBJECT_VERSION);
        Announcement announcement = requireAnnouncement(tenantId, announcementId);
        long currentVersion = announcement.getVersion() == null ? 0L : announcement.getVersion();
        if (expectedVersion == null || currentVersion != expectedVersion) {
            throw conflict("The announcement was changed by another administrator.");
        }
        return announcement;
    }

    @Transactional(readOnly = true)
    public void requireCatalogObjectVersion(
            Long tenantId,
            String serviceKey,
            Long expectedVersion) {
        requireBound(OBJECT_VERSION);
        ServiceCenterRepository.DefinitionAuthorizationEvidence evidence = serviceCenterRepository
                .definitionAuthorizationEvidence(tenantId, serviceKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (expectedVersion == null || evidence.version() != expectedVersion) {
            throw conflict("The service definition changed. Refresh and retry.");
        }
    }

    @Transactional(readOnly = true)
    public void requireOwnServiceRequest(
            Long tenantId,
            Long actorId,
            UUID requestId,
            Long expectedVersion) {
        requireBound(OWN_REQUEST);
        ServiceCenterRepository.RequestAuthorizationEvidence evidence = requestEvidence(
                tenantId, requestId);
        if (!actorId.equals(evidence.requesterUserId())) throw forbidden();
        requireVersion(evidence.version(), expectedVersion);
    }

    @Transactional(readOnly = true)
    public void requireAssignedServiceRequestTransition(
            Long tenantId,
            Long actorId,
            UUID requestId,
            RequestStatus targetStatus,
            Long expectedVersion) {
        requireBound(ASSIGNED_SERVICE_REQUEST, OBJECT_VERSION);
        ServiceCenterRepository.RequestAuthorizationEvidence evidence = requestEvidence(
                tenantId, requestId);
        if (!actorId.toString().equals(evidence.assignedTo())) throw forbidden();
        requireVersion(evidence.version(), expectedVersion);
        if (!OPERATOR_TRANSITIONS.getOrDefault(evidence.status(), Set.of())
                .contains(targetStatus)) {
            throw conflict("The requested service status transition is not allowed.");
        }
    }

    private Announcement requireAnnouncement(Long tenantId, Long announcementId) {
        return announcementRepository.findByAnnouncementIdAndTenantId(announcementId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private ServiceCenterRepository.RequestAuthorizationEvidence requestEvidence(
            Long tenantId, UUID requestId) {
        return serviceCenterRepository.requestAuthorizationEvidence(tenantId, requestId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requireBound(String... predicatePolicyKeys) {
        List<String> routeContractKeys = PlatformCanaryAuthorizationContext.current()
                .orElseThrow(this::forbidden);
        if (!canaryPepRegistry.predicatesCover(
                routeContractKeys, Set.of(predicatePolicyKeys))) {
            throw forbidden();
        }
    }

    private void requireVersion(long actualVersion, Long expectedVersion) {
        if (expectedVersion != null && actualVersion != expectedVersion) {
            throw conflict("The service request changed. Refresh and retry.");
        }
    }

    private List<String> parseRoles(String rolesHeader) {
        if (rolesHeader == null || rolesHeader.isBlank()) return List.of();
        return Arrays.stream(rolesHeader.split(","))
                .map(String::trim)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> ROLE_PATTERN.matcher(value).matches())
                .distinct()
                .toList();
    }

    private BaseException forbidden() {
        return new BaseException(ErrorCode.FORBIDDEN);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
