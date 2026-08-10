package com.dwp.services.platform.navigation;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.reference.ReferenceLifecycle;
import com.dwp.services.platform.registry.RegistryEntryRepository;
import com.dwp.services.platform.registry.RegistryType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NavigationService {

    private final NavigationItemRepository itemRepository;
    private final NavigationLabelRepository labelRepository;
    private final RegistryEntryRepository registryRepository;
    private final PlatformAuditService auditService;

    public NavigationService(
            NavigationItemRepository itemRepository,
            NavigationLabelRepository labelRepository,
            RegistryEntryRepository registryRepository,
            PlatformAuditService auditService) {
        this.itemRepository = itemRepository;
        this.labelRepository = labelRepository;
        this.registryRepository = registryRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<NavigationDtos.AdminNode> adminTree(Long tenantId) {
        List<NavigationItem> items = itemRepository
                .findByTenantIdOrderBySortOrderAscNavigationItemIdAsc(tenantId);
        return adminTree(tenantId, items);
    }

    @Transactional(readOnly = true)
    public List<NavigationDtos.RuntimeNode> runtimeTree(Long tenantId, String requestedLocale) {
        List<NavigationItem> items = itemRepository
                .findByTenantIdAndLifecycleStateOrderBySortOrderAscNavigationItemIdAsc(
                        tenantId, "ACTIVE");
        if (items.isEmpty()) return List.of();
        Map<Long, List<NavigationLabel>> labels = labelsByItem(tenantId, items);
        String locale = canonicalLocale(requestedLocale);
        Map<Long, List<NavigationItem>> children = items.stream()
                .filter(item -> item.getParentNavigationItemId() != null)
                .collect(Collectors.groupingBy(NavigationItem::getParentNavigationItemId));
        return items.stream()
                .filter(item -> item.getParentNavigationItemId() == null)
                .sorted(itemComparator())
                .map(item -> runtimeNode(item, children, labels, locale, new HashSet<>()))
                .toList();
    }

    @Transactional
    public NavigationDtos.AdminNode create(
            Long tenantId,
            Long actorId,
            String correlationId,
            NavigationDtos.CreateRequest request) {
        NavigationItem parent = validateParent(tenantId, null, request.parentNavigationItemId());
        validateShape(
                tenantId, request.itemType(), parent, request.registryEntryKey(), request.route(),
                request.requiredResourceKey());
        validateLabels(request.labels());
        NavigationItem item = NavigationItem.builder()
                .tenantId(tenantId)
                .navigationKey(request.navigationKey().trim().toLowerCase(Locale.ROOT))
                .itemType(request.itemType())
                .parentNavigationItemId(parent == null ? null : parent.getNavigationItemId())
                .registryEntryKey(normalizeRegistryKey(request.registryEntryKey()))
                .route(trimToNull(request.route()))
                .iconKey(trimToNull(request.iconKey()))
                .requiredResourceKey(trimToNull(request.requiredResourceKey()))
                .requiredPermissionCode(defaultPermission(request.requiredPermissionCode()))
                .sortOrder(request.sortOrder())
                .lifecycleState("DRAFT")
                .build();
        item.setCreatedBy(actorId);
        item.setUpdatedBy(actorId);
        item = save(item);
        replaceLabels(tenantId, actorId, item.getNavigationItemId(), request.labels());
        auditService.success(
                tenantId, actorId, "navigation.item.created", "NAVIGATION_ITEM",
                item.getNavigationItemId().toString(), correlationId, null, snapshot(item));
        return findAdminNode(tenantId, item.getNavigationItemId());
    }

    @Transactional
    public NavigationDtos.AdminNode update(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long itemId,
            NavigationDtos.UpdateRequest request) {
        NavigationItem item = require(tenantId, itemId);
        requireVersion(item.getVersion(), request.version());
        NavigationItem parent = validateParent(tenantId, itemId, request.parentNavigationItemId());
        ensureNoCycle(tenantId, itemId, parent);
        validateShape(
                tenantId, item.getItemType(), parent, request.registryEntryKey(), request.route(),
                request.requiredResourceKey());
        validateLabels(request.labels());
        Map<String, Object> before = snapshot(item);
        item.setParentNavigationItemId(parent == null ? null : parent.getNavigationItemId());
        item.setRegistryEntryKey(normalizeRegistryKey(request.registryEntryKey()));
        item.setRoute(trimToNull(request.route()));
        item.setIconKey(trimToNull(request.iconKey()));
        item.setRequiredResourceKey(trimToNull(request.requiredResourceKey()));
        item.setRequiredPermissionCode(defaultPermission(request.requiredPermissionCode()));
        item.setSortOrder(request.sortOrder());
        item.setUpdatedBy(actorId);
        item = save(item);
        replaceLabels(tenantId, actorId, itemId, request.labels());
        auditService.success(
                tenantId, actorId, "navigation.item.updated", "NAVIGATION_ITEM",
                itemId.toString(), correlationId, before, snapshot(item));
        return findAdminNode(tenantId, itemId);
    }

    @Transactional
    public NavigationDtos.AdminNode lifecycle(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long itemId,
            String nextState,
            Long expectedVersion) {
        NavigationItem item = require(tenantId, itemId);
        requireVersion(item.getVersion(), expectedVersion);
        if (!List.of("ACTIVE", "RETIRED").contains(nextState)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        if ("ACTIVE".equals(nextState)) {
            NavigationItem parent = validateParent(tenantId, itemId, item.getParentNavigationItemId());
            if (parent != null && !"ACTIVE".equals(parent.getLifecycleState())) {
                throw new BaseException(
                        ErrorCode.INVALID_STATE,
                        "The parent navigation group must be active first.");
            }
            List<NavigationLabel> labels = labelRepository.findByTenantIdAndNavigationItemIdIn(
                    tenantId, List.of(itemId));
            if (labels.isEmpty()) {
                throw new BaseException(ErrorCode.INVALID_STATE, "At least one navigation label is required.");
            }
        } else if (itemRepository.countByTenantIdAndParentNavigationItemIdAndLifecycleState(
                tenantId, itemId, "ACTIVE") > 0) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "A navigation group with active children cannot be retired.");
        }
        Map<String, Object> before = snapshot(item);
        item.setLifecycleState(nextState);
        item.setUpdatedBy(actorId);
        item = save(item);
        auditService.success(
                tenantId, actorId,
                "ACTIVE".equals(nextState) ? "navigation.item.activated" : "navigation.item.retired",
                "NAVIGATION_ITEM", itemId.toString(), correlationId, before, snapshot(item));
        return findAdminNode(tenantId, itemId);
    }

    @Transactional
    public List<NavigationDtos.AdminNode> reorder(
            Long tenantId,
            Long actorId,
            String correlationId,
            NavigationDtos.ReorderRequest request) {
        Set<Long> ids = request.items().stream()
                .map(NavigationDtos.ReorderItem::navigationItemId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (ids.size() != request.items().size()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Duplicate navigation item in reorder request.");
        }
        Map<Long, NavigationItem> items = itemRepository.findAllById(ids).stream()
                .filter(item -> tenantId.equals(item.getTenantId()))
                .collect(Collectors.toMap(NavigationItem::getNavigationItemId, Function.identity()));
        if (items.size() != ids.size()) throw new BaseException(ErrorCode.NOT_FOUND);
        for (NavigationDtos.ReorderItem change : request.items()) {
            NavigationItem item = items.get(change.navigationItemId());
            requireVersion(item.getVersion(), change.version());
            NavigationItem parent = validateParent(
                    tenantId, item.getNavigationItemId(), change.parentNavigationItemId());
            ensureNoCycle(tenantId, item.getNavigationItemId(), parent);
            validateShape(
                    tenantId, item.getItemType(), parent, item.getRegistryEntryKey(), item.getRoute(),
                    item.getRequiredResourceKey());
            item.setParentNavigationItemId(parent == null ? null : parent.getNavigationItemId());
            item.setSortOrder(change.sortOrder());
            item.setUpdatedBy(actorId);
        }
        itemRepository.saveAllAndFlush(items.values());
        auditService.success(
                tenantId, actorId, "navigation.items.reordered", "NAVIGATION",
                "tree", correlationId, null, Map.of("changedItemCount", items.size()));
        return adminTree(tenantId);
    }

    private List<NavigationDtos.AdminNode> adminTree(Long tenantId, List<NavigationItem> items) {
        if (items.isEmpty()) return List.of();
        Map<Long, List<NavigationLabel>> labels = labelsByItem(tenantId, items);
        Map<Long, List<NavigationItem>> children = items.stream()
                .filter(item -> item.getParentNavigationItemId() != null)
                .collect(Collectors.groupingBy(NavigationItem::getParentNavigationItemId));
        return items.stream()
                .filter(item -> item.getParentNavigationItemId() == null)
                .sorted(itemComparator())
                .map(item -> adminNode(item, children, labels, new HashSet<>()))
                .toList();
    }

    private NavigationDtos.AdminNode findAdminNode(Long tenantId, Long itemId) {
        return flatten(adminTree(tenantId)).stream()
                .filter(node -> itemId.equals(node.navigationItemId()))
                .findFirst()
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private List<NavigationDtos.AdminNode> flatten(List<NavigationDtos.AdminNode> nodes) {
        List<NavigationDtos.AdminNode> result = new ArrayList<>();
        for (NavigationDtos.AdminNode node : nodes) {
            result.add(node);
            result.addAll(flatten(node.children()));
        }
        return result;
    }

    private NavigationDtos.AdminNode adminNode(
            NavigationItem item,
            Map<Long, List<NavigationItem>> children,
            Map<Long, List<NavigationLabel>> labels,
            Set<Long> path) {
        guardCycle(item, path);
        Set<Long> nextPath = new HashSet<>(path);
        nextPath.add(item.getNavigationItemId());
        return new NavigationDtos.AdminNode(
                item.getNavigationItemId(), item.getNavigationKey(), item.getItemType(),
                item.getParentNavigationItemId(), item.getRegistryEntryKey(), item.getRoute(),
                item.getIconKey(), item.getRequiredResourceKey(), item.getRequiredPermissionCode(),
                item.getSortOrder(), item.getLifecycleState(), valueOrZero(item.getVersion()),
                labels.getOrDefault(item.getNavigationItemId(), List.of()).stream()
                        .sorted(Comparator.comparing(NavigationLabel::getLocale))
                        .map(label -> new NavigationDtos.Label(
                                label.getLocale(), label.getLabel(), label.getDescription()))
                        .toList(),
                children.getOrDefault(item.getNavigationItemId(), List.of()).stream()
                        .sorted(itemComparator())
                        .map(child -> adminNode(child, children, labels, nextPath))
                        .toList());
    }

    private NavigationDtos.RuntimeNode runtimeNode(
            NavigationItem item,
            Map<Long, List<NavigationItem>> children,
            Map<Long, List<NavigationLabel>> labels,
            String locale,
            Set<Long> path) {
        guardCycle(item, path);
        Set<Long> nextPath = new HashSet<>(path);
        nextPath.add(item.getNavigationItemId());
        NavigationLabel label = localizedLabel(
                labels.getOrDefault(item.getNavigationItemId(), List.of()), locale);
        return new NavigationDtos.RuntimeNode(
                item.getNavigationKey(), item.getItemType(),
                label == null ? item.getNavigationKey() : label.getLabel(),
                label == null ? null : label.getDescription(),
                item.getRegistryEntryKey(), item.getRoute(), item.getIconKey(),
                item.getRequiredResourceKey(), item.getRequiredPermissionCode(),
                children.getOrDefault(item.getNavigationItemId(), List.of()).stream()
                        .filter(child -> "ACTIVE".equals(child.getLifecycleState()))
                        .sorted(itemComparator())
                        .map(child -> runtimeNode(child, children, labels, locale, nextPath))
                        .toList());
    }

    private NavigationLabel localizedLabel(List<NavigationLabel> labels, String locale) {
        if (labels.isEmpty()) return null;
        String language = locale.contains("-") ? locale.substring(0, locale.indexOf('-')) : locale;
        return labels.stream().filter(label -> label.getLocale().equalsIgnoreCase(locale)).findFirst()
                .or(() -> labels.stream().filter(label -> label.getLocale().equalsIgnoreCase(language)).findFirst())
                .or(() -> labels.stream().filter(label -> label.getLocale().equalsIgnoreCase("en")).findFirst())
                .orElse(labels.get(0));
    }

    private Map<Long, List<NavigationLabel>> labelsByItem(
            Long tenantId,
            Collection<NavigationItem> items) {
        return labelRepository.findByTenantIdAndNavigationItemIdIn(
                        tenantId, items.stream().map(NavigationItem::getNavigationItemId).toList())
                .stream().collect(Collectors.groupingBy(NavigationLabel::getNavigationItemId));
    }

    private void replaceLabels(
            Long tenantId,
            Long actorId,
            Long itemId,
            List<NavigationDtos.Label> labels) {
        labelRepository.deleteByTenantIdAndNavigationItemId(tenantId, itemId);
        labelRepository.flush();
        labelRepository.saveAll(labels.stream().map(label -> {
            NavigationLabel entity = NavigationLabel.builder()
                    .tenantId(tenantId)
                    .navigationItemId(itemId)
                    .locale(canonicalLocale(label.locale()))
                    .label(label.label().trim())
                    .description(trimToNull(label.description()))
                    .build();
            entity.setCreatedBy(actorId);
            entity.setUpdatedBy(actorId);
            return entity;
        }).toList());
    }

    private void validateLabels(List<NavigationDtos.Label> labels) {
        Set<String> locales = new HashSet<>();
        labels.forEach(label -> {
            String locale = canonicalLocale(label.locale());
            if (!locales.add(locale.toLowerCase(Locale.ROOT))) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Duplicate navigation locale.");
            }
        });
    }

    private NavigationItem validateParent(Long tenantId, Long itemId, Long parentId) {
        if (parentId == null) return null;
        if (parentId.equals(itemId)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        NavigationItem parent = require(tenantId, parentId);
        if (!"GROUP".equals(parent.getItemType())) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Navigation parent must be a group.");
        }
        return parent;
    }

    private void ensureNoCycle(Long tenantId, Long itemId, NavigationItem parent) {
        Set<Long> visited = new HashSet<>();
        NavigationItem cursor = parent;
        while (cursor != null) {
            if (!visited.add(cursor.getNavigationItemId())
                    || itemId.equals(cursor.getNavigationItemId())) {
                throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Navigation hierarchy contains a cycle.");
            }
            cursor = cursor.getParentNavigationItemId() == null
                    ? null
                    : require(tenantId, cursor.getParentNavigationItemId());
        }
    }

    private void validateShape(
            Long tenantId,
            String type,
            NavigationItem parent,
            String registryKey,
            String route,
            String resourceKey) {
        if ("GROUP".equals(type)) {
            if (parent != null
                    || trimToNull(registryKey) != null
                    || trimToNull(route) != null || trimToNull(resourceKey) != null) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Navigation groups must be root items without an app route or resource requirement.");
            }
            return;
        }
        if (!"APP".equals(type)
                || (parent != null && parent.getParentNavigationItemId() != null)
                || trimToNull(registryKey) == null
                || trimToNull(route) == null
                || !route.trim().startsWith("/")
                || trimToNull(resourceKey) == null) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Navigation apps require a registry key, absolute route, and resource key.");
        }
        String normalizedRegistryKey = normalizeRegistryKey(registryKey);
        if (registryRepository.findByTenantIdAndRegistryTypeAndEntryKeyAndLifecycleState(
                tenantId, RegistryType.APP, normalizedRegistryKey, ReferenceLifecycle.ACTIVE)
                .isEmpty()) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Navigation apps must reference an active APP registry entry.");
        }
    }

    private NavigationItem require(Long tenantId, Long itemId) {
        return itemRepository.findByNavigationItemIdAndTenantId(itemId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private NavigationItem save(NavigationItem item) {
        try {
            return itemRepository.saveAndFlush(item);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "A navigation item with this key already exists.", exception);
        } catch (OptimisticLockingFailureException exception) {
            throw conflict();
        }
    }

    private void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(valueOrZero(actual), expected)) throw conflict();
    }

    private BaseException conflict() {
        return new BaseException(
                ErrorCode.RESOURCE_CONFLICT,
                "Navigation changed after it was loaded. Refresh and try again.");
    }

    private void guardCycle(NavigationItem item, Set<Long> path) {
        if (path.contains(item.getNavigationItemId())) {
            throw new IllegalStateException("Stored navigation hierarchy contains a cycle.");
        }
    }

    private Comparator<NavigationItem> itemComparator() {
        return Comparator.comparing(NavigationItem::getSortOrder)
                .thenComparing(NavigationItem::getNavigationItemId);
    }

    private Map<String, Object> snapshot(NavigationItem item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("itemId", item.getNavigationItemId());
        result.put("navigationKey", item.getNavigationKey());
        result.put("itemType", item.getItemType());
        result.put("parentId", item.getParentNavigationItemId());
        result.put("route", item.getRoute());
        result.put("resourceKey", item.getRequiredResourceKey());
        result.put("permission", item.getRequiredPermissionCode());
        result.put("sortOrder", item.getSortOrder());
        result.put("lifecycleState", item.getLifecycleState());
        result.put("version", valueOrZero(item.getVersion()));
        return result;
    }

    private String defaultPermission(String value) {
        return value == null || value.isBlank() ? "VIEW" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String canonicalLocale(String value) {
        String candidate = value == null || value.isBlank() ? "en" : value.trim();
        try {
            String locale = new Locale.Builder().setLanguageTag(candidate).build().toLanguageTag();
            if (locale.isBlank() || "und".equalsIgnoreCase(locale)) throw new IllegalArgumentException();
            return locale;
        } catch (IllegalArgumentException exception) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Navigation locale is invalid.");
        }
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeRegistryKey(String value) {
        String normalized = trimToNull(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }
}
