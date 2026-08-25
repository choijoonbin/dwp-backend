package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.dwp.services.platform.workspace.WorkspaceService;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeComposerServiceTest {
    @Mock private HomeComposerProposalRepository proposals;
    @Mock private HomeViewRevisionRepository revisions;
    @Mock private HomeViewService views;
    @Mock private HomePreferenceService preferenceService;
    @Mock private WorkspaceService workspace;
    @Mock private HomePersonalizationAccess access;
    @Mock private PlatformAuditService audit;
    @Mock private HomeCommandReceiptService commandReceipts;

    private ObjectMapper objectMapper;
    private HomeComposerService service;
    private HomeView view;
    private HomePreferenceDtos.HomeLayoutPayload layout;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new HomeComposerService(
                proposals, revisions, views, preferenceService, workspace,
                access, audit, objectMapper, commandReceipts);
        layout = new HomePreferenceDtos.HomeLayoutPayload(
                new HomePreferenceDtos.AppLayoutPayloadV1(
                        1, Map.of("work", List.of("dwp-work")), Map.of(), List.of()),
                "balanced",
                List.of(
                        new HomePreferenceDtos.WidgetPreference(
                                "command-rail", true, "large", "short"),
                        new HomePreferenceDtos.WidgetPreference(
                                "focus", true, "medium", "tall"),
                        new HomePreferenceDtos.WidgetPreference(
                                "schedule", true, "quarter", "standard")));
        view = HomeView.builder()
                .viewId(UUID.randomUUID()).tenantId(7L).userId(11L)
                .surfaceKey("workspace-home").viewKey("default").name("My home")
                .defaultView(true).schemaVersion(5)
                .layoutPayload(objectMapper.valueToTree(layout)).version(0L).build();
        lenient().when(views.layout(any())).thenAnswer(invocation -> objectMapper.treeToValue(
                invocation.getArgument(0), HomePreferenceDtos.HomeLayoutPayload.class));
        lenient().when(views.currentLayout(any(HomeView.class))).thenAnswer(invocation -> {
            HomeView current = invocation.getArgument(0);
            return objectMapper.treeToValue(
                    current.getLayoutPayload(), HomePreferenceDtos.HomeLayoutPayload.class);
        });
        lenient().when(preferenceService.normalizeForSurface(eq("workspace-home"), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(views.preserveClassicCompatibilitySnapshot(any(), any()))
                .thenAnswer(invocation -> {
                    HomePreferenceDtos.HomeLayoutPayload current = invocation.getArgument(0);
                    HomePreferenceDtos.HomeLayoutPayload requested = invocation.getArgument(1);
                    var fixed = current.widgets().stream()
                            .filter(widget -> "command-rail".equals(widget.widgetKey()))
                            .findFirst().orElse(null);
                    List<HomePreferenceDtos.WidgetPreference> widgets = new ArrayList<>(
                            requested.widgets().stream()
                                    .filter(widget -> !"command-rail".equals(widget.widgetKey()))
                                    .toList());
                    if (fixed != null) {
                        widgets.add(Math.min(current.widgets().indexOf(fixed), widgets.size()), fixed);
                    }
                    return new HomePreferenceDtos.HomeLayoutPayload(
                            requested.appLayout(), requested.presentation(), List.copyOf(widgets));
                });
        lenient().when(proposals.saveAndFlush(any(HomeComposerProposal.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(views.fingerprint(any())).thenReturn("a".repeat(64));
        lenient().when(views.externalFingerprint(anyString(), any(), anyString()))
                .thenReturn("b".repeat(64));
    }

    @Test
    void proposalPreviewDoesNotMutateTheViewAndApplyAndUndoRequireExplicitCommands() {
        UUID createCommand = UUID.randomUUID();
        HomeComposerDtos.CreateComposerProposalRequest request =
                new HomeComposerDtos.CreateComposerProposalRequest(
                        view.getViewId(), 0L, List.of("FOCUS_FIRST"),
                        List.of(new HomeComposerDtos.ComposerChange(
                                "MOVE_WIDGET", "schedule", null, 2, 1, null)));
        when(proposals.findByTenantIdAndUserIdAndCreationCommandId(
                7L, 11L, createCommand)).thenReturn(Optional.empty());
        when(views.requireOwnedForUpdate(7L, 11L, view.getViewId())).thenReturn(view);

        HomeComposerDtos.ComposerProposalResponse preview = service.create(
                7L, 11L, null, createCommand, "corr-create", request);

        assertThat(preview.state()).isEqualTo("PREVIEWED");
        assertThat(preview.proposedLayout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .containsExactly("command-rail", "schedule", "focus");
        verify(views, never()).applyExternalLayout(
                anyLong(), anyLong(), any(), anyLong(), any(), anyString(), anyString(),
                any(), anyString(), anyLong(), any());

        HomeComposerProposal proposal = proposals
                .findByTenantIdAndUserIdAndCreationCommandId(7L, 11L, createCommand)
                .orElse(null);
        // Mockito did not persist the repository lookup; rebuild the stored row from the response.
        proposal = HomeComposerProposal.builder()
                .proposalId(preview.proposalId()).tenantId(7L).userId(11L)
                .viewId(view.getViewId()).state("PREVIEWED").baseViewVersion(0L)
                .reasonCodes(objectMapper.valueToTree(preview.reasonCodes()))
                .changesPayload(objectMapper.valueToTree(preview.changes()))
                .warningsPayload(objectMapper.valueToTree(preview.warnings()))
                .beforeLayout(objectMapper.valueToTree(preview.beforeLayout()))
                .proposedLayout(objectMapper.valueToTree(preview.proposedLayout()))
                .creationCommandId(createCommand).requestFingerprint("a".repeat(64))
                .expiresAt(java.time.OffsetDateTime.now().plusMinutes(20)).build();

        UUID applyCommand = UUID.randomUUID();
        when(proposals.findByProposalIdAndTenantIdAndUserId(
                preview.proposalId(), 7L, 11L)).thenReturn(Optional.of(proposal));
        when(proposals.findOwnedForUpdate(preview.proposalId(), 7L, 11L))
                .thenReturn(Optional.of(proposal));
        when(revisions.findByTenantIdAndUserIdAndCommandId(7L, 11L, applyCommand))
                .thenReturn(Optional.empty());
        when(views.applyExternalLayout(
                eq(7L), eq(11L), eq(view.getViewId()), eq(0L), any(), eq("AI"),
                anyString(), eq(applyCommand), anyString(), eq(11L), eq("corr-apply")))
                .thenReturn(viewResponse(1L));
        HomeViewRevision appliedRevision = HomeViewRevision.builder()
                .revisionId(UUID.randomUUID()).viewId(view.getViewId())
                .revisionNumber(2L).source("AI").commandId(applyCommand)
                .requestFingerprint("b".repeat(64)).build();
        when(revisions.findTopByViewIdOrderByRevisionNumberDesc(view.getViewId()))
                .thenReturn(Optional.of(appliedRevision));

        HomeComposerDtos.ComposerProposalResponse applied = service.apply(
                7L, 11L, null, preview.proposalId(), applyCommand, "corr-apply",
                new HomeComposerDtos.ApplyComposerProposalRequest(0L));

        assertThat(applied.state()).isEqualTo("APPLIED");
        assertThat(applied.appliedRevisionId()).isEqualTo(appliedRevision.getRevisionId());

        UUID undoCommand = UUID.randomUUID();
        when(revisions.findByTenantIdAndUserIdAndCommandId(7L, 11L, undoCommand))
                .thenReturn(Optional.empty());
        when(views.applyExternalLayout(
                eq(7L), eq(11L), eq(view.getViewId()), eq(1L), any(), eq("UNDO"),
                anyString(), eq(undoCommand), anyString(), eq(11L), eq("corr-undo")))
                .thenReturn(viewResponse(2L));
        HomeViewRevision undoneRevision = HomeViewRevision.builder()
                .revisionId(UUID.randomUUID()).viewId(view.getViewId())
                .revisionNumber(3L).source("UNDO").commandId(undoCommand)
                .requestFingerprint("b".repeat(64)).build();
        when(revisions.findTopByViewIdOrderByRevisionNumberDesc(view.getViewId()))
                .thenReturn(Optional.of(undoneRevision));

        HomeComposerDtos.ComposerProposalResponse undone = service.undo(
                7L, 11L, preview.proposalId(), undoCommand, "corr-undo",
                new HomeComposerDtos.ApplyComposerProposalRequest(1L));

        assertThat(undone.state()).isEqualTo("UNDONE");
        assertThat(undone.undoneRevisionId()).isEqualTo(undoneRevision.getRevisionId());

        when(revisions.findByTenantIdAndUserIdAndCommandId(7L, 11L, undoCommand))
                .thenReturn(Optional.of(undoneRevision));
        assertThat(service.undo(
                7L, 11L, preview.proposalId(), undoCommand, "corr-undo-replay",
                new HomeComposerDtos.ApplyComposerProposalRequest(1L)).undoneRevisionId())
                .isEqualTo(undoneRevision.getRevisionId());
    }

    @Test
    void createRetryReplaysBeforeADeletedViewLookup() {
        UUID command = UUID.randomUUID();
        var request = new HomeComposerDtos.CreateComposerProposalRequest(
                view.getViewId(), 0L, List.of("FOCUS_FIRST"),
                List.of(new HomeComposerDtos.ComposerChange(
                        "MOVE_WIDGET", "schedule", null, 2, 1, null)));
        var original = proposalResponse(UUID.randomUUID(), "PREVIEWED", null, null);
        when(commandReceipts.replay(
                7L, 11L, command, "CREATE_PROPOSAL", view.getViewId().toString(),
                "a".repeat(64), HomeComposerDtos.ComposerProposalResponse.class))
                .thenReturn(original);

        assertThat(service.create(7L, 11L, null, command, "retry", request))
                .isEqualTo(original);
        verify(views, never()).lockPersonalizationScopeForView(any(), any(), any());
        verify(views, never()).requireOwnedForUpdate(any(), any(), any());
    }

    @Test
    void applyRetryReplaysBeforeADeletedViewLookup() {
        UUID proposalId = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        HomeComposerProposal proposal = proposal(
                proposalId, "PREVIEWED", null, null, null);
        var original = proposalResponse(
                proposalId, "APPLIED", UUID.randomUUID(), null);
        when(proposals.findByProposalIdAndTenantIdAndUserId(proposalId, 7L, 11L))
                .thenReturn(Optional.of(proposal));
        when(commandReceipts.replay(
                7L, 11L, command, "APPLY_PROPOSAL", proposalId.toString(),
                "b".repeat(64), HomeComposerDtos.ComposerProposalResponse.class))
                .thenReturn(original);

        assertThat(service.apply(
                7L, 11L, null, proposalId, command, "retry",
                new HomeComposerDtos.ApplyComposerProposalRequest(0L)))
                .isEqualTo(original);
        verify(views, never()).lockPersonalizationScopeForView(any(), any(), any());
        verify(views, never()).requireOwnedForUpdate(any(), any(), any());
    }

    @Test
    void undoRetryReplaysBeforeADeletedViewLookup() {
        UUID proposalId = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        UUID appliedRevision = UUID.randomUUID();
        HomeComposerProposal proposal = proposal(
                proposalId, "APPLIED", appliedRevision, 1L, null);
        var original = proposalResponse(
                proposalId, "UNDONE", appliedRevision, UUID.randomUUID());
        when(proposals.findByProposalIdAndTenantIdAndUserId(proposalId, 7L, 11L))
                .thenReturn(Optional.of(proposal));
        when(commandReceipts.replay(
                7L, 11L, command, "UNDO_PROPOSAL", proposalId.toString(),
                "b".repeat(64), HomeComposerDtos.ComposerProposalResponse.class))
                .thenReturn(original);

        assertThat(service.undo(
                7L, 11L, proposalId, command, "retry",
                new HomeComposerDtos.ApplyComposerProposalRequest(1L)))
                .isEqualTo(original);
        verify(views, never()).lockPersonalizationScopeForView(any(), any(), any());
        verify(views, never()).requireOwnedForUpdate(any(), any(), any());
    }

    @Test
    void rejectsChangesToManagedFlowZonesBeforeSavingAProposal() {
        UUID command = UUID.randomUUID();
        when(proposals.findByTenantIdAndUserIdAndCreationCommandId(7L, 11L, command))
                .thenReturn(Optional.empty());
        when(views.requireOwnedForUpdate(7L, 11L, view.getViewId())).thenReturn(view);
        HomeComposerDtos.CreateComposerProposalRequest request =
                new HomeComposerDtos.CreateComposerProposalRequest(
                        view.getViewId(), 0L, List.of("HIDE_NOW"),
                        List.of(new HomeComposerDtos.ComposerChange(
                                "HIDE_WIDGET", "command-rail", null, null, null, null)));

        assertThatThrownBy(() -> service.create(
                7L, 11L, null, command, null, request))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.INVALID_INPUT_VALUE));
        verify(views, never()).applyExternalLayout(
                anyLong(), anyLong(), any(), anyLong(), any(), anyString(), anyString(),
                any(), anyString(), anyLong(), any());
    }

    @Test
    void canRepinAnEntitledAppThatIsCurrentlyHidden() {
        HomePreferenceDtos.HomeLayoutPayload hiddenLayout =
                new HomePreferenceDtos.HomeLayoutPayload(
                        new HomePreferenceDtos.AppLayoutPayloadV1(
                                1, Map.of("work", List.of()), Map.of(), List.of("dwp-hidden")),
                        "balanced", layout.widgets());
        view.setLayoutPayload(objectMapper.valueToTree(hiddenLayout));
        UUID command = UUID.randomUUID();
        when(proposals.findByTenantIdAndUserIdAndCreationCommandId(7L, 11L, command))
                .thenReturn(Optional.empty());
        when(views.requireOwnedForUpdate(7L, 11L, view.getViewId())).thenReturn(view);
        when(workspace.apps(7L, 11L, "APP.APPS:VIEW", "en"))
                .thenReturn(List.of(workspaceApp("dwp-hidden")));

        HomeComposerDtos.ComposerProposalResponse preview = service.create(
                7L, 11L, "APP.APPS:VIEW", command, "corr",
                new HomeComposerDtos.CreateComposerProposalRequest(
                        view.getViewId(), 0L, List.of("REPINS_HIDDEN_APP"),
                        List.of(new HomeComposerDtos.ComposerChange(
                                "PIN_APP", null, "dwp-hidden", null, null, "work"))));

        assertThat(preview.proposedLayout().appLayout().hiddenAppIds()).isEmpty();
        assertThat(preview.proposedLayout().appLayout().groups().get("work"))
                .containsExactly("dwp-hidden");
    }

    @Test
    void previewAndApplyNormalizationPreserveAnAbsentClassicCommandRail() {
        HomePreferenceDtos.HomeLayoutPayload legacyWithoutRail =
                new HomePreferenceDtos.HomeLayoutPayload(
                        layout.appLayout(), layout.presentation(),
                        layout.widgets().stream()
                                .filter(widget -> !"command-rail".equals(widget.widgetKey()))
                                .toList());
        view.setLayoutPayload(objectMapper.valueToTree(legacyWithoutRail));
        UUID command = UUID.randomUUID();
        when(proposals.findByTenantIdAndUserIdAndCreationCommandId(7L, 11L, command))
                .thenReturn(Optional.empty());
        when(views.requireOwnedForUpdate(7L, 11L, view.getViewId())).thenReturn(view);
        when(preferenceService.normalizeForSurface(eq("workspace-home"), any()))
                .thenAnswer(invocation -> {
                    HomePreferenceDtos.HomeLayoutPayload candidate = invocation.getArgument(1);
                    List<HomePreferenceDtos.WidgetPreference> widgets =
                            new ArrayList<>(candidate.widgets());
                    widgets.addFirst(new HomePreferenceDtos.WidgetPreference(
                            "command-rail", true, "large", "short"));
                    return new HomePreferenceDtos.HomeLayoutPayload(
                            candidate.appLayout(), candidate.presentation(), List.copyOf(widgets));
                });

        var preview = service.create(
                7L, 11L, null, command, "corr",
                new HomeComposerDtos.CreateComposerProposalRequest(
                        view.getViewId(), 0L, List.of("LEGACY_COMPATIBILITY"),
                        List.of(new HomeComposerDtos.ComposerChange(
                                "MOVE_WIDGET", "schedule", null, 1, 0, null))));

        assertThat(preview.proposedLayout().widgets())
                .extracting(HomePreferenceDtos.WidgetPreference::widgetKey)
                .doesNotContain("command-rail");
    }

    @Test
    void rejectsAnIdempotencyReplayFromAnotherProposalOrOperation() {
        UUID proposalId = UUID.randomUUID();
        UUID appliedRevisionId = UUID.randomUUID();
        UUID command = UUID.randomUUID();
        HomeComposerProposal proposal = HomeComposerProposal.builder()
                .proposalId(proposalId).tenantId(7L).userId(11L)
                .viewId(view.getViewId()).state("APPLIED").baseViewVersion(0L)
                .reasonCodes(objectMapper.valueToTree(List.of("FOCUS_FIRST")))
                .changesPayload(objectMapper.valueToTree(List.of(
                        new HomeComposerDtos.ComposerChange(
                                "MOVE_WIDGET", "schedule", null, 2, 1, null))))
                .warningsPayload(objectMapper.createArrayNode())
                .beforeLayout(objectMapper.valueToTree(layout))
                .proposedLayout(objectMapper.valueToTree(layout))
                .creationCommandId(UUID.randomUUID()).requestFingerprint("a".repeat(64))
                .appliedRevisionId(appliedRevisionId).appliedViewVersion(1L)
                .expiresAt(java.time.OffsetDateTime.now().plusMinutes(20)).build();
        HomeViewRevision unrelated = HomeViewRevision.builder()
                .revisionId(appliedRevisionId).viewId(view.getViewId())
                .source("TEMPLATE").commandId(command)
                .requestFingerprint("b".repeat(64)).build();
        when(proposals.findOwnedForUpdate(proposalId, 7L, 11L))
                .thenReturn(Optional.of(proposal));
        when(proposals.findByProposalIdAndTenantIdAndUserId(proposalId, 7L, 11L))
                .thenReturn(Optional.of(proposal));
        when(views.requireOwnedForUpdate(7L, 11L, view.getViewId())).thenReturn(view);
        when(revisions.findByTenantIdAndUserIdAndCommandId(7L, 11L, command))
                .thenReturn(Optional.of(unrelated));

        assertThatThrownBy(() -> service.apply(
                7L, 11L, null, proposalId, command, "corr",
                new HomeComposerDtos.ApplyComposerProposalRequest(0L)))
                .isInstanceOfSatisfying(BaseException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ErrorCode.RESOURCE_CONFLICT));
    }

    private WorkspaceDtos.WorkspaceApp workspaceApp(String id) {
        return new WorkspaceDtos.WorkspaceApp(
                id, id, null, null, "WORK", "ROUTE", "/apps/" + id,
                "app", "APP.APPS", "HEALTHY", false, null,
                0L, 0L, "AVAILABLE", null, null, null, null);
    }

    private HomeComposerProposal proposal(
            UUID proposalId,
            String state,
            UUID appliedRevisionId,
            Long appliedViewVersion,
            UUID undoneRevisionId) {
        return HomeComposerProposal.builder()
                .proposalId(proposalId).tenantId(7L).userId(11L)
                .viewId(view.getViewId()).state(state).baseViewVersion(0L)
                .reasonCodes(objectMapper.valueToTree(List.of("FOCUS_FIRST")))
                .changesPayload(objectMapper.valueToTree(List.of(
                        new HomeComposerDtos.ComposerChange(
                                "MOVE_WIDGET", "schedule", null, 2, 1, null))))
                .warningsPayload(objectMapper.createArrayNode())
                .beforeLayout(objectMapper.valueToTree(layout))
                .proposedLayout(objectMapper.valueToTree(layout))
                .creationCommandId(UUID.randomUUID()).requestFingerprint("a".repeat(64))
                .appliedRevisionId(appliedRevisionId).appliedViewVersion(appliedViewVersion)
                .undoneRevisionId(undoneRevisionId)
                .expiresAt(java.time.OffsetDateTime.now().plusMinutes(20)).build();
    }

    private HomeComposerDtos.ComposerProposalResponse proposalResponse(
            UUID proposalId, String state, UUID appliedRevisionId, UUID undoneRevisionId) {
        return new HomeComposerDtos.ComposerProposalResponse(
                proposalId, view.getViewId(), state, 0L, List.of("FOCUS_FIRST"),
                List.of(new HomeComposerDtos.ComposerChange(
                        "MOVE_WIDGET", "schedule", null, 2, 1, null)),
                List.of(), layout, layout,
                java.time.OffsetDateTime.now().plusMinutes(20),
                appliedRevisionId, undoneRevisionId, null, null);
    }

    private HomeViewDtos.HomeViewResponse viewResponse(long version) {
        return new HomeViewDtos.HomeViewResponse(
                view.getViewId(), view.getViewKey(), view.getSurfaceKey(), view.getName(),
                true, true, 5, layout, version, null, null, Map.of());
    }
}
