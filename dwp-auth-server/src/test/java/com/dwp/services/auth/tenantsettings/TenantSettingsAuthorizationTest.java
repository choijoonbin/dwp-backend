package com.dwp.services.auth.tenantsettings;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSettingsAuthorizationTest {

    @Test
    void rejectsAnActorWithoutTheExactPersistedResourcePermission() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), eq(Boolean.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        TenantSettingsAuthorization authorization = new TenantSettingsAuthorization(jdbc);

        assertThatThrownBy(() -> authorization.require(
                7L, 101L, TenantSettingsAuthorization.POLICY_RESOURCE, "PUBLISH"))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void groupAuthorityIsTenantScopedActiveAndUnexpiredWithDenyPrecedence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(
                anyString(), eq(Boolean.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        TenantSettingsAuthorization authorization = new TenantSettingsAuthorization(jdbc);

        assertThat(authorization.can(
                7L, 101L, TenantSettingsAuthorization.DIRECTORY_RESOURCE, "VIEW")).isTrue();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(
                sql.capture(), eq(Boolean.class),
                eq(7L), eq(101L),
                eq(7L), eq(101L), eq(7L), eq(101L), eq(7L),
                eq(TenantSettingsAuthorization.DIRECTORY_RESOURCE), eq("VIEW"));
        assertThat(sql.getValue())
                .contains("identity_plane = 'TENANT'")
                .contains("status = 'ACTIVE'")
                .contains("membership.tenant_id = ?")
                .contains("assignment.lifecycle_state = 'ACTIVE'")
                .contains("assignment.assignment_type = 'ACTIVE'")
                .contains("assignment.scope_type = 'TENANT'")
                .contains("assignment.valid_from <= CURRENT_TIMESTAMP")
                .contains("assignment.valid_to > CURRENT_TIMESTAMP")
                .contains("NOT EXISTS (SELECT 1 FROM matching WHERE effect = 'DENY')");
    }
}
