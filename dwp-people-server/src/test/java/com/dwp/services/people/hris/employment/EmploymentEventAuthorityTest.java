package com.dwp.services.people.hris.employment;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.people.security.HcmPepContext;
import com.dwp.services.people.security.HcmV3PepRegistry;
import com.dwp.services.people.security.PeopleRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmploymentEventAuthorityTest {

    private final EmploymentEventAuthority authority = new EmploymentEventAuthority();

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "clear");
    }

    @Test
    void admitsOnlyTheExactManageRouteAndCapabilityTuple() {
        actor();
        pep(EmploymentEventAuthority.CREATE_ROUTE,
                EmploymentEventAuthority.MANAGE_CAPABILITY, "POST", false);

        assertThat(authority.require("CREATE"))
                .isEqualTo(EmploymentEventAuthority.MANAGE_CAPABILITY);

        pep(EmploymentEventAuthority.CREATE_ROUTE,
                EmploymentEventAuthority.OPERATIONS_READ_CAPABILITY, "POST", false);
        assertThatThrownBy(() -> authority.require("CREATE"))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void deniesCrossTenantOrMissingTrustedIdentityWithoutRepositoryDisclosure() {
        PeopleRequestContext.set(19L, null, UUID.randomUUID(), Set.of(), Set.of());
        pep(EmploymentEventAuthority.CREATE_ROUTE,
                EmploymentEventAuthority.MANAGE_CAPABILITY, "POST", false);

        assertThatThrownBy(() -> authority.require("CREATE"))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
    }

    @Test
    void detailAndReceiptUseDedicatedDataBindingsAndReceiptCapability() {
        assertThat(EmploymentEventAuthority.EVENT_DETAIL_BINDING_ID).isEqualTo("PEP-HRM-015");
        assertThat(EmploymentEventAuthority.RECEIPT_BINDING_ID).isEqualTo("PEP-HRM-014");
        actor();
        pep(EmploymentEventAuthority.RECEIPT_ROUTE,
                EmploymentEventAuthority.RECEIPT_CAPABILITY, "GET", true);

        assertThat(authority.require("RECEIPT"))
                .isEqualTo(EmploymentEventAuthority.RECEIPT_CAPABILITY);

        pep(EmploymentEventAuthority.RECEIPT_ROUTE,
                EmploymentEventAuthority.OPERATIONS_READ_CAPABILITY, "GET", true);
        assertThatThrownBy(() -> authority.require("RECEIPT"))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void workplaceSnapshotRequiresExactCallerPurposeAndActiveTenantGrant() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        EmploymentSnapshotAuthority snapshots = new EmploymentSnapshotAuthority(jdbc);
        actor();

        assertThatThrownBy(() -> snapshots.require(
                "dwp-payroll-server", EmploymentSnapshotAuthority.PURPOSE))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));
        assertThatThrownBy(() -> snapshots.require(
                EmploymentSnapshotAuthority.CALLER, "HR_LIFECYCLE"))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));

        PeopleRequestContext.set(19L, null, UUID.randomUUID(), Set.of(), Set.of());
        assertThatThrownBy(() -> snapshots.require(
                EmploymentSnapshotAuthority.CALLER, EmploymentSnapshotAuthority.PURPOSE))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED));

        actor();
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                eq(String.class))).thenReturn("77");
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class),
                eq(Integer.class))).thenReturn(0, 1);
        assertThatThrownBy(() -> snapshots.require(
                EmploymentSnapshotAuthority.CALLER, EmploymentSnapshotAuthority.PURPOSE))
                .isInstanceOf(BaseException.class)
                .satisfies(error -> assertThat(((BaseException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));

        snapshots.require(
                EmploymentSnapshotAuthority.CALLER, EmploymentSnapshotAuthority.PURPOSE);
    }

    private void actor() {
        PeopleRequestContext.set(
                19L, 77L, UUID.randomUUID(), Set.of("HRIS_OPERATOR"), Set.of());
    }

    private void pep(
            String route,
            String capability,
            String method,
            boolean readOnly) {
        HcmV3PepRegistry.RouteAuthority routeAuthority =
                new HcmV3PepRegistry.RouteAuthority(
                        route, "ACTION", "full-operations", readOnly, Set.of(), Set.of(),
                        "binding:hrm-008", capability, "ACTIVE", method,
                        "/api/people/v1/hris/assignment-events", null);
        ReflectionTestUtils.invokeMethod(HcmPepContext.class, "set",
                new HcmPepContext.Evidence(
                        routeAuthority, "decision:hrm-008", OffsetDateTime.now().plusMinutes(5),
                        "context:hrm-008", "scope:hrm-008", "111"));
    }
}
