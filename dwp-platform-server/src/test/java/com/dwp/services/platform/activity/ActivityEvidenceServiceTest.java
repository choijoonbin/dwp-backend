package com.dwp.services.platform.activity;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActivityEvidenceServiceTest {
    private final ActivityEvidenceRepository repository = mock(ActivityEvidenceRepository.class);
    private final ActivityEvidenceService service = new ActivityEvidenceService(repository);
    private final UUID event = UUID.randomUUID();
    private final UUID audit = UUID.randomUUID();
    private static final String ACCESS = "APP.ACTIVITY:VIEW,APP.WORK:VIEW";

    @Test
    void linkedEvidenceDoesNotGrantAuditReadOrClaimCryptographicVerification() {
        row("a".repeat(64), "VERIFIED");
        var result = service.evidence(7L, 8L, ACCESS, event);
        assertThat(result.linkStatus()).isEqualTo("LINKED");
        assertThat(result.auditAccess()).isEqualTo("RESTRICTED");
        assertThat(result.recordHash()).isNull();
        assertThat(result.verifiedAt()).isNull();
        assertThat(result.integrityStatus()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void pendingCheckpointNeverBecomesVerifiedJustBecauseAnAuditRecordExists() {
        row("a".repeat(64), null);
        var result = service.evidence(7L, 8L, ACCESS + ",ADMIN.AUDIT_VIEW:VIEW", event);
        assertThat(result.integrityStatus()).isEqualTo("PENDING");
        assertThat(result.hashAlgorithm()).isEqualTo("SHA-256");
        assertThat(result.integrityScope()).isEqualTo("DAILY_CHECKPOINT_REPORTED");
    }

    @Test
    void reportsFailedCheckpointWithoutPromotingStoredHashToProof() {
        row("b".repeat(64), "FAILED");
        assertThat(service.evidence(7L, 8L, ACCESS + ",ADMIN.AUDIT_VIEW:VIEW", event).integrityStatus())
                .isEqualTo("FAILED");
    }

    @Test
    void revokedMissingCrossTenantAndDeletedEventsHaveTheSameNotFoundResult() {
        when(repository.evidence(anyLong(), anyLong(), anySet(), any(), anyBoolean()))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.evidence(7L, 8L, ACCESS, event)).isInstanceOf(BaseException.class);
    }

    @Test
    void missingActivityAndInvalidIdentityNeverReadTheRepository() {
        assertThatThrownBy(() -> service.evidence(7L, 8L, "APP.WORK:VIEW", event)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.sources(0L, 8L, ACCESS)).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> service.sources(7L, null, ACCESS)).isInstanceOf(BaseException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void agentEvidenceAlsoRequiresAskAccessBeforeReadingAudit() {
        assertThatThrownBy(() -> service.agentEvidence(7L, 8L, ACCESS, audit)).isInstanceOf(BaseException.class);
        verifyNoInteractions(repository);
        when(repository.agentEvidence(7L, 8L, audit)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.agentEvidence(7L, 8L, ACCESS + ",APP.ASK:VIEW", audit))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void absentConnectionIsAnEmptyObservationNotInventedHealthyStatus() {
        when(repository.sources(anyLong(), anyLong(), anySet(), any(), anyBoolean()))
                .thenReturn(List.of());
        var result = service.sources(7L, 8L, ACCESS);
        assertThat(result.sources()).isEmpty();
        assertThat(result.observedAt()).isNotNull();
    }

    private void row(String hash, String status) {
        when(repository.evidence(anyLong(), anyLong(), anySet(), any(), anyBoolean()))
                .thenReturn(Optional.of(
                new ActivityEvidenceRepository.AuditObservation(event, audit, hash, status,
                        status == null ? null : OffsetDateTime.now().minusMinutes(1))));
    }
}
