package com.dwp.services.approval.auditrecords;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.approval.auditrecords.ApprovalAuditApiDtos.*;
import static com.dwp.services.approval.auditrecords.ApprovalAuditModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ApprovalAuditCommandFacadeTest {
    private static final String REVISION = "psr-" + "a".repeat(64);

    private final ApprovalAuditService service = mock(ApprovalAuditService.class);
    private final ApprovalAuditHttpAuthority authority =
            mock(ApprovalAuditHttpAuthority.class);
    private final ApprovalAuditCommandGuard guard = mock(ApprovalAuditCommandGuard.class);
    private final ApprovalAuditCommandFacade facade =
            new ApprovalAuditCommandFacade(service, authority, guard);

    @Test
    void currentAuthorityIsRequiredBeforeCreateCasIsEvaluated() {
        when(authority.command(anyString()))
                .thenThrow(new BaseException(ErrorCode.FORBIDDEN));
        SavedViewCreate input = new SavedViewCreate(
                UUID.randomUUID(), "Denied decisions", Visibility.PERSONAL,
                new SearchInput(
                        Instant.parse("2026-09-01T00:00:00Z"),
                        Instant.parse("2026-09-02T00:00:00Z"),
                        Set.of(), Set.of("DENIED"), null, null,
                        50, null, null));

        assertThatThrownBy(() -> facade.createSavedView(
                input, 1, headers()))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
        verifyNoInteractions(service, guard);
    }

    private ApprovalStepUpHeaders headers() {
        return ApprovalStepUpHeaders.of(
                "signed.challenge", "saved-view-100", REVISION, 1L);
    }
}
