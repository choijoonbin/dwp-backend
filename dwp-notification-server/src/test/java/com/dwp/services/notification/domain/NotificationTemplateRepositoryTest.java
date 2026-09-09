package com.dwp.services.notification.domain;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationTemplateRepositoryTest {

    @Test
    void retireTransitionIsTenantVersionAndAuthorBound() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        NotificationTemplateRepository repository = new NotificationTemplateRepository(jdbc);
        UUID revisionId = UUID.randomUUID();

        assertThat(repository.retireDraft(42L, 17L, revisionId, 3)).isTrue();

        assertClosureSql(jdbc, revisionId, 17L, "created_by = :actorId");
    }

    @Test
    void rejectTransitionIsTenantVersionAndIndependentReviewerBound() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        NotificationTemplateRepository repository = new NotificationTemplateRepository(jdbc);
        UUID revisionId = UUID.randomUUID();

        assertThat(repository.rejectDraft(42L, 29L, revisionId, 3)).isTrue();

        assertClosureSql(jdbc, revisionId, 29L, "created_by <> :actorId");
    }

    private void assertClosureSql(
            NamedParameterJdbcTemplate jdbc,
            UUID revisionId,
            long actorId,
            String actorGuard) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> params =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue())
                .contains("tenant_id = :tenantId")
                .contains("template_revision_id = :revisionId")
                .contains("revision = :expectedRevision")
                .contains("state = 'DRAFT'")
                .contains(actorGuard);
        assertThat(params.getValue().getValue("tenantId")).isEqualTo(42L);
        assertThat(params.getValue().getValue("actorId")).isEqualTo(actorId);
        assertThat(params.getValue().getValue("revisionId")).isEqualTo(revisionId);
        assertThat(params.getValue().getValue("expectedRevision")).isEqualTo(3);
    }
}
