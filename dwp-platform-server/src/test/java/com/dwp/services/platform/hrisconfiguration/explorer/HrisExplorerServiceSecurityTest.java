package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerDtos.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HrisExplorerServiceSecurityTest {

    @Test
    void idempotencyDigestBindsPreconditionIdentityScopeAndAuthority() {
        AuthoritySnapshot base = snapshot();
        String original = HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                base, "\"pref-1\"", List.of("hris.explorer.team-time"));

        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                base, "\"pref-2\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                copy(base, base.contextScopeKey(), base.authorizationRevision(),
                        base.fieldPolicyRevision() + 1),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                originCopy(base, "psc-" + "e".repeat(64),
                        base.currentDecisionRevision()),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                originCopy(base, base.contextKey(),
                        "psr-" + "f".repeat(64)),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                copy(base, "hcm-scope-" + "c".repeat(40),
                        base.authorizationRevision(), base.fieldPolicyRevision()),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                copy(base, base.contextScopeKey(),
                        base.authorizationRevision() + 1, base.fieldPolicyRevision()),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                identityCopy(base, 43, base.subjectPrincipalPublicId(),
                        base.populationScopeDigest()),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                identityCopy(base, base.tenantId(), UUID.randomUUID(),
                        base.populationScopeDigest()),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                identityCopy(base, base.tenantId(),
                        base.subjectPrincipalPublicId(), "d".repeat(64)),
                "\"pref-1\"", List.of("hris.explorer.team-time")))
                .isNotEqualTo(original);
        assertThat(HrisExplorerService.commandDigest(
                JdbcHrisExplorerRepository.PREFERENCES_ACTION,
                base, "\"pref-1\"", List.of("hris.explorer.my-time")))
                .isNotEqualTo(original);
    }

    @Test
    void callerSelectedHistoricalAsOfCannotMakeExpiredAuthorityFresh() {
        HrisExplorerService service = service();
        AuthoritySnapshot expired = expiredSnapshot();

        assertThatThrownBy(() -> service.explorer(
                expired, NOW.minusDays(1), Freshness.FRESH_ONLY,
                null, null, 100))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertThatThrownBy(() -> service.explorer(
                expired, NOW.minusDays(1), Freshness.ALLOW_STALE,
                null, null, 100))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void mutationsRequireAuthorityFreshAtServerExecutionTime() {
        HrisExplorerService service = service();
        AuthoritySnapshot expired = expiredSnapshot();

        assertThatThrownBy(() -> service.replacePreferences(
                expired, "idempotency-key-0001", "\"pref-1\"", null,
                new ExplorerPreferenceCommand(List.of())))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
        assertThatThrownBy(() -> service.recordRecent(
                expired, "idempotency-key-0002", null,
                new ExplorerRecentCommand("hris.explorer.team-time")))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void explorerReceiptReadRequiresCurrentOriginatingAuthority() {
        HrisExplorerService service = service();

        assertThatThrownBy(() -> service.explorerReceipt(
                expiredSnapshot(), UUID.randomUUID()))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE));
    }

    @Test
    void emptyResolvedAuthorityIsForbiddenButAuthorizedSearchMayHaveNoMatches() {
        AuthoritySnapshot value = snapshot();
        AuthoritySnapshot empty = new AuthoritySnapshot(
                value.tenantId(), value.subjectPrincipalPublicId(),
                value.currentDecisionRevision(), value.contextKey(),
                value.contextScopeKey(), value.purposeCode(),
                value.populationScopeDigest(), value.fieldPolicyRevision(),
                value.authorizationRevision(), value.entitlementRevision(),
                value.projectionRevision(), value.freshUntil(), Map.of());

        assertThatThrownBy(() -> service().explorer(
                empty, NOW, Freshness.FRESH_ONLY, null, null, 100))
                .isInstanceOfSatisfying(BaseException.class,
                        failure -> assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));

        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        when(repository.readAndPurge(
                value.tenantId(), value.subjectPrincipalPublicId(),
                value.allowedRouteDecisions().keySet(), NOW))
                .thenReturn(new HrisExplorerRepository.ExplorerState(
                        1, List.of(), List.of()));
        HrisExplorerProviderCoordinator coordinator =
                new HrisExplorerProviderCoordinator(
                        List.of(), new HrisExplorerRuntimeControls(
                        java.time.Duration.ofMillis(100),
                        java.time.Duration.ofMinutes(5), 5));
        try {
            HrisExplorerService.ExplorerResult result = new HrisExplorerService(
                    repository, coordinator,
                    Clock.fixed(NOW.toInstant(), ZoneOffset.UTC)).explorer(
                    value, NOW, Freshness.FRESH_ONLY,
                    "no-catalog-route-matches-this", null, 100);
            assertThat(result.response().routes()).isEmpty();
        } finally {
            coordinator.close();
        }
    }

    private HrisExplorerService service() {
        return new HrisExplorerService(null, null,
                Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"), ZoneOffset.UTC));
    }

    private AuthoritySnapshot expiredSnapshot() {
        AuthoritySnapshot value = snapshot();
        return new AuthoritySnapshot(
                value.tenantId(), value.subjectPrincipalPublicId(),
                value.currentDecisionRevision(), value.contextKey(),
                value.contextScopeKey(), value.purposeCode(),
                value.populationScopeDigest(), value.fieldPolicyRevision(),
                value.authorizationRevision(), value.entitlementRevision(),
                value.projectionRevision(), NOW.minusSeconds(1),
                value.allowedRouteDecisions());
    }

    private AuthoritySnapshot copy(
            AuthoritySnapshot value, String scope,
            long authorizationRevision, long fieldPolicyRevision) {
        return new AuthoritySnapshot(
                value.tenantId(), value.subjectPrincipalPublicId(),
                value.currentDecisionRevision(), value.contextKey(), scope,
                value.purposeCode(), value.populationScopeDigest(),
                fieldPolicyRevision, authorizationRevision,
                value.entitlementRevision(), value.projectionRevision(),
                value.freshUntil(), value.allowedRouteDecisions());
    }

    private AuthoritySnapshot identityCopy(
            AuthoritySnapshot value, long tenantId, UUID subject,
            String populationDigest) {
        return new AuthoritySnapshot(
                tenantId, subject, value.currentDecisionRevision(),
                value.contextKey(), value.contextScopeKey(), value.purposeCode(),
                populationDigest, value.fieldPolicyRevision(),
                value.authorizationRevision(), value.entitlementRevision(),
                value.projectionRevision(), value.freshUntil(),
                value.allowedRouteDecisions());
    }

    private AuthoritySnapshot originCopy(
            AuthoritySnapshot value, String contextKey,
            String currentDecisionRevision) {
        return new AuthoritySnapshot(
                value.tenantId(), value.subjectPrincipalPublicId(),
                currentDecisionRevision, contextKey, value.contextScopeKey(),
                value.purposeCode(), value.populationScopeDigest(),
                value.fieldPolicyRevision(), value.authorizationRevision(),
                value.entitlementRevision(), value.projectionRevision(),
                value.freshUntil(), value.allowedRouteDecisions());
    }
}
