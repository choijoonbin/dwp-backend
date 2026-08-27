package com.dwp.services.provider.support;

import com.dwp.services.provider.security.ProviderOperatorService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSupportEffectiveAuthorityQueryTest {

    @Test
    void reconciliationUsesOnlyTheSingletonControlRowAndDatabaseTime() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ProviderSupportSessionRepository repository =
                new ProviderSupportSessionRepository(jdbc);

        assertThat(repository.pulseAuthorityReconciliation()).isEqualTo(1);

        assertThat(jdbc.sql())
                .contains("SET authority_reconciled_at = statement_timestamp()")
                .contains("WHERE control_key = 'STANDARD_JIT'")
                .doesNotContain("CURRENT_TIMESTAMP")
                .doesNotContain("pg_advisory_xact_lock")
                .doesNotContain("prv_support_sessions");
    }

    @Test
    void activationRequiresAnActiveL3SupportPermissionCatalogEntry() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ProviderSupportSessionRepository repository =
                new ProviderSupportSessionRepository(jdbc);

        assertThat(repository.activateApprovedRequest(
                UUID.randomUUID(), 0, 17L, UUID.randomUUID(), "a".repeat(64)))
                .isEmpty();

        assertThat(jdbc.sql())
                .contains("JOIN prv_operator_permission_catalog permission_catalog")
                .contains("permission_catalog.lifecycle_state = 'ACTIVE'")
                .contains("permission_catalog.risk_tier = 'L3'")
                .contains("FOR SHARE OF operator, assignment, role, permission,")
                .contains("permission_catalog");
    }

    @Test
    void requestAuthorityPublishesSupportWriteOnlyFromTheActiveL3CatalogEntry() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        ProviderOperatorService service = new ProviderOperatorService(jdbc);

        assertThat(service.activeOperator(3L, 17L)).isEmpty();

        assertThat(jdbc.sql())
                .contains("permission_catalog.permission_code")
                .contains("permission_catalog.lifecycle_state = 'ACTIVE'")
                .contains("role_permission.permission_code <> 'SUPPORT_SESSION_WRITE'")
                .contains("permission_catalog.risk_tier = 'L3'");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;

        @Override
        public <T> List<T> query(String query, RowMapper<T> rowMapper, Object... arguments) {
            sql = query;
            return List.of();
        }

        @Override
        public int update(String query) {
            sql = query;
            return 1;
        }

        String sql() {
            return sql;
        }
    }
}
