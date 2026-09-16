package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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

    @Test
    void restoresAHistoricalSnapshotAsAnExplicitDraftWithAuditEvidence() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        UUID templateId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        var currentLayout = new com.dwp.services.platform.home.preference.HomePreferenceDtos
                .HomeLayoutPayload(null, "balanced", List.of());
        var restoredLayout = new com.dwp.services.platform.home.preference.HomePreferenceDtos
                .HomeLayoutPayload(null, "compact", List.of());
        HomeTemplate template = HomeTemplate.builder()
                .templateId(templateId).tenantId(7L).templateKey("team-home")
                .name("Published home")
                .audiencePayload(objectMapper.valueToTree(
                        new HomeTemplateDtos.TemplateAudience("ALL", List.of())))
                .lifecycleState("PUBLISHED").schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(currentLayout))
                .publishedAt(OffsetDateTime.parse("2026-09-15T01:00:00Z"))
                .publishedBy(20L).version(6L).build();
        HomeTemplateDtos.HomeTemplateSnapshot historical =
                new HomeTemplateDtos.HomeTemplateSnapshot(
                        "Restored team home",
                        new HomeTemplateDtos.TemplateAudience("ROLE", List.of("MANAGER")),
                        "PUBLISHED", 5, restoredLayout, 2L,
                        OffsetDateTime.parse("2026-09-01T01:00:00Z"), 19L);
        HomeTemplateRevision revision = HomeTemplateRevision.builder()
                .templateRevisionId(revisionId).templateId(templateId).tenantId(7L)
                .revisionNumber(2L).snapshot(objectMapper.valueToTree(historical))
                .source("PUBLISH").createdAt(OffsetDateTime.now()).createdBy(19L).build();

        when(views.fingerprint(any())).thenReturn("a".repeat(64));
        when(templates.findOwnedForUpdate(templateId, 7L)).thenReturn(java.util.Optional.of(template));
        when(revisions.findByTemplateRevisionIdAndTemplateIdAndTenantId(
                revisionId, templateId, 7L)).thenReturn(java.util.Optional.of(revision));
        when(preferenceService.normalizeForSurface("workspace-home", restoredLayout))
                .thenReturn(restoredLayout);
        when(views.layout(any())).thenReturn(restoredLayout);
        when(templates.saveAndFlush(template)).thenReturn(template);
        when(revisions.findTopByTemplateIdOrderByRevisionNumberDesc(templateId))
                .thenReturn(java.util.Optional.of(revision));
        when(revisions.saveAndFlush(any(HomeTemplateRevision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.restore(
                7L, 21L, "ADMIN.HOME_TEMPLATE:MANAGE", templateId, revisionId,
                commandId, "wave5", 6L);

        assertThat(result.name()).isEqualTo("Restored team home");
        assertThat(result.audience().values()).containsExactly("MANAGER");
        assertThat(result.layout()).isEqualTo(restoredLayout);
        assertThat(result.lifecycle()).isEqualTo("DRAFT");
        assertThat(result.publishedAt()).isNull();
        assertThat(result.publishedBy()).isNull();
        verify(audit).success(eq(7L), eq(21L), eq("home-template.revision-restored"),
                eq("HOME_TEMPLATE"), eq(templateId.toString()), eq("wave5"), any(), any());
        verify(receipts).record(eq(7L), eq(21L), eq(commandId), eq("RESTORE_TEMPLATE"),
                eq(templateId.toString()), eq("a".repeat(64)), eq(result));
        verify(revisions).findByTemplateRevisionIdAndTemplateIdAndTenantId(
                revisionId, templateId, 7L);
    }

    @Test
    void restoreRequiresManagePermissionBeforeReadingOrLockingState() {
        doThrow(new BaseException(ErrorCode.FORBIDDEN))
                .when(access).requireTemplateManage(null);

        assertThatThrownBy(() -> service.restore(
                7L, 21L, null, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "wave5", 3L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));

        verify(views, never()).fingerprint(any());
        verify(scopeLock, never()).lock(any());
        verify(templates, never()).findOwnedForUpdate(any(), any());
        verify(revisions, never()).findByTemplateRevisionIdAndTemplateIdAndTenantId(
                any(), any(), any());
    }

    @Test
    void staleRestoreFailsBeforeHistoricalRevisionLookupOrMutation() {
        UUID templateId = UUID.randomUUID();
        HomeTemplate template = HomeTemplate.builder()
                .templateId(templateId).tenantId(7L).templateKey("team-home")
                .name("Current home").lifecycleState("PUBLISHED")
                .schemaVersion(5).version(8L).build();
        when(views.fingerprint(any())).thenReturn("b".repeat(64));
        when(templates.findOwnedForUpdate(templateId, 7L))
                .thenReturn(java.util.Optional.of(template));

        assertThatThrownBy(() -> service.restore(
                7L, 21L, "ADMIN.HOME_TEMPLATE:MANAGE", templateId, UUID.randomUUID(),
                UUID.randomUUID(), "wave5", 7L))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));

        verify(scopeLock).lock(7L);
        verify(revisions, never()).findByTemplateRevisionIdAndTemplateIdAndTenantId(
                any(), any(), any());
        verify(templates, never()).saveAndFlush(any());
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
