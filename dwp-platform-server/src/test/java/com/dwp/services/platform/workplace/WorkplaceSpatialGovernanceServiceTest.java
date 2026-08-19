package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceSpatialGovernanceServiceTest {

    @Mock
    private WorkplaceSpatialGovernanceRepository repository;

    private ObjectMapper objectMapper;
    private WorkplaceSpatialGovernanceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new WorkplaceSpatialGovernanceService(repository, objectMapper);
    }

    @Test
    void policyPreviewResolvesNarrowestFieldAndReportsItsSource() {
        UUID campusId = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID tenantOverrideId = UUID.randomUUID();
        UUID siteOverrideId = UUID.randomUUID();
        UUID resourceOverrideId = UUID.randomUUID();
        when(repository.scopePath(1L, PolicyScopeType.RESOURCE, resourceId))
                .thenReturn(Optional.of(new ScopePath(
                        campusId, siteId, floorId, zoneId, resourceId)));
        when(repository.tenantBasePolicy(1L)).thenReturn(Optional.of(basePolicy()));
        when(repository.policyOverrides(1L)).thenReturn(List.of(
                policy(tenantOverrideId, PolicyScopeType.TENANT, null,
                        objectMapper.createObjectNode().put("bookingWindowDays", 60), 1),
                policy(siteOverrideId, PolicyScopeType.SITE, siteId,
                        objectMapper.createObjectNode().put("bookingWindowDays", 21), 2),
                policy(UUID.randomUUID(), PolicyScopeType.FLOOR, floorId,
                        objectMapper.createObjectNode().put("bookingWindowDays", 10),
                        RuleState.INACTIVE, 3),
                policy(resourceOverrideId, PolicyScopeType.RESOURCE, resourceId,
                        objectMapper.createObjectNode().put("requireCheckIn", false), 4)));

        EffectivePolicyPreview result = service.previewPolicy(
                1L, PolicyScopeType.RESOURCE, resourceId);

        assertThat(result.effectivePolicy().path("bookingWindowDays").asInt()).isEqualTo(21);
        assertThat(result.effectivePolicy().path("requireCheckIn").asBoolean()).isFalse();
        assertThat(result.effectivePolicy().path("bookingRetentionDays").asInt()).isEqualTo(365);
        assertThat(result.fieldSources().get("bookingWindowDays").policyOverrideId())
                .isEqualTo(siteOverrideId);
        assertThat(result.fieldSources().get("requireCheckIn").scopeType())
                .isEqualTo(PolicyScopeType.RESOURCE);
        assertThat(result.appliedOverrideIds())
                .containsExactly(tenantOverrideId, siteOverrideId, resourceOverrideId);
    }

    @Test
    void explicitDenyWinsOverVerifiedGroupAllow() {
        UUID siteId = UUID.randomUUID();
        UUID groupRef = UUID.randomUUID();
        UUID allowId = UUID.randomUUID();
        UUID denyId = UUID.randomUUID();
        when(repository.siteCampus(1L, siteId))
                .thenReturn(Optional.of(new SiteCampusRow(siteId, UUID.randomUUID(), 0)));
        when(repository.activeAccessRules(eq(1L), eq(siteId), any()))
                .thenReturn(List.of(
                        new AccessRuleRow(allowId, siteId, AccessSubjectType.GROUP_REF,
                                null, groupRef, AccessPermission.MANAGE, AccessEffect.ALLOW,
                                null, null, RuleState.ACTIVE, 0),
                        new AccessRuleRow(denyId, siteId, AccessSubjectType.USER,
                                9L, null, AccessPermission.BOOK, AccessEffect.DENY,
                                null, null, RuleState.ACTIVE, 0)));

        SiteAccessDecision result = service.evaluateSiteAccess(
                1L, 9L, groupRef + ",engineering-display-key", siteId,
                AccessPermission.BOOK);

        assertThat(result.allowed()).isFalse();
        assertThat(result.decision()).isEqualTo("DENY_EXPLICIT");
        assertThat(result.matchedRuleIds()).containsExactly(allowId, denyId);
    }

    @Test
    void siteWithoutRulesKeepsExistingApiCompatibility() {
        UUID siteId = UUID.randomUUID();
        when(repository.siteCampus(1L, siteId))
                .thenReturn(Optional.of(new SiteCampusRow(siteId, UUID.randomUUID(), 0)));
        when(repository.activeAccessRules(eq(1L), eq(siteId), any())).thenReturn(List.of());

        SiteAccessDecision result = service.evaluateSiteAccess(
                1L, 9L, null, siteId, AccessPermission.VIEW);

        assertThat(result.allowed()).isTrue();
        assertThat(result.decision()).isEqualTo("ALLOW_COMPATIBILITY_DEFAULT");
    }

    @Test
    void incompleteFloorPlanSnapshotIsRejectedBeforePersistence() {
        UUID floorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(repository.floorPlanRevision(1L, revisionId))
                .thenReturn(Optional.of(revision(
                        revisionId, floorId, RevisionState.DRAFT, 0, 1)));
        when(repository.resourceTargets(1L, floorId)).thenReturn(List.of(
                new ResourceTarget(first, 3, zoneId, null),
                new ResourceTarget(second, 2, zoneId, null)));
        when(repository.zones(1L, floorId)).thenReturn(List.of(
                zone(zoneId, floorId, SpatialState.ACTIVE)));
        when(repository.sections(1L, zoneId)).thenReturn(List.of());
        FloorPlanSnapshotRequest request = new FloorPlanSnapshotRequest(
                1200, 760, null, null, null, null, null, "Partial layout",
                List.of(placement(first, 3L, zoneId)), 0L);

        assertThatThrownBy(() -> service.updateFloorPlanRevision(
                1L, 7L, revisionId, "corr", request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("complete floor inventory");

        verify(repository, never()).updateDraft(anyLong(), anyLong(), any(), any(), anyString());
    }

    @Test
    void revisionSnapshotReturnsThePersistedDraftInventoryForSafeResume() {
        UUID floorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        PlacementRow placement = new PlacementRow(
                UUID.randomUUID(), resourceId, 4, zoneId, null,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, objectMapper.createObjectNode(), 0);
        when(repository.floorPlanRevision(1L, revisionId)).thenReturn(Optional.of(
                revision(revisionId, floorId, RevisionState.DRAFT, 2, 1)));
        when(repository.revisionPlacements(1L, revisionId)).thenReturn(List.of(placement));

        FloorPlanRevisionSnapshot result = service.floorPlanRevisionSnapshot(1L, revisionId);

        assertThat(result.revision().revisionId()).isEqualTo(revisionId);
        assertThat(result.revision().state()).isEqualTo(RevisionState.DRAFT);
        assertThat(result.placements()).singleElement()
                .extracting(FloorPlanPlacement::resourceId)
                .isEqualTo(resourceId);
    }

    @Test
    void publishUsesReviewVersionAndProjectsResourcesBeforeFloorPointer() {
        UUID floorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        FloorPlanRevisionRow review = revision(
                revisionId, floorId, RevisionState.REVIEW, 5, 1);
        FloorPlanRevisionRow published = revision(
                revisionId, floorId, RevisionState.PUBLISHED, 6, 1);
        PlacementRow placement = new PlacementRow(
                UUID.randomUUID(), resourceId, 4, zoneId, null,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, objectMapper.createObjectNode(), 0);
        when(repository.floorPlanRevision(1L, revisionId))
                .thenReturn(Optional.of(review))
                .thenReturn(Optional.of(published))
                .thenReturn(Optional.of(published));
        when(repository.floorSnapshot(1L, floorId)).thenReturn(Optional.of(
                new FloorSnapshot(floorId, 1200, 760, null, null, null, null, null, 8)));
        when(repository.revisionPlacements(1L, revisionId)).thenReturn(List.of(placement));
        when(repository.resourceTargets(1L, floorId))
                .thenReturn(List.of(new ResourceTarget(resourceId, 4, zoneId, null)))
                .thenReturn(List.of(new ResourceTarget(resourceId, 4, zoneId, null)));
        when(repository.zones(1L, floorId)).thenReturn(List.of(
                zone(zoneId, floorId, SpatialState.ACTIVE)));
        when(repository.sections(1L, zoneId)).thenReturn(List.of());
        when(repository.publishRevision(1L, 7L, revisionId, 5)).thenReturn(true);
        when(repository.projectPublishedPlacements(1L, 7L, floorId, List.of(placement)))
                .thenReturn(true);
        when(repository.projectPublishedFloor(
                1L, 7L, floorId, revisionId, published, 8)).thenReturn(true);

        FloorPlanRevision result = service.publishFloorPlan(
                1L, 7L, revisionId, "corr",
                new RevisionTransitionRequest(5L, "Approved layout"));

        assertThat(result.state()).isEqualTo(RevisionState.PUBLISHED);
        InOrder order = inOrder(repository);
        order.verify(repository).archivePublished(1L, 7L, floorId);
        order.verify(repository).publishRevision(1L, 7L, revisionId, 5);
        order.verify(repository).projectPublishedPlacements(
                1L, 7L, floorId, List.of(placement));
        order.verify(repository).projectPublishedFloor(
                1L, 7L, floorId, revisionId, published, 8);
        verify(repository).appendAudit(eq(1L), eq(7L),
                eq("workplace.governance.floorplan.published"),
                eq("WP_FLOOR_PLAN"), eq(revisionId), eq("corr"), any());
    }

    @Test
    void reviewRejectsAPlacementWhoseZoneWasDeactivatedAfterDrafting() {
        UUID floorId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        PlacementRow placement = new PlacementRow(
                UUID.randomUUID(), resourceId, 4, zoneId, null,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, objectMapper.createObjectNode(), 0);
        when(repository.floorPlanRevision(1L, revisionId)).thenReturn(Optional.of(
                revision(revisionId, floorId, RevisionState.DRAFT, 2, 1)));
        when(repository.revisionPlacements(1L, revisionId)).thenReturn(List.of(placement));
        when(repository.resourceTargets(1L, floorId)).thenReturn(List.of(
                new ResourceTarget(resourceId, 4, zoneId, null)));
        when(repository.zones(1L, floorId)).thenReturn(List.of(
                zone(zoneId, floorId, SpatialState.CLOSED)));

        assertThatThrownBy(() -> service.submitFloorPlanReview(
                1L, 7L, revisionId, "corr",
                new RevisionTransitionRequest(2L, "Ready for review")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("inactive zone");

        verify(repository, never()).submitForReview(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void historicalRestoreRequiresTheObservedVersion() {
        UUID revisionId = UUID.randomUUID();
        UUID floorId = UUID.randomUUID();
        when(repository.floorPlanRevision(1L, revisionId)).thenReturn(Optional.of(
                revision(revisionId, floorId, RevisionState.ARCHIVED, 4, 0)));

        assertThatThrownBy(() -> service.restoreFloorPlanRevision(
                1L, 7L, revisionId, "corr",
                new RevisionTransitionRequest(3L, "Restore known-good layout")))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(repository, never()).lockFloor(anyLong(), any());
    }

    @Test
    void effectiveDelegationUsesOnlyUuidGroupReferences() {
        UUID verifiedGroup = UUID.randomUUID();
        UUID ignoredGroup = UUID.randomUUID();
        UUID siteId = UUID.randomUUID();
        DelegatedScopeRow allowed = new DelegatedScopeRow(
                UUID.randomUUID(), DelegateType.GROUP_REF, null, verifiedGroup,
                DelegatedScopeType.SITE, siteId, null,
                List.of(DelegatedPermission.FLOOR_PLAN_MANAGE), null, null,
                DelegationState.ACTIVE, 0);
        DelegatedScopeRow denied = new DelegatedScopeRow(
                UUID.randomUUID(), DelegateType.GROUP_REF, null, ignoredGroup,
                DelegatedScopeType.SITE, siteId, null,
                List.of(DelegatedPermission.POLICY_MANAGE), null, null,
                DelegationState.ACTIVE, 0);
        when(repository.activeDelegatedScopes(eq(1L), any()))
                .thenReturn(List.of(allowed, denied));

        List<EffectiveDelegatedScope> result = service.effectiveDelegatedScopes(
                1L, 9L, verifiedGroup + ",Human Resources");

        assertThat(result).singleElement()
                .extracting(EffectiveDelegatedScope::delegationId)
                .isEqualTo(allowed.delegationId());
    }

    @Test
    void policyOverrideUpdateCannotMoveToAnotherSiteEvenWithAValidBody() {
        UUID overrideId = UUID.randomUUID();
        UUID originalSiteId = UUID.randomUUID();
        UUID targetSiteId = UUID.randomUUID();
        when(repository.policyOverride(1L, overrideId)).thenReturn(Optional.of(policy(
                overrideId, PolicyScopeType.SITE, originalSiteId,
                objectMapper.createObjectNode().put("bookingWindowDays", 14), 3)));
        PolicyOverrideRequest request = new PolicyOverrideRequest(
                PolicyScopeType.SITE, targetSiteId,
                objectMapper.createObjectNode().put("bookingWindowDays", 7),
                RuleState.ACTIVE, 3L);

        assertThatThrownBy(() -> service.savePolicyOverride(
                1L, 9L, overrideId, "corr",
                PolicyScopeType.SITE, targetSiteId, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT))
                .hasMessageContaining("scope is immutable");

        verify(repository, never()).updatePolicyOverride(anyLong(), anyLong(), any(), any());
    }

    @Test
    void sitePolicyOverrideCannotBePromotedToTenantScope() {
        UUID overrideId = UUID.randomUUID();
        UUID originalSiteId = UUID.randomUUID();
        when(repository.policyOverride(1L, overrideId)).thenReturn(Optional.of(policy(
                overrideId, PolicyScopeType.SITE, originalSiteId,
                objectMapper.createObjectNode().put("bookingWindowDays", 14), 1)));
        PolicyOverrideRequest request = new PolicyOverrideRequest(
                PolicyScopeType.TENANT, null,
                objectMapper.createObjectNode().put("bookingRetentionDays", 3650),
                RuleState.ACTIVE, 1L);

        assertThatThrownBy(() -> service.savePolicyOverride(
                1L, 9L, overrideId, "corr", null, null, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(repository, never()).updatePolicyOverride(anyLong(), anyLong(), any(), any());
    }

    @Test
    void policyMutationRejectsMismatchedQueryAndBodyScopes() {
        UUID querySiteId = UUID.randomUUID();
        UUID bodySiteId = UUID.randomUUID();
        PolicyOverrideRequest request = new PolicyOverrideRequest(
                PolicyScopeType.SITE, bodySiteId,
                objectMapper.createObjectNode().put("bookingWindowDays", 14),
                RuleState.ACTIVE, null);

        assertThatThrownBy(() -> service.savePolicyOverride(
                1L, 9L, null, "corr",
                PolicyScopeType.SITE, querySiteId, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("query must match");

        verifyNoInteractions(repository);
    }

    @Test
    void delegatedPolicyListIsFilteredToTheRequestedExactScope() {
        UUID siteId = UUID.randomUUID();
        UUID overrideId = UUID.randomUUID();
        when(repository.scopePath(1L, PolicyScopeType.SITE, siteId))
                .thenReturn(Optional.of(new ScopePath(null, siteId, null, null, null)));
        when(repository.policyOverrides(1L, PolicyScopeType.SITE, siteId))
                .thenReturn(List.of(policy(overrideId, PolicyScopeType.SITE, siteId,
                        objectMapper.createObjectNode().put("bookingWindowDays", 10), 2)));

        List<PolicyOverride> result = service.policyOverrides(
                1L, PolicyScopeType.SITE, siteId);

        assertThat(result).singleElement()
                .extracting(PolicyOverride::policyOverrideId)
                .isEqualTo(overrideId);
        verify(repository, never()).policyOverrides(1L);
    }

    @Test
    void policyListRejectsAStandaloneScopeIdentifier() {
        assertThatThrownBy(() -> service.policyOverrides(
                1L, null, UUID.randomUUID()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));

        verifyNoInteractions(repository);
    }

    @Test
    void unsupportedGroupTargetDelegationIsRejectedFailClosed() {
        DelegatedAdminScopeRequest request = new DelegatedAdminScopeRequest(
                DelegateType.USER, 9L, null,
                DelegatedScopeType.GROUP_REF, null, UUID.randomUUID(),
                List.of(DelegatedPermission.CATALOG_VIEW), null, null,
                DelegationState.ACTIVE, null);

        assertThatThrownBy(() -> service.saveDelegatedScope(
                1L, 7L, null, "corr", request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE))
                .hasMessageContaining("Only SITE");

        verifyNoInteractions(repository);
    }

    @Test
    void legacyGroupTargetDelegationIsNotReturnedAsEffective() {
        DelegatedScopeRow legacy = new DelegatedScopeRow(
                UUID.randomUUID(), DelegateType.USER, 9L, null,
                DelegatedScopeType.GROUP_REF, null, UUID.randomUUID(),
                List.of(DelegatedPermission.POLICY_MANAGE), null, null,
                DelegationState.ACTIVE, 0);
        when(repository.activeDelegatedScopes(eq(1L), any())).thenReturn(List.of(legacy));

        assertThat(service.effectiveDelegatedScopes(1L, 9L, null)).isEmpty();
    }

    private ObjectNode basePolicy() {
        return objectMapper.createObjectNode()
                .put("bookingWindowDays", 30)
                .put("maximumActiveBookings", 20)
                .put("minimumBookingMinutes", 30)
                .put("maximumBookingMinutes", 720)
                .put("maximumConsecutiveDays", 5)
                .put("workingDayStart", "08:00")
                .put("workingDayEnd", "20:00")
                .put("allowRecurring", false)
                .put("requireCheckIn", true)
                .put("checkInLeadMinutes", 30)
                .put("autoReleaseMinutes", 30)
                .put("allowAssignedDeskLending", false)
                .put("showColleagueNames", false)
                .put("bookingRetentionDays", 365);
    }

    private PolicyOverrideRow policy(
            UUID id, PolicyScopeType type, UUID scopeId, ObjectNode patch, long version) {
        return policy(id, type, scopeId, patch, RuleState.ACTIVE, version);
    }

    private PolicyOverrideRow policy(
            UUID id, PolicyScopeType type, UUID scopeId, ObjectNode patch,
            RuleState state, long version) {
        return new PolicyOverrideRow(id, type,
                type == PolicyScopeType.CAMPUS ? scopeId : null,
                type == PolicyScopeType.SITE ? scopeId : null,
                type == PolicyScopeType.FLOOR ? scopeId : null,
                type == PolicyScopeType.ZONE ? scopeId : null,
                type == PolicyScopeType.RESOURCE ? scopeId : null,
                patch, state, version);
    }

    private FloorPlanRevisionRow revision(
            UUID revisionId, UUID floorId, RevisionState state,
            long version, int placementCount) {
        OffsetDateTime now = OffsetDateTime.now();
        return new FloorPlanRevisionRow(revisionId, floorId, 2, UUID.randomUUID(), null,
                state, 1200, 760, null, null, null, null, null,
                "Layout", "a".repeat(64), placementCount,
                state == RevisionState.DRAFT ? null : now,
                state == RevisionState.DRAFT ? null : 7L,
                state == RevisionState.PUBLISHED ? now : null,
                state == RevisionState.PUBLISHED ? 7L : null, version);
    }

    private ZoneRow zone(UUID zoneId, UUID floorId, SpatialState state) {
        return new ZoneRow(zoneId, floorId, "ZONE-01", "구역", "Zone",
                ZoneType.WORK_AREA, objectMapper.createObjectNode(), state, 0, 2, 0);
    }

    private FloorPlanPlacementRequest placement(
            UUID resourceId, Long resourceVersion, UUID zoneId) {
        return new FloorPlanPlacementRequest(resourceId, resourceVersion, zoneId, null,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                0, objectMapper.createObjectNode());
    }
}
