package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationRuntimeAdmissionRepositoryTest {

    @Test
    void mandatoryDeliveryCanOnlyComeFromPublishedPolicyState() throws Exception {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getBoolean("policy_present")).thenReturn(true);
        when(resultSet.getObject("policy_mandatory", Boolean.class)).thenReturn(true);
        when(resultSet.getObject("policy_enabled", Boolean.class)).thenReturn(false);
        when(resultSet.getObject("policy_user_overridable", Boolean.class)).thenReturn(false);
        when(resultSet.getString("policy_default_mode")).thenReturn("IMMEDIATE");
        when(jdbc.queryForObject(
                anyString(),
                any(MapSqlParameterSource.class),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any()))
                .thenAnswer(invocation -> invocation.<RowMapper<?>>getArgument(2)
                        .mapRow(resultSet, 0));
        NotificationRuntimeAdmissionRepository repository =
                new NotificationRuntimeAdmissionRepository(jdbc);

        assertThat(repository.inAppDeliveryEnabled(
                1L, 900018L, "messaging", "MESSAGING.DIRECT_MESSAGE"))
                .isFalse();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(
                sql.capture(),
                params.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any());
        assertThat(sql.getValue())
                .contains("SELECT mandatory FROM effective_policy")
                .doesNotContain("mandatoryReason");
        assertThat(params.getValue().hasValue("mandatoryReason")).isFalse();
    }
}
