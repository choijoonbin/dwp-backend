package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.preference.HomePreferenceService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeTemplateServiceTest {
    @Mock private HomeTemplateRepository templates;
    @Mock private HomeViewService views;
    @Mock private HomePreferenceService preferenceService;
    @Mock private HomePersonalizationAccess access;
    @Mock private PlatformAuditService audit;
    @Mock private HomeTemplateRevisionRepository revisions;
    @Mock private HomeCommandReceiptService receipts;
    @Mock private HomeTemplateScopeLock scopeLock;

    private HomeTemplateService service;

    @BeforeEach
    void setUp() {
        service = new HomeTemplateService(
                templates, views, preferenceService, access, audit,
                new ObjectMapper().findAndRegisterModules(), revisions, receipts, scopeLock);
    }

    @Test
    void applyRetryReplaysBeforeLifecycleAudienceAndScopeChecks() {
        UUID templateId = UUID.randomUUID();
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var request = new HomeTemplateDtos.ApplyHomeTemplateRequest(viewId, 3L);
        var original = new HomeViewDtos.HomeViewResponse(
                viewId, "default", "workspace-home", "Original", true, true, 5,
                null, 4L, OffsetDateTime.parse("2026-08-21T05:00:00Z"),
                OffsetDateTime.parse("2026-08-21T05:01:00Z"), Map.of());
        when(views.fingerprint(any())).thenReturn("a".repeat(64));
        when(receipts.replay(
                7L, 11L, commandId, "APPLY_TEMPLATE", templateId.toString(),
                "a".repeat(64), HomeViewDtos.HomeViewResponse.class))
                .thenReturn(original);

        assertThat(service.apply(
                7L, 11L, "FORMER_ROLE", templateId, commandId, "corr", request))
                .isEqualTo(original);

        verify(views, never()).lockPersonalizationScopeForView(any(), any(), any());
        verify(templates, never()).findOwnedForUpdate(any(), any());
    }

    @Test
    void concurrentApplyRechecksReceiptAfterBothScopesAreSerialized() {
        UUID templateId = UUID.randomUUID();
        UUID viewId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var request = new HomeTemplateDtos.ApplyHomeTemplateRequest(viewId, 3L);
        var original = new HomeViewDtos.HomeViewResponse(
                viewId, "default", "workspace-home", "Original", true, true, 5,
                null, 4L, null, null, Map.of());
        when(views.fingerprint(any())).thenReturn("a".repeat(64));
        when(receipts.replay(
                7L, 11L, commandId, "APPLY_TEMPLATE", templateId.toString(),
                "a".repeat(64), HomeViewDtos.HomeViewResponse.class))
                .thenReturn(null, original);

        assertThat(service.apply(
                7L, 11L, null, templateId, commandId, null, request))
                .isEqualTo(original);

        verify(views).lockPersonalizationScopeForView(7L, 11L, viewId);
        verify(scopeLock).lock(7L);
        verify(templates, never()).findOwnedForUpdate(any(), any());
        verify(receipts, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void publishRetryReplaysBeforePolicyLifecycleAndTargetLookup() {
        UUID templateId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var original = new HomeTemplateDtos.HomeTemplateResponse(
                templateId, "team-home", "Original",
                new HomeTemplateDtos.TemplateAudience("ALL", List.of()),
                "PUBLISHED", 5, null, 4L, null, 11L, null);
        when(views.fingerprint(any())).thenReturn("a".repeat(64));
        when(receipts.replay(
                7L, 11L, commandId, "PUBLISH_TEMPLATE", templateId.toString(),
                "a".repeat(64), HomeTemplateDtos.HomeTemplateResponse.class))
                .thenReturn(original);

        assertThat(service.publish(
                7L, 11L, "ADMIN.HOME_TEMPLATE:MANAGE", templateId,
                commandId, "retry", 3L)).isEqualTo(original);

        verify(views, never()).requirePolicy(any(), any());
        verify(scopeLock, never()).lock(any());
        verify(templates, never()).findOwnedForUpdate(any(), any());
    }
}
