package com.dwp.services.platform.security;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.announcement.Announcement;
import com.dwp.services.platform.announcement.AnnouncementAudienceType;
import com.dwp.services.platform.announcement.AnnouncementLifecycle;
import com.dwp.services.platform.announcement.AnnouncementRepository;
import com.dwp.services.platform.servicecenter.ServiceCenterRepository;
import com.dwp.services.platform.servicecenter.ServiceCenterTypes.RequestStatus;
import com.dwp.services.platform.support.PilotAuthorizationFixtureAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PlatformRoutePredicateEvaluatorTest {

    @Mock
    private AnnouncementRepository announcementRepository;
    @Mock
    private ServiceCenterRepository serviceCenterRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final PilotAuthorizationFixtureAdapter fixtures =
            new PilotAuthorizationFixtureAdapter();
    private PlatformRoutePredicateEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new PlatformRoutePredicateEvaluator(
                announcementRepository,
                serviceCenterRepository,
                new PlatformCanaryPepRegistry(objectMapper),
                Clock.fixed(Instant.parse("2026-08-21T09:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach
    void clearAuthorizationContext() {
        PlatformCanaryAuthorizationContext.clear();
    }

    @Test
    void communicationVisibilityAndReaderActionsRecheckTheOwnerRepository() throws Exception {
        requireCanaryFixture("PS-C009", "COMMUNICATION_VISIBLE_1");
        JsonNode source = source("COMMUNICATION_VISIBLE_1");
        Announcement visible = announcement(
                AnnouncementLifecycle.valueOf(source.path("state").asText()),
                source.path("version").asLong());
        when(announcementRepository.findByAnnouncementIdAndTenantId(91L, 7L))
                .thenReturn(Optional.of(visible));

        authorizeAs("route.communications.work.for-you-story.page");
        assertThat(evaluator.requireVisibleCommunication(7L, "WORKSPACE_MEMBER", 91L))
                .isSameAs(visible);

        authorizeAs("route.communications.work.event.action");
        assertThat(evaluator.requireCommunicationReaderAction(
                7L, "WORKSPACE_MEMBER", 91L)).isSameAs(visible);

        visible.setLifecycleState(AnnouncementLifecycle.DRAFT);
        assertThatThrownBy(() -> evaluator.requireCommunicationReaderAction(
                7L, "WORKSPACE_MEMBER", 91L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void announcementMutationRequiresTheExactFixtureObjectVersion() throws Exception {
        requireCanaryFixture("PS-C009", "ANNOUNCEMENT_DRAFT_1");
        JsonNode source = source("ANNOUNCEMENT_DRAFT_1");
        Announcement draft = announcement(
                AnnouncementLifecycle.valueOf(source.path("state").asText()),
                source.path("version").asLong());
        when(announcementRepository.findByAnnouncementIdAndTenantId(91L, 7L))
                .thenReturn(Optional.of(draft));

        authorizeAs("route.communications.management.content-update.action");
        assertThat(evaluator.requireAnnouncementObjectVersion(
                7L, 91L, source.path("version").asLong())).isSameAs(draft);
        assertThatThrownBy(() -> evaluator.requireAnnouncementObjectVersion(
                7L, 91L, source.path("version").asLong() - 1))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void catalogUpdateRequiresTheBoundFixtureVersion() throws Exception {
        requireCanaryFixture("PS-C011", "SERVICE_CATALOG_ITEM_1");
        JsonNode source = source("SERVICE_CATALOG_ITEM_1");
        when(serviceCenterRepository.definitionAuthorizationEvidence(7L, "service.one"))
                .thenReturn(Optional.of(
                        new ServiceCenterRepository.DefinitionAuthorizationEvidence(
                                "service.one", source.path("version").asLong())));

        authorizeAs("route.services.management.catalog-update.action");
        evaluator.requireCatalogObjectVersion(
                7L, "service.one", source.path("version").asLong());
        assertThatThrownBy(() -> evaluator.requireCatalogObjectVersion(
                7L, "service.one", source.path("version").asLong() - 1))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void ownRequestPredicateRejectsAnotherRequesterAndStaleVersion() {
        requireCanaryFixture("PS-C005", "SERVICES_WORK");
        UUID requestId = UUID.randomUUID();
        when(serviceCenterRepository.requestAuthorizationEvidence(7L, requestId))
                .thenReturn(Optional.of(requestEvidence(
                        requestId, 101L, null, RequestStatus.DRAFT, 4L)));

        authorizeAs("route.services.work.draft-detail.page");
        evaluator.requireOwnServiceRequest(7L, 101L, requestId, 4L);
        assertThatThrownBy(() -> evaluator.requireOwnServiceRequest(
                7L, 102L, requestId, 4L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> evaluator.requireOwnServiceRequest(
                7L, 101L, requestId, 3L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void assignedTransitionRechecksAssigneeStateAndVersionFromTheFixture() throws Exception {
        requireCanaryFixture("PS-C012", "SERVICE_REQUEST_ASSIGNED_1");
        JsonNode assigned = source("SERVICE_REQUEST_ASSIGNED_1");
        UUID requestId = UUID.randomUUID();
        when(serviceCenterRepository.requestAuthorizationEvidence(7L, requestId))
                .thenReturn(Optional.of(requestEvidence(
                        requestId,
                        55L,
                        "101",
                        RequestStatus.valueOf(assigned.path("state").asText()),
                        assigned.path("version").asLong())));

        authorizeAs("route.services.management.request-transition.action");
        evaluator.requireAssignedServiceRequestTransition(
                7L, 101L, requestId, RequestStatus.RESOLVED,
                assigned.path("version").asLong());
        assertThatThrownBy(() -> evaluator.requireAssignedServiceRequestTransition(
                7L, 102L, requestId, RequestStatus.RESOLVED,
                assigned.path("version").asLong()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> evaluator.requireAssignedServiceRequestTransition(
                7L, 101L, requestId, RequestStatus.RESOLVED,
                assigned.path("version").asLong() - 1))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
        assertThatThrownBy(() -> evaluator.requireAssignedServiceRequestTransition(
                7L, 101L, requestId, RequestStatus.CLOSED,
                assigned.path("version").asLong()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    @Test
    void unboundOrWrongRouteCannotInvokeAnOwnerPredicate() {
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> evaluator.requireOwnServiceRequest(
                7L, 101L, requestId, 1L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        authorizeAs("route.communications.work.for-you-story.page");
        assertThatThrownBy(() -> evaluator.requireOwnServiceRequest(
                7L, 101L, requestId, 1L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(announcementRepository, serviceCenterRepository);
    }

    private void requireCanaryFixture(String testId, String reference) {
        PilotAuthorizationFixtureAdapter.PlatformPepFixture fixture = fixtures.project(testId);
        assertThat(fixture.group()).isEqualTo("CANARY");
        assertThat(fixture.composition())
                .extracting(PilotAuthorizationFixtureAdapter.SourceRecord::reference)
                .contains(reference);
    }

    private JsonNode source(String reference) throws Exception {
        return objectMapper.readTree(fixtures.source(reference).canonicalJson());
    }

    private void authorizeAs(String routeContractKey) {
        PlatformCanaryAuthorizationContext.clear();
        PlatformCanaryAuthorizationContext.set(java.util.List.of(routeContractKey));
    }

    private Announcement announcement(AnnouncementLifecycle lifecycle, long version) {
        return Announcement.builder()
                .announcementId(91L)
                .tenantId(7L)
                .title("Canary")
                .message("Canary")
                .lifecycleState(lifecycle)
                .audienceType(AnnouncementAudienceType.ALL)
                .version(version)
                .build();
    }

    private ServiceCenterRepository.RequestAuthorizationEvidence requestEvidence(
            UUID requestId,
            Long requester,
            String assignedTo,
            RequestStatus status,
            long version) {
        return new ServiceCenterRepository.RequestAuthorizationEvidence(
                requestId, requester, assignedTo, status, version);
    }
}
