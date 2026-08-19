package com.dwp.services.platform.workplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WorkplacePrivacyRepositoryTest {

    @Mock
    private JdbcTemplate jdbc;

    @Test
    void bookingAnonymizationClearsIdentityAndIdempotencyLinkage() {
        WorkplacePrivacyRepository repository = new WorkplacePrivacyRepository(jdbc);

        repository.anonymizeExpired(100);

        verify(jdbc).update(argThat(sql -> sql.contains("created_by = NULL")
                && sql.contains("idempotency_key = NULL")
                && sql.contains("request_fingerprint = NULL")
                && sql.contains("FOR UPDATE SKIP LOCKED")), eq(100));
    }

    @Test
    void auditRetentionPurgesProjectionAndSourceUnderExplicitBypass() {
        WorkplacePrivacyRepository repository = new WorkplacePrivacyRepository(jdbc);
        OffsetDateTime cutoff = OffsetDateTime.now().minusYears(7);

        repository.purgeExpiredAuditReplicas(100, cutoff);

        verify(jdbc).execute(argThat((String sql) ->
                sql.contains("dwp.audit_retention_bypass")));
        verify(jdbc).update(argThat(sql -> sql.contains("deleted_projection")
                && sql.contains("DELETE FROM sys_platform_audit_events")
                && sql.contains("DELETE FROM wp_audit_events")
                && sql.contains("booking.legal_hold = TRUE")
                && sql.contains("release.legal_hold = TRUE")), eq(cutoff), eq(100));
    }
}
