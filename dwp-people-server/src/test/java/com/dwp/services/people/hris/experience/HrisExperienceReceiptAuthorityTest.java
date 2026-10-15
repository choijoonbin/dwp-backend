package com.dwp.services.people.hris.experience;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HrisExperienceReceiptAuthorityTest {

    private static final long TENANT = 77L;
    private static final long USER = 19L;
    private static final UUID ACTOR = UUID.fromString(
            "00000000-0000-4000-8000-000000000077");
    private static final String ORIGIN_ROUTE =
            "route.hcm.operations.employee-change-request-create.action";
    private static final String ORIGIN_CAPABILITY = "hcm.personal.profile.update";

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HrisExperienceOriginReceiptAuthoritySource> sources =
            mock(ObjectProvider.class);

    @BeforeEach
    void context() {
        PeopleRequestContext.set(USER, TENANT, ACTOR, Set.of(), Set.of());
        var route = new HcmV3PepRegistry.RouteAuthority(
                HrisExperienceReceiptAuthority.ROUTE, "DATA", "full-operations", true,
                Set.of(), Set.of(), "PEP-HRM-014",
                HrisExperienceReceiptAuthority.CAPABILITY, "ACTIVE", "GET",
                "/api/people/v1/hris/command-receipts/{receiptId}", null);
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(route, "psr-" + "a".repeat(64),
                        OffsetDateTime.now().plusMinutes(5), "context", "self", "111"));
    }

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
    }

    @Test
    void requiresExactlyOneOriginatingActionAuthoritySource() {
        when(sources.orderedStream()).thenReturn(Stream.empty());
        var authority = new HrisExperienceReceiptAuthority(sources);

        assertOpaque(() -> authority.require(receipt(), policy()));
    }

    @Test
    void admitsExactCurrentOriginAndRejectsRevisionDrift() {
        var source = mock(HrisExperienceOriginReceiptAuthoritySource.class);
        when(sources.orderedStream()).thenAnswer(ignored -> Stream.of(source));
        var authority = new HrisExperienceReceiptAuthority(sources);
        var row = receipt();
        var exact = current(row.authorizationRevision());
        when(source.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(exact));

        assertThat(authority.require(row, policy()).self()).isTrue();

        when(source.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(current(row.authorizationRevision() + 1)));
        assertOpaque(() -> authority.require(row, policy()));

        when(source.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(current(
                        row.authorizationRevision(), "d".repeat(64))));
        assertOpaque(() -> authority.require(row, policy()));
    }

    @Test
    void convertsOriginAuthoritySourceFailureToOpaqueNotFound() {
        var source = mock(HrisExperienceOriginReceiptAuthoritySource.class);
        when(sources.orderedStream()).thenAnswer(ignored -> Stream.of(source));
        when(source.resolve(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("authority unavailable"));
        var authority = new HrisExperienceReceiptAuthority(sources);

        assertOpaque(() -> authority.require(receipt(), policy()));
    }

    @Test
    void rejectsEveryCurrentOriginAuthorityDimensionDriftOpaque() {
        var row = receipt();
        List<HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority> drift =
                List.of(
                        current(TENANT + 1, USER, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", false,
                                row.authorizationRevision()),
                        current(TENANT, USER + 1, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", false,
                                row.authorizationRevision()),
                        current(TENANT, USER, UUID.randomUUID(),
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", false,
                                row.authorizationRevision()),
                        current(TENANT, USER, ACTOR, "EMPLOYEE_CHANGE_SUBMIT",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", false,
                                row.authorizationRevision()),
                        new HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority(
                                TENANT, USER, ACTOR, "HRM_EMPLOYEE_CHANGE_CREATE",
                                "SUBMIT_EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_CHANGE_REQUEST",
                                "EMPLOYEE_SELF_SERVICE", ORIGIN_ROUTE, ORIGIN_CAPABILITY,
                                "POST", false, row.authorizationRevision(),
                                row.contextDigest(), selfScope()),
                        current(TENANT, USER, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE", "EMPLOYMENT_EVENT",
                                "EMPLOYEE_SELF_SERVICE", ORIGIN_ROUTE, ORIGIN_CAPABILITY,
                                "POST", false, row.authorizationRevision()),
                        current(TENANT, USER, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "TEAM_MANAGEMENT",
                                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", false,
                                row.authorizationRevision()),
                        current(TENANT, USER, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                "route.changed", ORIGIN_CAPABILITY, "POST", false,
                                row.authorizationRevision()),
                        current(TENANT, USER, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                ORIGIN_ROUTE, "hcm.changed", "POST", false,
                                row.authorizationRevision()),
                        current(TENANT, USER, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "GET", false,
                                row.authorizationRevision()),
                        current(TENANT, USER, ACTOR,
                                "HRM_EMPLOYEE_CHANGE_CREATE",
                                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", true,
                                row.authorizationRevision()),
                        current(row.authorizationRevision() + 1));

        for (var candidate : drift) {
            var source = mock(HrisExperienceOriginReceiptAuthoritySource.class);
            when(sources.orderedStream()).thenAnswer(ignored -> Stream.of(source));
            when(source.resolve(org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Optional.of(candidate));
            var authority = new HrisExperienceReceiptAuthority(sources);

            assertOpaque(() -> authority.require(row, policy()));
        }
    }

    private HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority current(
            long revision) {
        return current(revision, "c".repeat(64));
    }

    private HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority current(
            long revision,
            String contextDigest) {
        return current(TENANT, USER, ACTOR, "HRM_EMPLOYEE_CHANGE_CREATE",
                "EMPLOYEE_CHANGE_REQUEST", "EMPLOYEE_SELF_SERVICE",
                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", false, revision,
                contextDigest);
    }

    private HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority current(
            long tenant,
            long user,
            UUID actor,
            String command,
            String targetType,
            String purpose,
            String route,
            String capability,
            String method,
            boolean readOnly,
            long revision) {
        return current(tenant, user, actor, command, targetType, purpose, route,
                capability, method, readOnly, revision, "c".repeat(64));
    }

    private HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority current(
            long tenant,
            long user,
            UUID actor,
            String command,
            String targetType,
            String purpose,
            String route,
            String capability,
            String method,
            boolean readOnly,
            long revision,
            String contextDigest) {
        return new HrisExperienceOriginReceiptAuthoritySource.CurrentOriginAuthority(
                tenant, user, actor, command, "CREATE_EMPLOYEE_CHANGE_REQUEST",
                targetType, purpose, route, capability, method, readOnly, revision,
                contextDigest, selfScope());
    }

    private HrisExperienceOriginReceiptAuthoritySource.CurrentOriginScope selfScope() {
        return new HrisExperienceOriginReceiptAuthoritySource.CurrentOriginScope(
                null, "self:hcm-scope-" + "1".repeat(40),
                Set.of("DIRECTORY", "PERSON_PROFILE", "CONTACT"),
                "scope-" + "2".repeat(32), "hcm-scope-" + "1".repeat(40),
                "hcm-rel-current", "hcm-pop-current",
                OffsetDateTime.now().plusMinutes(4));
    }

    private HrisExperienceReceiptAuthority.OriginPolicy policy() {
        return new HrisExperienceReceiptAuthority.OriginPolicy(
                ORIGIN_ROUTE, ORIGIN_CAPABILITY, "POST", false);
    }

    private HrisExperienceCommandReceiptRepository.ReceiptRecord receipt() {
        return new HrisExperienceCommandReceiptRepository.ReceiptRecord(
                UUID.randomUUID(), TENANT, "HRM_EMPLOYEE_CHANGE_CREATE", "key",
                "a".repeat(64), ACTOR, "CREATE_EMPLOYEE_CHANGE_REQUEST", ACTOR,
                "b".repeat(64), 1L, "EMPLOYEE_SELF_SERVICE", 31L,
                "c".repeat(64), "EMPLOYEE_CHANGE_REQUEST", UUID.randomUUID(),
                0L, "RUNNING", null, null, UUID.randomUUID(), Instant.now());
    }

    private void assertOpaque(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }
}
