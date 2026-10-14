package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApprovedCompensationSnapshotOwnerAuthorityTest {

    private static final String SET_TENANT =
            "SELECT set_config('dwp.tenant_id', CAST(:tenant AS VARCHAR), TRUE)";

    @Test
    void requiresTheExactAppliedTenantAndOneActiveOwnerTenant() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var authority = new ApprovedCompensationSnapshotOwnerAuthority(jdbc);
        when(jdbc.queryForObject(
                eq(SET_TENANT), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn("71");
        when(jdbc.queryForObject(
                startsWith("SELECT count(*)"), any(SqlParameterSource.class),
                eq(Integer.class)))
                .thenReturn(1);

        assertThatCode(() -> authority.requireActiveTenant(request()))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenTheTransactionLocalTenantWasNotAppliedExactly() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var authority = new ApprovedCompensationSnapshotOwnerAuthority(jdbc);
        when(jdbc.queryForObject(
                eq(SET_TENANT), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn("72");

        assertThatThrownBy(() -> authority.requireActiveTenant(request()))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
        verify(jdbc, never()).queryForObject(
                startsWith("SELECT count(*)"), any(SqlParameterSource.class),
                eq(Integer.class));
    }

    @Test
    void rejectsAnInactiveOwnerTenant() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var authority = new ApprovedCompensationSnapshotOwnerAuthority(jdbc);
        when(jdbc.queryForObject(
                eq(SET_TENANT), any(SqlParameterSource.class), eq(String.class)))
                .thenReturn("71");
        when(jdbc.queryForObject(
                startsWith("SELECT count(*)"), any(SqlParameterSource.class),
                eq(Integer.class)))
                .thenReturn(0);

        assertThatThrownBy(() -> authority.requireActiveTenant(request()))
                .isInstanceOfSatisfying(BaseException.class, failure ->
                        org.assertj.core.api.Assertions.assertThat(failure.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    private static ApprovedCompensationSnapshotRequestContext.VerifiedRequest request() {
        UUID snapshotId = UUID.fromString(
                "10000000-0000-4000-8000-000000000002");
        return new ApprovedCompensationSnapshotRequestContext.VerifiedRequest(
                ApprovedCompensationSnapshotIngressFilter.PATH_PREFIX + snapshotId,
                snapshotId, 4L, "a".repeat(64), 71L, 3L, 9L,
                UUID.fromString("10000000-0000-4000-8000-000000000008"),
                ApprovedCompensationPlanSnapshotProvider.CALLER_WORKLOAD,
                ApprovedCompensationPlanSnapshotProvider.AUDIENCE,
                ApprovedCompensationPlanSnapshotProvider.PURPOSE,
                ApprovedCompensationPlanSnapshotProvider.PROJECTION);
    }
}
