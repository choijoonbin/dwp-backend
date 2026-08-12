package com.dwp.services.platform.navigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NavigationStudioService {

    private static final int MAX_ITEMS = 500;
    private static final Set<String> REQUIRED_LOCALES = Set.of("ko", "en");

    private final NavigationStudioRepository repository;
    private final NavigationService navigationService;
    private final PlatformAuditService auditService;
    private final ObjectMapper objectMapper;

    public NavigationStudioService(
            NavigationStudioRepository repository,
            NavigationService navigationService,
            PlatformAuditService auditService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.navigationService = navigationService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public NavigationStudioDtos.Workspace workspace(Long tenantId, Long actorId) {
        List<NavigationDtos.AdminNode> currentTree = navigationService.adminTree(tenantId);
        NavigationStudioRepository.StoredRevision published = ensureBaseline(
                tenantId, actorId, currentTree);
        NavigationStudioRepository.StoredRevision draft = repository.draft(tenantId).orElse(null);
        return new NavigationStudioDtos.Workspace(
                revision(published, published.tree()),
                draft == null ? null : revision(draft, published.tree()),
                repository.history(tenantId, 20).stream()
                        .map(item -> revision(item, baselineTree(tenantId, item)))
                        .toList(),
                currentTree,
                validate(tenantId, currentTree));
    }

    @Transactional
    public NavigationStudioDtos.Revision createDraft(
            Long tenantId,
            Long actorId,
            String correlationId,
            NavigationStudioDtos.CreateDraftRequest request) {
        List<NavigationDtos.AdminNode> currentTree = navigationService.adminTree(tenantId);
        NavigationStudioRepository.StoredRevision baseline = ensureBaseline(
                tenantId, actorId, currentTree);
        NavigationStudioDtos.ValidationReport validation = validate(tenantId, currentTree);
        NavigationStudioRepository.StoredRevision draft = repository.createDraft(
                tenantId, actorId, baseline, hash(baseline.tree()), currentTree,
                validation, request.changeSummary());
        auditService.success(
                tenantId, actorId, "navigation.draft.created", "NAVIGATION_REVISION",
                draft.navigationRevisionId().toString(), correlationId, null,
                snapshot(draft, diff(baseline.tree(), draft.tree())));
        return revision(draft, baseline.tree());
    }

    @Transactional
    public NavigationStudioDtos.Revision saveDraft(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            NavigationStudioDtos.SaveDraftRequest request) {
        NavigationStudioRepository.StoredRevision existing = repository.requireDraft(
                tenantId, revisionId);
        if (existing.version() != request.version()) throw conflict();
        List<NavigationDtos.AdminNode> tree = immutableTree(request.tree());
        NavigationStudioDtos.ValidationReport validation = validate(tenantId, tree);
        NavigationStudioRepository.StoredRevision saved = repository.updateDraft(
                tenantId, actorId, revisionId, request.version(), tree,
                validation, request.changeSummary());
        List<NavigationDtos.AdminNode> baseline = baselineTree(tenantId, saved);
        auditService.success(
                tenantId, actorId, "navigation.draft.saved", "NAVIGATION_REVISION",
                revisionId.toString(), correlationId,
                snapshot(existing, diff(baseline, existing.tree())),
                snapshot(saved, diff(baseline, saved.tree())));
        return revision(saved, baseline);
    }

    @Transactional
    public NavigationStudioDtos.Revision publish(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            NavigationStudioDtos.VersionRequest request) {
        NavigationStudioRepository.StoredRevision draft = repository.requireDraft(
                tenantId, revisionId);
        if (draft.version() != request.version()) throw conflict();
        NavigationStudioDtos.ValidationReport validation = validate(tenantId, draft.tree());
        if (!validation.valid()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Navigation draft has blocking validation errors.");
        }

        List<NavigationDtos.AdminNode> publishedTree = navigationService.applyStudioTree(
                tenantId, actorId, correlationId, draft.tree());
        NavigationStudioDtos.ValidationReport publishedValidation = validate(
                tenantId, publishedTree);
        NavigationStudioRepository.StoredRevision published = repository.publish(
                tenantId, actorId, revisionId, request.version(), hash(publishedTree),
                publishedTree, publishedValidation);
        List<NavigationDtos.AdminNode> baseline = baselineTree(tenantId, draft);
        auditService.success(
                tenantId, actorId, "navigation.revision.published", "NAVIGATION_REVISION",
                revisionId.toString(), correlationId,
                snapshot(draft, diff(baseline, draft.tree())),
                snapshot(published, diff(baseline, publishedTree)));
        return revision(published, baseline);
    }

    @Transactional
    public NavigationStudioDtos.Revision cancel(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            NavigationStudioDtos.VersionRequest request) {
        NavigationStudioRepository.StoredRevision draft = repository.requireDraft(
                tenantId, revisionId);
        if (draft.version() != request.version()) throw conflict();
        NavigationStudioRepository.StoredRevision cancelled = repository.cancel(
                tenantId, actorId, revisionId, request.version());
        List<NavigationDtos.AdminNode> baseline = baselineTree(tenantId, cancelled);
        auditService.success(
                tenantId, actorId, "navigation.draft.cancelled", "NAVIGATION_REVISION",
                revisionId.toString(), correlationId,
                snapshot(draft, diff(baseline, draft.tree())),
                snapshot(cancelled, diff(baseline, cancelled.tree())));
        return revision(cancelled, baseline);
    }

    @Transactional
    public NavigationStudioDtos.Revision restore(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID sourceRevisionId,
            NavigationStudioDtos.CreateDraftRequest request) {
        NavigationStudioRepository.StoredRevision source = repository.requireRevision(
                tenantId, sourceRevisionId);
        NavigationStudioRepository.StoredRevision baseline = repository.latestPublished(tenantId)
                .orElseGet(() -> ensureBaseline(
                        tenantId, actorId, navigationService.adminTree(tenantId)));
        NavigationStudioDtos.ValidationReport validation = validate(tenantId, source.tree());
        NavigationStudioRepository.StoredRevision draft = repository.createDraft(
                tenantId, actorId, baseline, hash(baseline.tree()), source.tree(), validation,
                request.changeSummary() == null
                        ? "Restore navigation revision " + source.revisionNumber()
                        : request.changeSummary());
        auditService.success(
                tenantId, actorId, "navigation.revision.restore-draft.created",
                "NAVIGATION_REVISION", draft.navigationRevisionId().toString(),
                correlationId, null, snapshot(draft, diff(baseline.tree(), source.tree())));
        return revision(draft, baseline.tree());
    }

    @Transactional(readOnly = true)
    public NavigationStudioDtos.ValidationReport validate(
            Long tenantId, List<NavigationDtos.AdminNode> tree) {
        List<NavigationStudioDtos.ValidationIssue> issues = new ArrayList<>();
        List<NavigationDtos.AdminNode> flattened = flattenSafely(tree, issues);
        if (flattened.size() > MAX_ITEMS) {
            issues.add(issue("ERROR", "ITEM_LIMIT_EXCEEDED", null, null,
                    "Navigation contains more than 500 items."));
        }
        Set<Long> ids = new HashSet<>();
        Set<String> keys = new HashSet<>();
        Set<String> routes = new HashSet<>();
        Set<String> activeApps = repository.activeAppRegistryKeys(tenantId);
        Map<String, Integer> siblingOrder = new HashMap<>();

        validateNodes(tree == null ? List.of() : tree, null, 0, issues, ids, keys,
                routes, activeApps, siblingOrder);
        long errors = issues.stream().filter(item -> "ERROR".equals(item.severity())).count();
        long warnings = issues.stream().filter(item -> "WARNING".equals(item.severity())).count();
        return new NavigationStudioDtos.ValidationReport(
                errors == 0, errors, warnings, List.copyOf(issues), OffsetDateTime.now());
    }

    private void validateNodes(
            List<NavigationDtos.AdminNode> nodes,
            NavigationDtos.AdminNode expectedParent,
            int depth,
            List<NavigationStudioDtos.ValidationIssue> issues,
            Set<Long> ids,
            Set<String> keys,
            Set<String> routes,
            Set<String> activeApps,
            Map<String, Integer> siblingOrder) {
        if (nodes == null) return;
        for (NavigationDtos.AdminNode node : nodes) {
            if (node.navigationItemId() == null || !ids.add(node.navigationItemId())) {
                issues.add(issue("ERROR", "DUPLICATE_ITEM", node,
                        "Navigation item identifier is missing or duplicated."));
            }
            if (node.navigationKey() == null
                    || !keys.add(node.navigationKey().toLowerCase(Locale.ROOT))) {
                issues.add(issue("ERROR", "DUPLICATE_KEY", node,
                        "Navigation key is missing or duplicated."));
            }
            Long expectedParentId = expectedParent == null
                    ? null : expectedParent.navigationItemId();
            if (!Objects.equals(expectedParentId, node.parentNavigationItemId())) {
                issues.add(issue("ERROR", "PARENT_MISMATCH", node,
                        "Stored parent does not match the proposed tree."));
            }
            if (depth > 1 || (depth == 1 && !"APP".equals(node.itemType()))) {
                issues.add(issue("ERROR", "DEPTH_EXCEEDED", node,
                        "Navigation supports root groups and one application level."));
            }
            String siblingKey = String.valueOf(expectedParentId) + ":" + node.sortOrder();
            if (siblingOrder.merge(siblingKey, 1, Integer::sum) > 1) {
                issues.add(issue("WARNING", "DUPLICATE_SORT_ORDER", node,
                        "Sibling navigation items share the same sort order."));
            }
            validateLabels(node, issues);
            if ("APP".equals(node.itemType())) {
                validateApp(node, issues, routes, activeApps);
            } else if (!"GROUP".equals(node.itemType())) {
                issues.add(issue("ERROR", "UNSUPPORTED_TYPE", node,
                        "Navigation item type is unsupported."));
            }
            if (expectedParent != null
                    && "ACTIVE".equals(node.lifecycleState())
                    && !"ACTIVE".equals(expectedParent.lifecycleState())) {
                issues.add(issue("ERROR", "ACTIVE_CHILD_WITH_INACTIVE_PARENT", node,
                        "An active item requires an active parent group."));
            }
            if ("DRAFT".equals(node.lifecycleState())) {
                issues.add(issue("WARNING", "DRAFT_ITEM_NOT_VISIBLE", node,
                        "Draft items are excluded from the runtime menu."));
            } else if (!List.of("ACTIVE", "RETIRED").contains(node.lifecycleState())) {
                issues.add(issue("ERROR", "INVALID_LIFECYCLE", node,
                        "Navigation lifecycle must be DRAFT, ACTIVE, or RETIRED."));
            }
            validateNodes(children(node), node, depth + 1, issues, ids, keys,
                    routes, activeApps, siblingOrder);
        }
    }

    private void validateApp(
            NavigationDtos.AdminNode node,
            List<NavigationStudioDtos.ValidationIssue> issues,
            Set<String> routes,
            Set<String> activeApps) {
        if (blank(node.registryEntryKey())
                || !activeApps.contains(node.registryEntryKey().toUpperCase(Locale.ROOT))) {
            issues.add(issue("ERROR", "ACTIVE_APP_REGISTRY_REQUIRED", node,
                    "Application navigation must reference an active APP registry entry."));
        }
        if (blank(node.route()) || !node.route().startsWith("/")) {
            issues.add(issue("ERROR", "ABSOLUTE_ROUTE_REQUIRED", node,
                    "Application navigation requires an absolute route."));
        } else if ("ACTIVE".equals(node.lifecycleState())
                && !routes.add(node.route().toLowerCase(Locale.ROOT))) {
            issues.add(issue("ERROR", "DUPLICATE_ACTIVE_ROUTE", node,
                    "Active application routes must be unique."));
        }
        if (blank(node.requiredResourceKey()) || blank(node.requiredPermissionCode())) {
            issues.add(issue("ERROR", "PERMISSION_REQUIRED", node,
                    "Application navigation requires a resource and permission."));
        }
        if (blank(node.iconKey())) {
            issues.add(issue("WARNING", "ICON_RECOMMENDED", node,
                    "An icon improves navigation scanning."));
        }
    }

    private void validateLabels(
            NavigationDtos.AdminNode node,
            List<NavigationStudioDtos.ValidationIssue> issues) {
        List<NavigationDtos.Label> labels = labels(node);
        if (labels.isEmpty()) {
            issues.add(issue("ERROR", "LABEL_REQUIRED", node,
                    "At least one localized label is required."));
            return;
        }
        Set<String> locales = labels.stream()
                .map(NavigationDtos.Label::locale)
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT).split("-")[0])
                .collect(Collectors.toSet());
        for (String required : REQUIRED_LOCALES) {
            if (!locales.contains(required)) {
                issues.add(issue("WARNING", "LOCALE_MISSING_" + required.toUpperCase(Locale.ROOT), node,
                        "Recommended locale label is missing: " + required));
            }
        }
    }

    private List<NavigationDtos.AdminNode> flattenSafely(
            List<NavigationDtos.AdminNode> tree,
            List<NavigationStudioDtos.ValidationIssue> issues) {
        try {
            return flatten(tree == null ? List.of() : tree);
        } catch (StackOverflowError error) {
            issues.add(issue("ERROR", "TREE_CYCLE", null, null,
                    "Navigation tree contains a cycle."));
            return List.of();
        }
    }

    private List<NavigationDtos.AdminNode> flatten(List<NavigationDtos.AdminNode> tree) {
        List<NavigationDtos.AdminNode> result = new ArrayList<>();
        if (tree == null) return result;
        for (NavigationDtos.AdminNode node : tree) {
            result.add(node);
            result.addAll(flatten(children(node)));
        }
        return result;
    }

    private List<NavigationDtos.AdminNode> immutableTree(List<NavigationDtos.AdminNode> tree) {
        if (tree == null) return List.of();
        return tree.stream().map(node -> new NavigationDtos.AdminNode(
                node.navigationItemId(), node.navigationKey(), node.itemType(),
                node.parentNavigationItemId(), node.registryEntryKey(), node.route(),
                node.iconKey(), node.requiredResourceKey(), node.requiredPermissionCode(),
                node.sortOrder(), node.lifecycleState(), node.version(),
                List.copyOf(labels(node)), immutableTree(children(node)))).toList();
    }

    private NavigationStudioRepository.StoredRevision ensureBaseline(
            Long tenantId,
            Long actorId,
            List<NavigationDtos.AdminNode> currentTree) {
        return repository.latestPublished(tenantId).orElseGet(() -> repository.createBaseline(
                tenantId, actorId, hash(currentTree), currentTree,
                validate(tenantId, currentTree)));
    }

    private List<NavigationDtos.AdminNode> baselineTree(
            Long tenantId, NavigationStudioRepository.StoredRevision revision) {
        if (revision.baselineRevisionId() == null) return revision.tree();
        return repository.requireRevision(tenantId, revision.baselineRevisionId()).tree();
    }

    private NavigationStudioDtos.Revision revision(
            NavigationStudioRepository.StoredRevision revision,
            List<NavigationDtos.AdminNode> baseline) {
        return new NavigationStudioDtos.Revision(
                revision.navigationRevisionId(), revision.revisionNumber(),
                revision.lifecycleState(), revision.baselineRevisionId(),
                revision.baselineTreeHash(), revision.tree(), revision.validation(),
                diff(baseline, revision.tree()), revision.changeSummary(), revision.version(),
                revision.createdAt(), revision.createdBy(), revision.updatedAt(),
                revision.publishedAt(), revision.publishedBy());
    }

    private NavigationStudioDtos.DiffSummary diff(
            List<NavigationDtos.AdminNode> baseline,
            List<NavigationDtos.AdminNode> candidate) {
        Map<Long, NodeFingerprint> before = fingerprints(baseline);
        Map<Long, NodeFingerprint> after = fingerprints(candidate);
        long added = after.keySet().stream().filter(key -> !before.containsKey(key)).count();
        long removed = before.keySet().stream().filter(key -> !after.containsKey(key)).count();
        long reordered = after.entrySet().stream()
                .filter(entry -> before.containsKey(entry.getKey()))
                .filter(entry -> !Objects.equals(entry.getValue().parentId(), before.get(entry.getKey()).parentId())
                        || entry.getValue().sortOrder() != before.get(entry.getKey()).sortOrder())
                .count();
        long lifecycle = after.entrySet().stream()
                .filter(entry -> before.containsKey(entry.getKey()))
                .filter(entry -> !Objects.equals(
                        entry.getValue().lifecycleState(), before.get(entry.getKey()).lifecycleState()))
                .count();
        long changed = after.entrySet().stream()
                .filter(entry -> before.containsKey(entry.getKey()))
                .filter(entry -> !entry.getValue().equals(before.get(entry.getKey())))
                .count();
        return new NavigationStudioDtos.DiffSummary(
                added, removed, changed, reordered, lifecycle);
    }

    private Map<Long, NodeFingerprint> fingerprints(List<NavigationDtos.AdminNode> tree) {
        return flatten(tree).stream().filter(node -> node.navigationItemId() != null)
                .collect(Collectors.toMap(
                        NavigationDtos.AdminNode::navigationItemId,
                        node -> new NodeFingerprint(
                                node.parentNavigationItemId(), node.sortOrder(),
                                node.lifecycleState(), node.registryEntryKey(), node.route(),
                                node.iconKey(), node.requiredResourceKey(),
                                node.requiredPermissionCode(), labels(node)),
                        (left, right) -> left, LinkedHashMap::new));
    }

    private String hash(List<NavigationDtos.AdminNode> tree) {
        try {
            byte[] value = objectMapper.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "Navigation revision hash could not be created.", exception);
        }
    }

    private Map<String, Object> snapshot(
            NavigationStudioRepository.StoredRevision revision,
            NavigationStudioDtos.DiffSummary diff) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revisionId", revision.navigationRevisionId());
        result.put("revisionNumber", revision.revisionNumber());
        result.put("lifecycleState", revision.lifecycleState());
        result.put("validation", revision.validation());
        result.put("diff", diff);
        result.put("version", revision.version());
        return result;
    }

    private NavigationStudioDtos.ValidationIssue issue(
            String severity, String code, NavigationDtos.AdminNode node, String message) {
        return issue(severity, code,
                node == null ? null : node.navigationItemId(),
                node == null ? null : node.navigationKey(), message);
    }

    private NavigationStudioDtos.ValidationIssue issue(
            String severity,
            String code,
            Long itemId,
            String navigationKey,
            String message) {
        return new NavigationStudioDtos.ValidationIssue(
                severity, code, itemId, navigationKey, message);
    }

    private List<NavigationDtos.AdminNode> children(NavigationDtos.AdminNode node) {
        return node.children() == null ? List.of() : node.children();
    }

    private List<NavigationDtos.Label> labels(NavigationDtos.AdminNode node) {
        return node.labels() == null ? List.of() : node.labels();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Navigation draft changed after it was loaded. Refresh and try again.");
    }

    private record NodeFingerprint(
            Long parentId,
            int sortOrder,
            String lifecycleState,
            String registryEntryKey,
            String route,
            String iconKey,
            String requiredResourceKey,
            String requiredPermissionCode,
            List<NavigationDtos.Label> labels) {
    }
}
