package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.MeetingFollowupAuthorityDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingFollowupAuthorityServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");
    private final ProductSurfaceAuthorityService authority =
            mock(ProductSurfaceAuthorityService.class);
    private final MeetingFollowupAuthorityService service = new MeetingFollowupAuthorityService(
            authority, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void allowsCreateOnlyFromTheExactCurrentMeetingCapability() {
        when(authority.evaluate(any())).thenReturn(allowed(
                "meetings.work.meeting.create", "APP.MEETINGS:CREATE", false));

        MeetingFollowupAuthorityDtos.AuthorityResult result = service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L));

        assertThat(result.allowed()).isTrue();
        assertThat(result.denial()).isNull();
        assertThat(result.source()).isEqualTo(request(
                MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L).source());
        assertThat(result.validUntil()).isEqualTo(OffsetDateTime.ofInstant(
                NOW.plusSeconds(60), ZoneOffset.UTC));
    }

    @Test
    void deniesAReadGrantOrReadOnlyGrantForCreate() {
        when(authority.evaluate(any()))
                .thenReturn(allowed(
                        "meetings.work.meetings.read", "APP.MEETINGS:VIEW", true))
                .thenReturn(allowed(
                        "meetings.work.meeting.create", "APP.MEETINGS:CREATE", true));

        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.ACTION_NOT_AUTHORIZED);
        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.ACTION_NOT_AUTHORIZED);
    }

    @Test
    void mapsScopeAndIdentityPlaneDecisionsWithoutLeakingTheAuthorityProjection() {
        when(authority.evaluate(any()))
                .thenReturn(denied(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID))
                .thenReturn(new ProductSurfaceAuthorityDtos.AuthorityResult(
                        ProductSurfaceAuthorityDtos.Decision.ALLOWED, "ALLOWED", "auth-1",
                        "policy-1", "context-1", "meetings", "meetings.management",
                        "management", ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                        ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT, "APP.MEETINGS",
                        List.of(), List.of(), null, false, true, null, null, null, null,
                        OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC),
                        "evidence-1"));

        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.READ, null, null)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.SCOPE_FORBIDDEN);
        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.READ, null, null)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.IDENTITY_PLANE_MISMATCH);
    }

    @Test
    void keepsReassignmentClosedUntilPeopleTargetEligibilityExists() {
        MeetingFollowupAuthorityDtos.AuthorityResult result = service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.REASSIGN, 99L, null));

        assertThat(result.denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.ACTION_NOT_AUTHORIZED);
        verify(authority, never()).evaluate(any());
    }

    @Test
    void failsClosedWhenTheAuthorityCannotResolve() {
        when(authority.evaluate(any())).thenThrow(new IllegalStateException("unavailable"));

        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.READ, null, null)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED);
    }

    @Test
    void reportsAnInactiveMeetingRegistryAsUnverifiedWithoutLegacyFallback() {
        when(authority.evaluate(any()))
                .thenReturn(denied(
                        ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        "PRODUCT_NOT_REGISTERED"))
                .thenReturn(denied(
                        ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        "SURFACE_NOT_REGISTERED"))
                .thenReturn(denied(
                        ProductSurfaceAuthorityDtos.Decision.SURFACE_DENIED,
                        "ENTITLEMENT_DENIED"));

        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED);
        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED);
        assertThat(service.evaluate(request(
                MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L)).denial())
                .isEqualTo(MeetingFollowupAuthorityDtos.Denial.AUTHORITY_REVOKED);
        verify(authority, org.mockito.Mockito.times(3)).evaluate(any());
    }

    @Test
    void distinguishesMissingOrExpiredAuthorityEvidenceFromAnAbsentGrant() {
        ProductSurfaceAuthorityDtos.AuthorityResult valid = allowed(
                "meetings.work.meeting.create", "APP.MEETINGS:CREATE", false);
        when(authority.evaluate(any()))
                .thenReturn(withEvidence(
                        valid, null, valid.policyRevision(), valid.revalidateAt(),
                        valid.evidenceRef()))
                .thenReturn(withEvidence(
                        valid, valid.authRevision(), "", valid.revalidateAt(),
                        valid.evidenceRef()))
                .thenReturn(withEvidence(
                        valid, valid.authRevision(), valid.policyRevision(),
                        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), valid.evidenceRef()))
                .thenReturn(withEvidence(
                        valid, valid.authRevision(), valid.policyRevision(),
                        valid.revalidateAt(), " "));

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(service.evaluate(request(
                    MeetingFollowupAuthorityDtos.Action.CREATE, null, 7L)).denial())
                    .isEqualTo(MeetingFollowupAuthorityDtos.Denial.AUTHORITY_UNVERIFIED);
        }
    }

    private MeetingFollowupAuthorityDtos.EvaluateRequest request(
            MeetingFollowupAuthorityDtos.Action action,
            Long target,
            Long version) {
        return new MeetingFollowupAuthorityDtos.EvaluateRequest(
                42L, 17L,
                new MeetingFollowupAuthorityDtos.Source(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        UUID.fromString("00000000-0000-0000-0000-000000000003")),
                action, target, version);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult allowed(
            String capability,
            String permission,
            boolean readOnly) {
        var grant = new ProductSurfaceAuthorityDtos.CapabilityGrant(
                capability, permission,
                ProductSurfaceAuthorityDtos.CapabilityAuthorityMode.PERMISSION_AND_RELATIONSHIP,
                List.of("predicate.meetings-self.v1"),
                ProductSurfaceAuthorityDtos.ResponsibilityRequirement.NOT_REQUIRED,
                null, List.of("self:17"), true, readOnly,
                ProductSurfaceAuthorityDtos.ActivationState.ACTIVE,
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC));
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.ALLOWED, "ALLOWED", "auth-1", "policy-1",
                "context-1", "meetings", "meetings.work", "work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT, "APP.MEETINGS",
                List.of(grant), List.of(), null, readOnly, true,
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC), null, null, null,
                OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC), "evidence-1");
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult denied(
            ProductSurfaceAuthorityDtos.Decision decision) {
        return denied(decision, decision.name());
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult denied(
            ProductSurfaceAuthorityDtos.Decision decision,
            String reasonCode) {
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                decision, reasonCode, "auth-1", "policy-1", null,
                "meetings", "meetings.work", null,
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL, null, null,
                List.of(), List.of(), null, true, true, null, null, null, null, null,
                "evidence-1");
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult withEvidence(
            ProductSurfaceAuthorityDtos.AuthorityResult original,
            String authRevision,
            String policyRevision,
            OffsetDateTime revalidateAt,
            String evidenceRef) {
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                original.decision(), original.reasonCode(), authRevision, policyRevision,
                original.contextKey(), original.productKey(), original.surfaceKey(),
                original.plane(), original.accessMode(), original.accessSource(),
                original.appResourceKey(), original.effectiveGrants(), original.scopes(),
                original.routeGrantRef(), original.effectiveReadOnly(),
                original.requiresProductEligibility(), original.validUntil(),
                original.expiredAt(), original.requiredAssurance(),
                original.requestPolicyRef(), revalidateAt, evidenceRef);
    }
}
