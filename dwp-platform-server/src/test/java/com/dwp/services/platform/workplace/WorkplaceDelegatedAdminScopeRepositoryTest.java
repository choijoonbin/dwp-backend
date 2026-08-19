package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminScopeRepository.SiteTargetType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkplaceDelegatedAdminScopeRepositoryTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Test
    @SuppressWarnings("unchecked")
    void policyOverrideScopeUsesThePhysicalScopeColumnsToResolveItsSite() {
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
        WorkplaceDelegatedAdminScopeRepository repository =
                new WorkplaceDelegatedAdminScopeRepository(jdbc);

        assertThat(repository.resolveSite(
                1L, SiteTargetType.POLICY_OVERRIDE, UUID.randomUUID())).isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(), any(MapSqlParameterSource.class), any(RowMapper.class));
        assertThat(sql.getValue())
                .contains("policy.site_id")
                .contains("policy.floor_id")
                .contains("policy.zone_id")
                .contains("policy.resource_id")
                .doesNotContain("policy.scope_id");
    }
}
