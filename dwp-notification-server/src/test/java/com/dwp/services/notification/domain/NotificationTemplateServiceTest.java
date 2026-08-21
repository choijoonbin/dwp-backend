package com.dwp.services.notification.domain;

import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateContent;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDecisionRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDraftRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplatePreviewRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateRevision;
import com.dwp.services.notification.domain.NotificationTemplateRepository.ProviderVariant;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationTemplateServiceTest {

    private final NotificationDatabaseScope databaseScope = mock(NotificationDatabaseScope.class);
    private final NotificationTemplateRepository repository = mock(NotificationTemplateRepository.class);
    private final NotificationIdempotencyRepository idempotency =
            mock(NotificationIdempotencyRepository.class);
    private final AuditOutboxRecorder audit = mock(AuditOutboxRecorder.class);
    private final NotificationTemplateService service = new NotificationTemplateService(
            databaseScope, repository, idempotency, audit);
    private final NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
            42L, 17L, Set.of(), Set.of(), false, "dwp-gateway");
    private final UUID typeVersionId = UUID.randomUUID();
    private final ProviderVariant provider = new ProviderVariant(
            typeVersionId,
            "MESSAGING.DIRECT_MESSAGE",
            "messaging",
            "IN_APP",
            "ko-KR",
            new TemplateContent(
                    "{{senderName}}님의 메시지",
                    "{{messagePreview}}",
                    "{{messagePreview}}",
                    "대화 열기"),
            "{{senderName}} {{messagePreview}}");

    @BeforeEach
    void providerContractExists() {
        when(repository.providerVariant(42L, typeVersionId, "IN_APP", "ko-KR"))
                .thenReturn(Optional.of(provider));
    }

    @Test
    void rejectsUndeclaredVariablesBeforeACompanyDraftCanBeCreated() {
        TemplatePreviewRequest request = new TemplatePreviewRequest(
                typeVersionId,
                "IN_APP",
                "ko-KR",
                "{{unknownSecret}}",
                "{{messagePreview}}",
                "{{messagePreview}}",
                "대화 열기",
                Map.of());

        assertThatThrownBy(() -> service.preview(actor, request))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(
                                NotificationErrorCode.INVALID_INPUT));
    }

    @Test
    void rendersOnlyValidatedVariablesWithSyntheticPreviewData() {
        TemplatePreviewRequest request = new TemplatePreviewRequest(
                typeVersionId,
                "IN_APP",
                "ko-KR",
                "{{senderName}}님의 메시지",
                "{{messagePreview}}",
                "{{messagePreview}}",
                "대화 열기",
                Map.of("senderName", "박현우", "messagePreview", "회의가 시작됩니다."));

        var result = service.preview(actor, request);

        assertThat(result.rendered().title()).isEqualTo("박현우님의 메시지");
        assertThat(result.rendered().body()).isEqualTo("회의가 시작됩니다.");
        assertThat(result.variables()).containsExactly("senderName", "messagePreview");
    }

    @Test
    void preventsTheTemplateAuthorFromPublishingTheSameRevision() {
        UUID revisionId = UUID.randomUUID();
        TemplateRevision draft = revision(revisionId, actor.userId(), "DRAFT");
        when(idempotency.begin(any(), any(), any(), any()))
                .thenReturn(new Request(
                        "template:publish", "TENANT_NOTIFICATION_TEMPLATE_PUBLISH", "hash", null));
        when(repository.revision(42L, revisionId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.publish(
                actor,
                revisionId,
                new TemplateDecisionRequest("1", "내용과 개인정보 노출을 독립 검토했습니다."),
                "template:publish"))
                .isInstanceOfSatisfying(NotificationException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(NotificationErrorCode.FORBIDDEN));
    }

    @Test
    void recordsExtendedAuditEvidenceWhenCreatingACompanyDraft() {
        TemplateDraftRequest request = new TemplateDraftRequest(
                typeVersionId,
                "IN_APP",
                "ko-KR",
                "{{senderName}}님의 메시지",
                "{{messagePreview}}",
                "{{messagePreview}}",
                "대화 열기",
                "회사 표현 기준에 맞춘 메시지 문구입니다.",
                "0");
        TemplateRevision draft = revision(UUID.randomUUID(), actor.userId(), "DRAFT");
        when(repository.latestRevision(42L, typeVersionId, "IN_APP", "ko-KR"))
                .thenReturn(0L);
        when(idempotency.begin(any(), any(), any(), any()))
                .thenReturn(new Request(
                        "template:draft", "TENANT_NOTIFICATION_TEMPLATE_DRAFT", "hash", null));
        when(repository.createDraft(any(Long.class), any(Long.class), any(), any(Integer.class), any()))
                .thenReturn(draft);

        service.createDraft(actor, request, "template:draft");

        verify(audit).record(any());
        verify(databaseScope).applyWorker(42L);
    }

    private TemplateRevision revision(UUID revisionId, Long createdBy, String state) {
        return new TemplateRevision(
                revisionId,
                typeVersionId,
                "MESSAGING.DIRECT_MESSAGE",
                "messaging",
                "IN_APP",
                "ko-KR",
                state,
                1,
                provider.content(),
                "a".repeat(64),
                "회사 표현 기준에 맞춘 메시지 문구입니다.",
                createdBy,
                null,
                null,
                null,
                "1",
                Instant.now());
    }
}
