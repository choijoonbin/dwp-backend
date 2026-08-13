package com.dwp.services.platform.localization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalizationServiceTest {

    @Mock
    private LocalizationRepository repository;
    @Mock
    private PlatformAuditService auditService;

    private LocalizationService service;

    @BeforeEach
    void setUp() {
        service = new LocalizationService(repository, auditService, new ObjectMapper());
    }

    @Test
    void previewReportsFallbackUnknownKeysAndPlaceholderMismatch() {
        LocalizationDtos.Preview preview = service.preview(
                Map.of(
                        "home.greeting", "Hello {{name}}",
                        "home.empty", "Nothing here"),
                Map.of(
                        "home.greeting", "안녕하세요 {{user}}",
                        "home.unknown", "알 수 없음"));

        assertThat(preview.publishable()).isFalse();
        assertThat(preview.missingKeys()).containsExactly("home.empty");
        assertThat(preview.fallbackKeys()).containsExactly("home.empty");
        assertThat(preview.unknownKeys()).containsExactly("home.unknown");
        assertThat(preview.placeholderIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.key()).isEqualTo("home.greeting");
            assertThat(issue.expected()).containsExactly("name");
            assertThat(issue.actual()).containsExactly("user");
        });
        assertThat(preview.resolvedEntries().get("home.empty")).isEqualTo("Nothing here");
        assertThat(preview.completeness()).isEqualTo(50d);
    }

    @Test
    void submitBlocksAnIncompleteRevisionBeforeRepositoryMutation() {
        UUID revisionId = UUID.randomUUID();
        when(repository.requireRevision(1L, revisionId)).thenReturn(revision(
                revisionId, "DRAFT", 7L,
                Map.of("account.title", "Account {{name}}"), Map.of()));

        assertThatThrownBy(() -> service.submit(
                1L, 7L, "corr", revisionId,
                new LocalizationDtos.TransitionRequest("Ready for review", 0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATE));

        verify(repository, never()).submit(anyLong(), anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void submitAllowsCompleteContentWithMatchingPlaceholders() {
        UUID revisionId = UUID.randomUUID();
        LocalizationRepository.StoredRevision draft = revision(
                revisionId, "DRAFT", 7L,
                Map.of("account.title", "Account {{name}}"),
                Map.of("account.title", "{{name}} 계정"));
        LocalizationRepository.StoredRevision submitted = withState(draft, "IN_REVIEW", 1L, 7L);
        when(repository.requireRevision(1L, revisionId)).thenReturn(draft);
        when(repository.submit(1L, 7L, revisionId, 0L, "Ready for review"))
                .thenReturn(submitted);
        when(repository.decisions(1L, revisionId)).thenReturn(List.of());

        LocalizationDtos.Revision result = service.submit(
                1L, 7L, "corr", revisionId,
                new LocalizationDtos.TransitionRequest("Ready for review", 0L));

        assertThat(result.lifecycleState()).isEqualTo("IN_REVIEW");
        assertThat(result.preview().publishable()).isTrue();
        verify(auditService).success(
                anyLong(), anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void submitterCannotApproveTheirOwnRevision() {
        UUID revisionId = UUID.randomUUID();
        when(repository.requireRevision(1L, revisionId)).thenReturn(withState(
                revision(revisionId, "DRAFT", 7L,
                        Map.of("account.title", "Account"),
                        Map.of("account.title", "계정")),
                "IN_REVIEW", 1L, 7L));

        assertThatThrownBy(() -> service.decide(
                1L, 7L, "corr", revisionId,
                new LocalizationDtos.DecisionRequest(
                        "APPROVED", "Reviewed and approved", 1L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(repository, never()).decide(
                anyLong(), anyLong(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    void restoreCreatesANewDraftWithoutMutatingTheHistoricalRevision() {
        UUID sourceId = UUID.randomUUID();
        LocalizationRepository.StoredRevision source = withState(
                revision(sourceId, "DRAFT", 7L,
                        Map.of("account.title", "Account"),
                        Map.of("account.title", "계정")),
                "SUPERSEDED", 4L, 7L);
        LocalizationRepository.StoredRevision restored = new LocalizationRepository.StoredRevision(
                UUID.randomUUID(), source.bundleId(), 1L, source.bundleKey(),
                source.sourceLocale(), source.targetLocale(), 5L, sourceId,
                source.sourceEntries(), source.entries(), "DRAFT", "Restore stable copy",
                source.contentSha256(), null, null, null, null, null, null,
                0L, OffsetDateTime.now(), 9L, OffsetDateTime.now());
        when(repository.requireRevision(1L, sourceId)).thenReturn(source);
        when(repository.restore(
                anyLong(), anyLong(), any(), anyString(), anyString())).thenReturn(restored);
        when(repository.decisions(1L, restored.revisionId())).thenReturn(List.of());

        LocalizationDtos.Revision result = service.restore(
                1L, 9L, "corr", sourceId,
                new LocalizationDtos.RestoreRequest("Restore stable copy"));

        assertThat(result.lifecycleState()).isEqualTo("DRAFT");
        assertThat(result.basedOnRevisionId()).isEqualTo(sourceId);
        verify(repository).restore(
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(9L),
                org.mockito.ArgumentMatchers.eq(source),
                org.mockito.ArgumentMatchers.eq("Restore stable copy"),
                anyString());
    }

    private LocalizationRepository.StoredRevision revision(
            UUID revisionId,
            String state,
            Long actorId,
            Map<String, String> source,
            Map<String, String> entries) {
        return new LocalizationRepository.StoredRevision(
                revisionId, UUID.randomUUID(), 1L, "shell", "en", "ko", 1L,
                null, source, entries, state, "Initial translation", "a".repeat(64),
                null, null, null, null, null, null, 0L,
                OffsetDateTime.now(), actorId, OffsetDateTime.now());
    }

    private LocalizationRepository.StoredRevision withState(
            LocalizationRepository.StoredRevision source,
            String state,
            long version,
            Long submittedBy) {
        return new LocalizationRepository.StoredRevision(
                source.revisionId(), source.bundleId(), source.tenantId(), source.bundleKey(),
                source.sourceLocale(), source.targetLocale(), source.revisionNumber(),
                source.basedOnRevisionId(), source.sourceEntries(), source.entries(), state,
                source.changeSummary(), source.contentSha256(), submittedBy, OffsetDateTime.now(),
                null, null, null, null, version, source.createdAt(), source.createdBy(),
                OffsetDateTime.now());
    }
}
