package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.dwp.services.platform.workspace.WorkspaceDtos;
import com.dwp.services.platform.workspace.WorkspaceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class HomeComposerService {
    private static final int MAX_ACTIVE_PROPOSALS_PER_VIEW = 20;
    private static final Set<String> LOCKED_WIDGETS = Set.of(
            "announcements", "now", "my-app-dock");
    private static final Set<String> WIDTHS = Set.of(
            "fifth", "quarter", "compact", "medium", "large", "full");
    private static final Set<String> DENSITIES = Set.of(
            "balanced", "expressive", "focused");

    private final HomeComposerProposalRepository proposals;
    private final HomeViewRevisionRepository revisions;
    private final HomeViewService views;
    private final HomePreferenceService preferenceService;
    private final WorkspaceService workspace;
    private final HomePersonalizationAccess access;
    private final PlatformAuditService audit;
    private final ObjectMapper objectMapper;
    private final HomeCommandReceiptService commandReceipts;

    public HomeComposerService(
            HomeComposerProposalRepository proposals,
            HomeViewRevisionRepository revisions,
            HomeViewService views,
            HomePreferenceService preferenceService,
            WorkspaceService workspace,
            HomePersonalizationAccess access,
            PlatformAuditService audit,
            ObjectMapper objectMapper,
            HomeCommandReceiptService commandReceipts) {
        this.proposals = proposals;
        this.revisions = revisions;
        this.views = views;
        this.preferenceService = preferenceService;
        this.workspace = workspace;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.commandReceipts = commandReceipts;
    }

    @Transactional
    public HomeComposerDtos.ComposerProposalResponse create(
            Long tenantId,
            Long userId,
            String permissions,
            UUID commandId,
            String correlationId,
            HomeComposerDtos.CreateComposerProposalRequest request) {
        access.requireComposer();
        validateProposalRequest(request);
        if (commandId == null) throw invalid("Idempotency-Key is required.");
        String fingerprint = views.fingerprint(Map.of(
                "operation", "CREATE_PROPOSAL",
                "viewId", request.viewId(),
                "request", request));
        HomeComposerDtos.ComposerProposalResponse receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "CREATE_PROPOSAL",
                request.viewId().toString(), fingerprint,
                HomeComposerDtos.ComposerProposalResponse.class);
        if (receiptReplay != null) return receiptReplay;
        views.lockPersonalizationScopeForView(tenantId, userId, request.viewId());
        receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "CREATE_PROPOSAL",
                request.viewId().toString(), fingerprint,
                HomeComposerDtos.ComposerProposalResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeComposerProposal replay = proposals
                .findByTenantIdAndUserIdAndCreationCommandId(tenantId, userId, commandId)
                .orElse(null);
        if (replay != null) {
            if (!fingerprint.equals(replay.getRequestFingerprint())) conflict();
            return response(replay);
        }
        HomeView view = views.requireOwnedForUpdate(tenantId, userId, request.viewId());
        requireViewVersion(view, request.baseViewVersion());
        if (proposals.countByTenantIdAndUserIdAndViewIdAndStateAndExpiresAtAfter(
                tenantId, userId, view.getViewId(), "PREVIEWED",
                OffsetDateTime.now(ZoneOffset.UTC)) >= MAX_ACTIVE_PROPOSALS_PER_VIEW) {
            throw invalid("A view can have up to twenty active composer proposals.");
        }
        Set<String> entitledApps = entitledApps(
                tenantId, userId, permissions, request.changes());
        HomePreferenceDtos.HomeLayoutPayload before = views.currentLayout(view);
        HomePreferenceDtos.HomeLayoutPayload proposed = patch(
                view.getSurfaceKey(), before, request.changes(), entitledApps);
        HomeComposerProposal proposal = HomeComposerProposal.builder()
                .proposalId(UUID.randomUUID()).tenantId(tenantId).userId(userId)
                .viewId(view.getViewId()).state("PREVIEWED")
                .baseViewVersion(request.baseViewVersion())
                .reasonCodes(objectMapper.valueToTree(List.copyOf(request.reasonCodes())))
                .changesPayload(objectMapper.valueToTree(List.copyOf(request.changes())))
                .warningsPayload(objectMapper.createArrayNode())
                .beforeLayout(objectMapper.valueToTree(before))
                .proposedLayout(objectMapper.valueToTree(proposed))
                .creationCommandId(commandId).requestFingerprint(fingerprint)
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30))
                .build();
        save(proposal);
        audit.success(tenantId, userId, "home-composer.proposal-previewed",
                "HOME_COMPOSER_PROPOSAL", proposal.getProposalId().toString(),
                correlationId, null, auditSnapshot(proposal));
        HomeComposerDtos.ComposerProposalResponse result = response(proposal);
        commandReceipts.record(tenantId, userId, commandId, "CREATE_PROPOSAL",
                request.viewId().toString(), fingerprint, result);
        return result;
    }

    @Transactional(readOnly = true)
    public HomeComposerDtos.ComposerProposalResponse get(
            Long tenantId, Long userId, UUID proposalId) {
        access.requireComposer();
        HomeComposerProposal proposal = requireProposal(tenantId, userId, proposalId);
        views.requirePersonalizationForView(tenantId, userId, proposal.getViewId());
        return response(proposal);
    }

    @Transactional
    public HomeComposerDtos.ComposerProposalResponse apply(
            Long tenantId,
            Long userId,
            String permissions,
            UUID proposalId,
            UUID commandId,
            String correlationId,
            HomeComposerDtos.ApplyComposerProposalRequest request) {
        access.requireComposer();
        if (commandId == null) throw invalid("Idempotency-Key is required.");
        HomeComposerProposal observed = requireProposal(tenantId, userId, proposalId);
        String fingerprint = views.fingerprint(Map.of(
                "operation", "APPLY_PROPOSAL",
                "proposalId", proposalId,
                "viewId", observed.getViewId(),
                "baseViewVersion", observed.getBaseViewVersion(),
                "viewVersion", request.viewVersion(),
                "proposedLayout", observed.getProposedLayout()));
        String scopedFingerprint = views.externalFingerprint(
                "AI", observed.getViewId(), fingerprint);
        HomeComposerDtos.ComposerProposalResponse receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "APPLY_PROPOSAL", proposalId.toString(),
                scopedFingerprint, HomeComposerDtos.ComposerProposalResponse.class);
        if (receiptReplay != null) return receiptReplay;
        views.lockPersonalizationScopeForView(tenantId, userId, observed.getViewId());
        receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "APPLY_PROPOSAL", proposalId.toString(),
                scopedFingerprint, HomeComposerDtos.ComposerProposalResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeViewRevision replay = revisions
                .findByTenantIdAndUserIdAndCommandId(tenantId, userId, commandId).orElse(null);
        HomeView view = views.requireOwnedForUpdate(tenantId, userId, observed.getViewId());
        HomeComposerProposal proposal = requireProposalForUpdate(tenantId, userId, proposalId);
        if (!observed.getViewId().equals(proposal.getViewId())) conflict();
        String lockedFingerprint = views.fingerprint(Map.of(
                "operation", "APPLY_PROPOSAL",
                "proposalId", proposalId,
                "viewId", proposal.getViewId(),
                "baseViewVersion", proposal.getBaseViewVersion(),
                "viewVersion", request.viewVersion(),
                "proposedLayout", proposal.getProposedLayout()));
        if (!fingerprint.equals(lockedFingerprint)) conflict();
        if (replay != null) {
            requireReplay(replay, proposal, "AI", scopedFingerprint,
                    proposal.getAppliedRevisionId(), "APPLIED");
            return response(proposal);
        }
        if (!"PREVIEWED".equals(proposal.getState())
                || proposal.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            throw invalid("Only a current previewed proposal can be approved.");
        }
        if (!proposal.getBaseViewVersion().equals(request.viewVersion())) conflict();
        requireViewVersion(view, request.viewVersion());
        List<HomeComposerDtos.ComposerChange> changes = changes(proposal.getChangesPayload());
        Set<String> entitledApps = entitledApps(
                tenantId, userId, permissions, changes);
        HomePreferenceDtos.HomeLayoutPayload current = views.currentLayout(view);
        HomePreferenceDtos.HomeLayoutPayload verified = patch(
                view.getSurfaceKey(), current, changes, entitledApps);
        if (!objectMapper.valueToTree(verified).equals(proposal.getProposedLayout())) {
            conflict();
        }
        HomeViewDtos.HomeViewResponse applied = views.applyExternalLayout(
                tenantId, userId, proposal.getViewId(), request.viewVersion(), verified,
                "AI", "Approved composer proposal applied", commandId, fingerprint,
                userId, correlationId);
        HomeViewRevision revision = revisions.findTopByViewIdOrderByRevisionNumberDesc(
                proposal.getViewId()).orElseThrow();
        requireNewRevision(revision, proposal, "AI", scopedFingerprint, commandId);
        proposal.setState("APPLIED");
        proposal.setAppliedRevisionId(revision.getRevisionId());
        proposal.setAppliedViewVersion(applied.version());
        save(proposal);
        audit.success(tenantId, userId, "home-composer.proposal-applied",
                "HOME_COMPOSER_PROPOSAL", proposalId.toString(), correlationId,
                null, auditSnapshot(proposal));
        HomeComposerDtos.ComposerProposalResponse result = response(proposal);
        commandReceipts.record(tenantId, userId, commandId, "APPLY_PROPOSAL",
                proposalId.toString(), scopedFingerprint, result);
        return result;
    }

    @Transactional
    public HomeComposerDtos.ComposerProposalResponse undo(
            Long tenantId,
            Long userId,
            UUID proposalId,
            UUID commandId,
            String correlationId,
            HomeComposerDtos.ApplyComposerProposalRequest request) {
        access.requireComposer();
        if (commandId == null) throw invalid("Idempotency-Key is required.");
        HomeComposerProposal observed = requireProposal(tenantId, userId, proposalId);
        String fingerprint = views.fingerprint(Map.of(
                "operation", "UNDO_PROPOSAL",
                "proposalId", proposalId,
                "viewId", observed.getViewId(),
                "appliedRevisionId", String.valueOf(observed.getAppliedRevisionId()),
                "appliedViewVersion", String.valueOf(observed.getAppliedViewVersion()),
                "viewVersion", request.viewVersion()));
        String scopedFingerprint = views.externalFingerprint(
                "UNDO", observed.getViewId(), fingerprint);
        HomeComposerDtos.ComposerProposalResponse receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "UNDO_PROPOSAL", proposalId.toString(),
                scopedFingerprint, HomeComposerDtos.ComposerProposalResponse.class);
        if (receiptReplay != null) return receiptReplay;
        views.lockPersonalizationScopeForView(tenantId, userId, observed.getViewId());
        receiptReplay = commandReceipts.replay(
                tenantId, userId, commandId, "UNDO_PROPOSAL", proposalId.toString(),
                scopedFingerprint, HomeComposerDtos.ComposerProposalResponse.class);
        if (receiptReplay != null) return receiptReplay;
        HomeViewRevision replay = revisions
                .findByTenantIdAndUserIdAndCommandId(tenantId, userId, commandId).orElse(null);
        views.requireOwnedForUpdate(tenantId, userId, observed.getViewId());
        HomeComposerProposal proposal = requireProposalForUpdate(tenantId, userId, proposalId);
        if (!observed.getViewId().equals(proposal.getViewId())) conflict();
        String lockedFingerprint = views.fingerprint(Map.of(
                "operation", "UNDO_PROPOSAL",
                "proposalId", proposalId,
                "viewId", proposal.getViewId(),
                "appliedRevisionId", String.valueOf(proposal.getAppliedRevisionId()),
                "appliedViewVersion", String.valueOf(proposal.getAppliedViewVersion()),
                "viewVersion", request.viewVersion()));
        if (!fingerprint.equals(lockedFingerprint)) conflict();
        if (replay != null) {
            requireReplay(replay, proposal, "UNDO", scopedFingerprint,
                    proposal.getUndoneRevisionId(), "UNDONE");
            return response(proposal);
        }
        if (!"APPLIED".equals(proposal.getState())
                || proposal.getAppliedViewVersion() == null
                || !proposal.getAppliedViewVersion().equals(request.viewVersion())) {
            conflict();
        }
        HomePreferenceDtos.HomeLayoutPayload before = views.layout(proposal.getBeforeLayout());
        views.applyExternalLayout(
                tenantId, userId, proposal.getViewId(), request.viewVersion(), before,
                "UNDO", "Composer proposal undone", commandId, fingerprint,
                userId, correlationId);
        HomeViewRevision revision = revisions.findTopByViewIdOrderByRevisionNumberDesc(
                proposal.getViewId()).orElseThrow();
        requireNewRevision(revision, proposal, "UNDO", scopedFingerprint, commandId);
        proposal.setState("UNDONE");
        proposal.setUndoneRevisionId(revision.getRevisionId());
        save(proposal);
        audit.success(tenantId, userId, "home-composer.proposal-undone",
                "HOME_COMPOSER_PROPOSAL", proposalId.toString(), correlationId,
                null, auditSnapshot(proposal));
        HomeComposerDtos.ComposerProposalResponse result = response(proposal);
        commandReceipts.record(tenantId, userId, commandId, "UNDO_PROPOSAL",
                proposalId.toString(), scopedFingerprint, result);
        return result;
    }

    private HomePreferenceDtos.HomeLayoutPayload patch(
            String surfaceKey,
            HomePreferenceDtos.HomeLayoutPayload original,
            List<HomeComposerDtos.ComposerChange> changes,
            Set<String> entitledApps) {
        ObjectNode layout = objectMapper.valueToTree(original);
        for (HomeComposerDtos.ComposerChange change : changes) {
            switch (change.operation()) {
                case "MOVE_WIDGET" -> moveWidget(layout, change);
                case "SHOW_WIDGET" -> visibility(layout, change, true);
                case "HIDE_WIDGET" -> visibility(layout, change, false);
                case "SET_WIDTH" -> width(layout, change);
                case "SET_DENSITY" -> density(layout, change);
                case "PIN_APP" -> pinApp(layout, change, entitledApps);
                case "UNPIN_APP" -> unpinApp(layout, change, entitledApps);
                default -> throw invalid("The composer operation is not registered.");
            }
        }
        try {
            HomePreferenceDtos.HomeLayoutPayload candidate = objectMapper.treeToValue(
                    layout, HomePreferenceDtos.HomeLayoutPayload.class);
            HomePreferenceDtos.HomeLayoutPayload normalized =
                    preferenceService.normalizeForSurface(surfaceKey, candidate);
            return normalized;
        } catch (JsonProcessingException exception) {
            throw invalid("The composer patch did not produce a valid home layout.");
        }
    }

    private void validateProposalRequest(
            HomeComposerDtos.CreateComposerProposalRequest request) {
        if (request == null || request.reasonCodes() == null || request.changes() == null
                || request.reasonCodes().stream().anyMatch(value -> value == null || value.isBlank())
                || request.changes().stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("The composer proposal contains a null or missing value.");
        }
    }

    private void moveWidget(ObjectNode layout, HomeComposerDtos.ComposerChange change) {
        rejectLocked(change.widgetKey());
        ArrayNode widgets = widgets(layout);
        int current = widgetIndex(widgets, change.widgetKey());
        if (current < 0 || change.afterIndex() == null
                || change.afterIndex() >= widgets.size()
                || (change.beforeIndex() != null && current != change.beforeIndex())) {
            throw invalid("The widget move no longer matches the preview base.");
        }
        JsonNode widget = widgets.remove(current);
        widgets.insert(change.afterIndex(), widget);
    }

    private void visibility(
            ObjectNode layout, HomeComposerDtos.ComposerChange change, boolean visible) {
        rejectLocked(change.widgetKey());
        ObjectNode widget = widget(widgets(layout), change.widgetKey());
        widget.put("visible", visible);
    }

    private void width(ObjectNode layout, HomeComposerDtos.ComposerChange change) {
        rejectLocked(change.widgetKey());
        if (!WIDTHS.contains(change.value())) throw invalid("The requested width is not allowed.");
        widget(widgets(layout), change.widgetKey()).put("size", change.value());
    }

    private void density(ObjectNode layout, HomeComposerDtos.ComposerChange change) {
        if (!DENSITIES.contains(change.value())) {
            throw invalid("The requested presentation density is not allowed.");
        }
        layout.put("presentation", change.value());
    }

    private void pinApp(
            ObjectNode layout,
            HomeComposerDtos.ComposerChange change,
            Set<String> entitledApps) {
        requireEntitledApp(change.appId(), entitledApps);
        ObjectNode appLayout = appLayout(layout);
        ObjectNode groups = object(appLayout, "groups");
        if (change.value() == null || !groups.has(change.value())
                || containsVisibleApp(appLayout, change.appId())) {
            throw invalid("The app pin target group is invalid or the app is already placed.");
        }
        removeText((ArrayNode) appLayout.get("hiddenAppIds"), change.appId());
        ((ArrayNode) groups.get(change.value())).add(change.appId());
    }

    private void unpinApp(
            ObjectNode layout,
            HomeComposerDtos.ComposerChange change,
            Set<String> entitledApps) {
        requireEntitledApp(change.appId(), entitledApps);
        ObjectNode appLayout = appLayout(layout);
        ObjectNode groups = object(appLayout, "groups");
        groups.properties().forEach(entry ->
                removeText((ArrayNode) entry.getValue(), change.appId()));
        ObjectNode folders = object(appLayout, "folders");
        List<String> dissolve = new ArrayList<>();
        folders.properties().forEach(entry -> {
            ObjectNode folder = (ObjectNode) entry.getValue();
            ArrayNode appIds = (ArrayNode) folder.get("appIds");
            if (removeText(appIds, change.appId()) && appIds.size() < 2) {
                dissolve.add(entry.getKey());
            }
        });
        dissolve.forEach(folderId -> dissolveFolder(groups, folders, folderId));
        ArrayNode hidden = (ArrayNode) appLayout.get("hiddenAppIds");
        if (!contains(hidden, change.appId())) hidden.add(change.appId());
    }

    private void dissolveFolder(ObjectNode groups, ObjectNode folders, String folderId) {
        ObjectNode folder = (ObjectNode) folders.remove(folderId);
        String groupId = folder.path("groupId").asText();
        ArrayNode group = (ArrayNode) groups.get(groupId);
        int index = indexOf(group, folderId);
        if (index < 0) throw invalid("The app folder is not in its declared group.");
        group.remove(index);
        ArrayNode remaining = (ArrayNode) folder.get("appIds");
        for (int offset = 0; offset < remaining.size(); offset++) {
            group.insert(index + offset, remaining.get(offset));
        }
    }

    private Set<String> entitledApps(
            Long tenantId,
            Long userId,
            String permissions,
            List<HomeComposerDtos.ComposerChange> changes) {
        boolean needsApps = changes.stream().anyMatch(change ->
                "PIN_APP".equals(change.operation()) || "UNPIN_APP".equals(change.operation()));
        if (!needsApps) return Set.of();
        Set<String> result = new HashSet<>();
        workspace.apps(tenantId, userId, permissions, "en").stream()
                .filter(app -> "AVAILABLE".equals(app.accessState()))
                .map(WorkspaceDtos.WorkspaceApp::id).forEach(result::add);
        return Set.copyOf(result);
    }

    private void requireEntitledApp(String appId, Set<String> entitledApps) {
        if (appId == null || !entitledApps.contains(appId)) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The composer cannot modify an application outside current entitlement.");
        }
    }

    private ObjectNode appLayout(ObjectNode layout) {
        JsonNode value = layout.get("appLayout");
        if (value == null || !value.isObject()) {
            throw invalid("This home view does not have an application layout.");
        }
        return (ObjectNode) value;
    }

    private boolean containsVisibleApp(ObjectNode appLayout, String appId) {
        ObjectNode groups = object(appLayout, "groups");
        if (groups.properties().stream()
                .anyMatch(entry -> contains((ArrayNode) entry.getValue(), appId))) {
            return true;
        }
        return object(appLayout, "folders").properties().stream().anyMatch(entry ->
                contains((ArrayNode) entry.getValue().get("appIds"), appId));
    }

    private ObjectNode object(ObjectNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) throw invalid("The app layout is invalid.");
        return (ObjectNode) value;
    }

    private ArrayNode widgets(ObjectNode layout) {
        JsonNode value = layout.get("widgets");
        if (value == null || !value.isArray()) throw invalid("The widget layout is invalid.");
        return (ArrayNode) value;
    }

    private ObjectNode widget(ArrayNode widgets, String widgetKey) {
        int index = widgetIndex(widgets, widgetKey);
        if (index < 0) throw invalid("The composer widget is not registered in this view.");
        return (ObjectNode) widgets.get(index);
    }

    private int widgetIndex(ArrayNode widgets, String widgetKey) {
        if (widgetKey == null) return -1;
        for (int index = 0; index < widgets.size(); index++) {
            if (widgetKey.equals(widgets.get(index).path("widgetKey").asText())) return index;
        }
        return -1;
    }

    private void rejectLocked(String widgetKey) {
        if (widgetKey == null || LOCKED_WIDGETS.contains(widgetKey)) {
            throw invalid("The composer cannot change a managed Flow Home zone.");
        }
    }

    private boolean removeText(ArrayNode values, String expected) {
        int index = indexOf(values, expected);
        if (index < 0) return false;
        values.remove(index);
        return true;
    }

    private boolean contains(ArrayNode values, String expected) {
        return indexOf(values, expected) >= 0;
    }

    private int indexOf(ArrayNode values, String expected) {
        if (values == null) return -1;
        for (int index = 0; index < values.size(); index++) {
            if (expected.equals(values.get(index).asText())) return index;
        }
        return -1;
    }

    private HomeComposerProposal requireProposal(
            Long tenantId, Long userId, UUID proposalId) {
        return proposals.findByProposalIdAndTenantIdAndUserId(proposalId, tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private HomeComposerProposal requireProposalForUpdate(
            Long tenantId, Long userId, UUID proposalId) {
        return proposals.findOwnedForUpdate(proposalId, tenantId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private HomeComposerDtos.ComposerProposalResponse response(HomeComposerProposal value) {
        return new HomeComposerDtos.ComposerProposalResponse(
                value.getProposalId(), value.getViewId(), value.getState(),
                value.getBaseViewVersion(), strings(value.getReasonCodes()),
                changes(value.getChangesPayload()), strings(value.getWarningsPayload()),
                views.layout(value.getBeforeLayout()), views.layout(value.getProposedLayout()),
                value.getExpiresAt(), value.getAppliedRevisionId(), value.getUndoneRevisionId(),
                value.getCreatedAt() == null
                        ? null : value.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC),
                value.getUpdatedAt() == null
                        ? null : value.getUpdatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }

    private void requireReplay(
            HomeViewRevision replay,
            HomeComposerProposal proposal,
            String source,
            String requestFingerprint,
            UUID expectedRevisionId,
            String expectedState) {
        if (!expectedState.equals(proposal.getState())
                || expectedRevisionId == null
                || !expectedRevisionId.equals(replay.getRevisionId())
                || !proposal.getViewId().equals(replay.getViewId())
                || !source.equals(replay.getSource())
                || !requestFingerprint.equals(replay.getRequestFingerprint())) {
            conflict();
        }
    }

    private void requireNewRevision(
            HomeViewRevision revision,
            HomeComposerProposal proposal,
            String source,
            String requestFingerprint,
            UUID commandId) {
        if (!proposal.getViewId().equals(revision.getViewId())
                || !commandId.equals(revision.getCommandId())
                || !source.equals(revision.getSource())
                || !requestFingerprint.equals(revision.getRequestFingerprint())) {
            conflict();
        }
    }

    private List<String> strings(JsonNode value) {
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    private List<HomeComposerDtos.ComposerChange> changes(JsonNode value) {
        return objectMapper.convertValue(value, new TypeReference<>() { });
    }

    private void save(HomeComposerProposal proposal) {
        try {
            proposals.saveAndFlush(proposal);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            conflict();
        }
    }

    private Object auditSnapshot(HomeComposerProposal proposal) {
        return java.util.Map.of(
                "proposalId", proposal.getProposalId(),
                "viewId", proposal.getViewId(),
                "state", proposal.getState(),
                "baseViewVersion", proposal.getBaseViewVersion(),
                "reasonCodes", proposal.getReasonCodes(),
                "changeCount", proposal.getChangesPayload().size());
    }

    private void requireViewVersion(HomeView view, Long expected) {
        long actual = view.getVersion() == null ? 0L : view.getVersion();
        if (expected == null || actual != expected) conflict();
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private void conflict() {
        throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }
}
