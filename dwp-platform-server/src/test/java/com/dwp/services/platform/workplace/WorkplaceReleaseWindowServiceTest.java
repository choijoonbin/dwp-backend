package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceReleaseWindowServiceTest {

    @Mock
    private WorkplaceCatalogRepository catalog;

    @Mock
    private WorkplaceReleaseWindowRepository releases;

    @Mock
    private WorkplaceBookingRepository audit;

    @Mock
    private WorkplaceRuntimeGovernance runtimeGovernance;

    private WorkplaceReleaseWindowService service;

    @BeforeEach
    void setUp() {
        service = new WorkplaceReleaseWindowService(
                catalog, releases, audit, runtimeGovernance);
    }

    @Test
    void assignedResourcesExcludeSitesTheMemberCannotBook() {
        UUID allowedSiteId = UUID.randomUUID();
        UUID deniedSiteId = UUID.randomUUID();
        WorkplaceReleaseWindowDtos.AssignedResource allowed = assignedResource(allowedSiteId);
        WorkplaceReleaseWindowDtos.AssignedResource denied = assignedResource(deniedSiteId);
        when(catalog.policy(1L)).thenReturn(policy(true));
        when(releases.assignedResources(1L, 7L, null, false))
                .thenReturn(List.of(allowed, denied));
        org.mockito.Mockito.doAnswer(invocation -> {
            if (deniedSiteId.equals(invocation.getArgument(3))) {
                throw new BaseException(ErrorCode.FORBIDDEN);
            }
            return null;
        }).when(runtimeGovernance)
                .requireBookAccess(eq(1L), eq(7L), eq("group-a"), any(UUID.class));

        List<WorkplaceReleaseWindowDtos.AssignedResource> result =
                service.assignedResources(1L, 7L, null, "en-US", "group-a");

        assertThat(result).containsExactly(allowed);
        verify(runtimeGovernance).requireBookAccess(1L, 7L, "group-a", allowedSiteId);
        verify(runtimeGovernance).requireBookAccess(1L, 7L, "group-a", deniedSiteId);
    }

    @Test
    void assignedResourcesUseEachResourcesEffectiveLendingPolicy() {
        UUID siteId = UUID.randomUUID();
        WorkplaceReleaseWindowDtos.AssignedResource resource = assignedResource(siteId);
        WorkplaceCatalogRepository.PolicyRow tenantPolicy = policy(false);
        WorkplaceCatalogRepository.PolicyRow resourcePolicy = policy(true);
        when(catalog.policy(1L)).thenReturn(tenantPolicy);
        when(releases.assignedResources(1L, 7L, null, false))
                .thenReturn(List.of(resource));
        when(runtimeGovernance.effectivePolicy(
                1L,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                resource.resourceId(),
                tenantPolicy)).thenReturn(resourcePolicy);

        List<WorkplaceReleaseWindowDtos.AssignedResource> result =
                service.assignedResources(1L, 7L, null, "en-US", null);

        assertThat(result).containsExactly(resource);
    }

    @Test
    void createUsesEffectiveResourcePolicyAndAuditsTheOwnerRelease() {
        UUID resourceId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2);
        WorkplaceReleaseWindowDtos.CreateRequest request =
                new WorkplaceReleaseWindowDtos.CreateRequest(
                        resourceId, startsAt, startsAt.plusHours(4), "Team day");
        WorkplaceCatalogRepository.ResourceRow resource = assignedResourceRow(
                resourceId, floorId, 7L, ResourceState.AVAILABLE);
        WorkplaceCatalogRepository.PolicyRow basePolicy = policy(false);
        WorkplaceCatalogRepository.PolicyRow effectivePolicy = policy(true);
        WorkplaceReleaseWindowRepository.ReleaseWindowRow created = releaseWindowRow(
                resourceId, startsAt, startsAt.plusHours(4), 0L);
        when(catalog.resource(1L, resourceId, false)).thenReturn(Optional.of(resource));
        when(catalog.floor(1L, floorId, false)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.policy(1L)).thenReturn(basePolicy);
        when(runtimeGovernance.effectivePolicy(
                1L,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                resourceId,
                basePolicy)).thenReturn(effectivePolicy);
        when(releases.idempotency(1L, 7L, "release-1")).thenReturn(Optional.empty());
        when(releases.create(
                1L, 7L, null, request, "release-1", fingerprint(request), 365, false))
                .thenReturn(created);

        WorkplaceReleaseWindowDtos.ReleaseWindow result = service.create(
                1L, 7L, null, "en-US", "corr-release", "release-1", "group-a", request);

        assertThat(result.resourceId()).isEqualTo(resourceId);
        assertThat(result.status()).isEqualTo("ACTIVE");
        verify(releases).lockUserReleaseScope(1L, 7L);
        verify(runtimeGovernance).requireBookAccess(1L, 7L, "group-a", siteId);
        verify(audit).audit(
                eq(1L), eq(7L), eq("workplace.assigned-resource.released"),
                eq("RELEASE_WINDOW"), eq(created.releaseWindowId()),
                eq("corr-release"), anyMap());
    }

    @Test
    void createRejectsAReleaseByAnyoneOtherThanTheVerifiedAssignee() {
        UUID resourceId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2);
        WorkplaceReleaseWindowDtos.CreateRequest request =
                new WorkplaceReleaseWindowDtos.CreateRequest(
                        resourceId, startsAt, startsAt.plusHours(1), null);
        WorkplaceCatalogRepository.ResourceRow resource = assignedResourceRow(
                resourceId, floorId, 99L, ResourceState.AVAILABLE);
        WorkplaceCatalogRepository.PolicyRow policy = policy(true);
        when(catalog.resource(1L, resourceId, false)).thenReturn(Optional.of(resource));
        when(catalog.floor(1L, floorId, false)).thenReturn(Optional.of(floor(siteId, floorId)));
        when(catalog.policy(1L)).thenReturn(policy);
        when(runtimeGovernance.effectivePolicy(
                1L,
                WorkplaceSpatialGovernanceDtos.PolicyScopeType.RESOURCE,
                resourceId,
                policy)).thenReturn(policy);

        assertThatThrownBy(() -> service.create(
                1L, 7L, null, "en-US", "corr-release", null, null, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN))
                .hasMessageContaining("verified assignee");

        verify(releases, never()).create(
                any(), any(), any(), any(), any(), any(), any(Integer.class), anyBoolean());
        verify(audit, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void repeatedIdempotentCreateReturnsTheOriginalWindowWithoutWritingAgain() {
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(2);
        WorkplaceReleaseWindowDtos.CreateRequest request =
                new WorkplaceReleaseWindowDtos.CreateRequest(
                        resourceId, startsAt, startsAt.plusHours(2), "Team day");
        WorkplaceReleaseWindowRepository.ReleaseWindowRow original = releaseWindowRow(
                resourceId, startsAt, startsAt.plusHours(2), 0L);
        when(releases.idempotency(1L, 7L, "release-7")).thenReturn(Optional.of(
                new WorkplaceReleaseWindowRepository.IdempotencyRow(
                        original.releaseWindowId(), fingerprint(request))));
        when(releases.ownedWindow(
                1L, 7L, null, original.releaseWindowId(), false))
                .thenReturn(Optional.of(original));

        WorkplaceReleaseWindowDtos.ReleaseWindow result = service.create(
                1L, 7L, null, "en-US", "corr-release", "release-7", null, request);

        assertThat(result.releaseWindowId()).isEqualTo(original.releaseWindowId());
        verify(releases).lockUserReleaseScope(1L, 7L);
        verify(releases, never()).create(
                any(), any(), any(), any(), any(), any(), any(Integer.class), anyBoolean());
        verify(audit, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cancelFailsClosedWhenTheWindowChangedOrHasAnActiveReservation() {
        UUID releaseWindowId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now().plusDays(1);
        WorkplaceReleaseWindowRepository.ReleaseWindowRow current = releaseWindowRow(
                resourceId, startsAt, startsAt.plusHours(2), 3L);
        when(releases.ownedWindow(
                1L, 7L, null, releaseWindowId, false)).thenReturn(Optional.of(current));
        when(releases.lockWindowForUpdate(1L, releaseWindowId)).thenReturn(resourceId);
        when(releases.cancel(
                eq(1L), eq(7L), eq(null), eq(releaseWindowId), eq(3L), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.cancel(
                1L, 7L, null, releaseWindowId, "en-US", "corr-cancel",
                new WorkplaceDtos.VersionRequest(3L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("active reservation");

        verify(audit, never()).audit(any(), any(), any(), any(), any(), any(), any());
    }

    private WorkplaceReleaseWindowDtos.AssignedResource assignedResource(UUID siteId) {
        return new WorkplaceReleaseWindowDtos.AssignedResource(
                UUID.randomUUID(), "Desk", "DESK", siteId, "Site",
                UUID.randomUUID(), "Floor", "Asia/Seoul");
    }

    private WorkplaceCatalogRepository.ResourceRow assignedResourceRow(
            UUID resourceId,
            UUID floorId,
            Long assignedUserId,
            ResourceState state) {
        return new WorkplaceCatalogRepository.ResourceRow(
                resourceId, floorId, null, "DESK-01", "Desk 01", "좌석 01", "Desk 01",
                ResourceType.DESK, BookingMode.ASSIGNED, state,
                "Team zone", 1, List.of("MONITOR"), false, false,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, assignedUserId, null, "Member", 0L, null);
    }

    private WorkplaceCatalogRepository.FloorRow floor(UUID siteId, UUID floorId) {
        return new WorkplaceCatalogRepository.FloorRow(
                floorId, siteId, "Site", 2, "2F", "2층", "2F",
                1200, 760, null, null, null, null, null,
                FloorState.ACTIVE, 10, 0L);
    }

    private WorkplaceCatalogRepository.PolicyRow policy(boolean lending) {
        return new WorkplaceCatalogRepository.PolicyRow(
                30, 20, 30, 720, 5,
                LocalTime.of(8, 0), LocalTime.of(20, 0),
                false, true, 30, 30, lending, false, 365, 0L);
    }

    private WorkplaceReleaseWindowRepository.ReleaseWindowRow releaseWindowRow(
            UUID resourceId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            long version) {
        return new WorkplaceReleaseWindowRepository.ReleaseWindowRow(
                UUID.randomUUID(), resourceId, "Desk 01", "Site", "2F",
                startsAt, endsAt, "Team day", "ACTIVE", null, version);
    }

    private String fingerprint(WorkplaceReleaseWindowDtos.CreateRequest request) {
        try {
            String note = request.note() == null || request.note().isBlank()
                    ? null : request.note().trim();
            String canonical = request.resourceId() + "\n"
                    + request.startsAt().toInstant() + "\n"
                    + request.endsAt().toInstant() + "\n"
                    + String.valueOf(note);
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
