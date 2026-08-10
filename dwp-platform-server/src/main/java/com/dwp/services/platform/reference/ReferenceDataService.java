package com.dwp.services.platform.reference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IllformedLocaleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.dwp.services.platform.reference.ReferenceDataDtos.itemSnapshot;
import static com.dwp.services.platform.reference.ReferenceDataDtos.setSnapshot;

@Service
public class ReferenceDataService {

    private static final int MAX_REFERENCE_HIERARCHY_DEPTH = 8;

    private final ReferenceSetRepository setRepository;
    private final ReferenceItemRepository itemRepository;
    private final ReferenceItemLabelRepository labelRepository;
    private final PlatformAuditService auditService;

    public ReferenceDataService(
            ReferenceSetRepository setRepository,
            ReferenceItemRepository itemRepository,
            ReferenceItemLabelRepository labelRepository,
            PlatformAuditService auditService) {
        this.setRepository = setRepository;
        this.itemRepository = itemRepository;
        this.labelRepository = labelRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ReferenceDataDtos.PageResult<ReferenceDataDtos.ReferenceSetSummary> listSets(
            Long tenantId,
            String query,
            ReferenceLifecycle lifecycle,
            int page,
            int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Specification<ReferenceSet> specification = tenantSpecification(tenantId);
        if (lifecycle != null) {
            specification = specification.and((root, ignored, builder) ->
                    builder.equal(root.get("lifecycleState"), lifecycle));
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, ignored, builder) -> builder.or(
                    builder.like(builder.lower(root.get("setKey")), pattern),
                    builder.like(builder.lower(root.get("name")), pattern)));
        }
        Page<ReferenceSet> result = setRepository.findAll(
                specification,
                PageRequest.of(safePage, safeSize, Sort.by("setKey").ascending()));
        List<ReferenceDataDtos.ReferenceSetSummary> content = result.stream()
                .map(set -> toSummary(
                        set,
                        itemRepository.countByTenantIdAndReferenceSetId(
                                tenantId,
                                set.getReferenceSetId())))
                .toList();
        return new ReferenceDataDtos.PageResult<>(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ReferenceDataDtos.ReferenceSetDetail getSet(Long tenantId, String rawSetKey) {
        ReferenceSet set = requireSet(tenantId, rawSetKey);
        List<ReferenceItem> items = itemRepository
                .findByTenantIdAndReferenceSetIdOrderBySortOrderAscCodeAsc(
                        tenantId,
                        set.getReferenceSetId());
        return toDetail(set, items, labelsByItem(tenantId, items));
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail createSet(
            Long tenantId,
            Long actorId,
            String correlationId,
            ReferenceDataDtos.CreateSetRequest request) {
        String setKey = normalizeIdentifier(request.setKey());
        if (setRepository.existsByTenantIdAndSetKey(tenantId, setKey)) {
            throw conflict("Reference set already exists: " + setKey);
        }
        ReferenceSet set = ReferenceSet.builder()
                .tenantId(tenantId)
                .setKey(setKey)
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .lifecycleState(ReferenceLifecycle.DRAFT)
                .contentRevision(1L)
                .build();
        try {
            set = setRepository.saveAndFlush(set);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Reference set already exists: " + setKey,
                    exception);
        }
        auditService.success(
                tenantId,
                actorId,
                "reference-set.created",
                "REFERENCE_SET",
                setKey,
                correlationId,
                null,
                setSnapshot(set));
        return toDetail(set, List.of(), Map.of());
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail updateSet(
            Long tenantId,
            Long actorId,
            String correlationId,
            String rawSetKey,
            ReferenceDataDtos.UpdateSetRequest request) {
        ReferenceSet set = requireMutableSet(tenantId, rawSetKey);
        requireVersion(set.getVersion(), request.version());
        Map<String, Object> before = setSnapshot(set);
        set.setName(request.name().trim());
        set.setDescription(trimToNull(request.description()));
        bumpRevision(set);
        set = setRepository.saveAndFlush(set);
        auditService.success(
                tenantId,
                actorId,
                "reference-set.updated",
                "REFERENCE_SET",
                set.getSetKey(),
                correlationId,
                before,
                setSnapshot(set));
        return getSet(tenantId, set.getSetKey());
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail activateSet(
            Long tenantId,
            Long actorId,
            String correlationId,
            String rawSetKey,
            Long expectedVersion) {
        ReferenceSet set = requireMutableSet(tenantId, rawSetKey);
        requireVersion(set.getVersion(), expectedVersion);
        List<ReferenceItem> items = itemRepository
                .findByTenantIdAndReferenceSetIdOrderBySortOrderAscCodeAsc(
                        tenantId,
                        set.getReferenceSetId());
        long activatableItemCount = items.stream()
                .filter(item -> item.getLifecycleState() != ReferenceLifecycle.RETIRED)
                .count();
        if (activatableItemCount == 0) {
            throw conflict("At least one reference item is required before activation.");
        }
        for (ReferenceItem item : items) {
            if (item.getLifecycleState() == ReferenceLifecycle.RETIRED) continue;
            if (labelRepository.countByTenantIdAndReferenceItemId(
                    tenantId,
                    item.getReferenceItemId()) == 0) {
                throw conflict("Every active reference item requires a localized label.");
            }
            item.setLifecycleState(ReferenceLifecycle.ACTIVE);
        }
        Map<String, Object> before = setSnapshot(set);
        itemRepository.saveAllAndFlush(items);
        set.setLifecycleState(ReferenceLifecycle.ACTIVE);
        bumpRevision(set);
        set = setRepository.saveAndFlush(set);
        auditService.success(
                tenantId,
                actorId,
                "reference-set.activated",
                "REFERENCE_SET",
                set.getSetKey(),
                correlationId,
                before,
                setSnapshot(set));
        return getSet(tenantId, set.getSetKey());
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail retireSet(
            Long tenantId,
            Long actorId,
            String correlationId,
            String rawSetKey,
            Long expectedVersion) {
        ReferenceSet set = requireSet(tenantId, rawSetKey);
        requireVersion(set.getVersion(), expectedVersion);
        if (set.getLifecycleState() == ReferenceLifecycle.RETIRED) {
            return getSet(tenantId, set.getSetKey());
        }
        Map<String, Object> before = setSnapshot(set);
        set.setLifecycleState(ReferenceLifecycle.RETIRED);
        bumpRevision(set);
        set = setRepository.saveAndFlush(set);
        auditService.success(
                tenantId,
                actorId,
                "reference-set.retired",
                "REFERENCE_SET",
                set.getSetKey(),
                correlationId,
                before,
                setSnapshot(set));
        return getSet(tenantId, set.getSetKey());
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail createItem(
            Long tenantId,
            Long actorId,
            String correlationId,
            String rawSetKey,
            ReferenceDataDtos.CreateItemRequest request) {
        ReferenceSet set = requireMutableSet(tenantId, rawSetKey);
        String code = normalizeIdentifier(request.code());
        if (itemRepository.existsByTenantIdAndReferenceSetIdAndCode(
                tenantId,
                set.getReferenceSetId(),
                code)) {
            throw conflict("Reference item already exists: " + code);
        }
        validateValidity(request.validFrom(), request.validTo());
        String parentCode = normalizeOptionalIdentifier(request.parentCode());
        ReferenceItem parent = resolveParent(tenantId, set, code, null, parentCode);
        List<ReferenceDataDtos.LocalizedLabelRequest> labels = normalizeLabels(request.labels());
        ReferenceItem item = ReferenceItem.builder()
                .tenantId(tenantId)
                .referenceSetId(set.getReferenceSetId())
                .code(code)
                .lifecycleState(ReferenceLifecycle.DRAFT)
                .sortOrder(request.sortOrder())
                .parentCode(parentCode)
                .parentReferenceItemId(parent == null ? null : parent.getReferenceItemId())
                .validFrom(request.validFrom())
                .validTo(request.validTo())
                .build();
        try {
            item = itemRepository.saveAndFlush(item);
            replaceLabels(tenantId, item.getReferenceItemId(), labels);
        } catch (DataIntegrityViolationException exception) {
            throw new BaseException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "Reference item already exists: " + code,
                    exception);
        }
        bumpRevision(set);
        setRepository.saveAndFlush(set);
        auditService.success(
                tenantId,
                actorId,
                "reference-item.created",
                "REFERENCE_ITEM",
                set.getSetKey() + "/" + code,
                correlationId,
                null,
                itemSnapshot(item));
        return getSet(tenantId, set.getSetKey());
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail updateItem(
            Long tenantId,
            Long actorId,
            String correlationId,
            String rawSetKey,
            String rawCode,
            ReferenceDataDtos.UpdateItemRequest request) {
        ReferenceSet set = requireMutableSet(tenantId, rawSetKey);
        ReferenceItem item = requireItem(tenantId, set, rawCode);
        requireItemMutable(item);
        requireVersion(item.getVersion(), request.version());
        validateValidity(request.validFrom(), request.validTo());
        String parentCode = normalizeOptionalIdentifier(request.parentCode());
        ReferenceItem parent = resolveParent(
                tenantId,
                set,
                item.getCode(),
                item.getReferenceItemId(),
                parentCode);
        List<ReferenceDataDtos.LocalizedLabelRequest> labels = normalizeLabels(request.labels());
        Map<String, Object> before = itemSnapshot(item);
        item.setSortOrder(request.sortOrder());
        item.setParentCode(parentCode);
        item.setParentReferenceItemId(parent == null ? null : parent.getReferenceItemId());
        item.setValidFrom(request.validFrom());
        item.setValidTo(request.validTo());
        item = itemRepository.saveAndFlush(item);
        replaceLabels(tenantId, item.getReferenceItemId(), labels);
        bumpRevision(set);
        setRepository.saveAndFlush(set);
        auditService.success(
                tenantId,
                actorId,
                "reference-item.updated",
                "REFERENCE_ITEM",
                set.getSetKey() + "/" + item.getCode(),
                correlationId,
                before,
                itemSnapshot(item));
        return getSet(tenantId, set.getSetKey());
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail activateItem(
            Long tenantId,
            Long actorId,
            String correlationId,
            String rawSetKey,
            String rawCode,
            Long expectedVersion) {
        ReferenceSet set = requireMutableSet(tenantId, rawSetKey);
        if (set.getLifecycleState() != ReferenceLifecycle.ACTIVE) {
            throw conflict("The reference set must be active before an item can be activated.");
        }
        ReferenceItem item = requireItem(tenantId, set, rawCode);
        requireItemMutable(item);
        requireVersion(item.getVersion(), expectedVersion);
        if (labelRepository.countByTenantIdAndReferenceItemId(
                tenantId,
                item.getReferenceItemId()) == 0) {
            throw conflict("A localized label is required before activation.");
        }
        if (item.getParentReferenceItemId() != null) {
            ReferenceItem parent = itemRepository
                    .findByTenantIdAndReferenceSetIdAndReferenceItemId(
                            tenantId,
                            set.getReferenceSetId(),
                            item.getParentReferenceItemId())
                    .orElseThrow(() -> conflict("The parent reference item does not exist."));
            if (parent.getLifecycleState() != ReferenceLifecycle.ACTIVE) {
                throw conflict("The parent reference item must be active first.");
            }
        }
        Map<String, Object> before = itemSnapshot(item);
        item.setLifecycleState(ReferenceLifecycle.ACTIVE);
        item = itemRepository.saveAndFlush(item);
        bumpRevision(set);
        setRepository.saveAndFlush(set);
        auditService.success(
                tenantId,
                actorId,
                "reference-item.activated",
                "REFERENCE_ITEM",
                set.getSetKey() + "/" + item.getCode(),
                correlationId,
                before,
                itemSnapshot(item));
        return getSet(tenantId, set.getSetKey());
    }

    @Transactional
    public ReferenceDataDtos.ReferenceSetDetail retireItem(
            Long tenantId,
            Long actorId,
            String correlationId,
            String rawSetKey,
            String rawCode,
            Long expectedVersion) {
        ReferenceSet set = requireMutableSet(tenantId, rawSetKey);
        ReferenceItem item = requireItem(tenantId, set, rawCode);
        requireVersion(item.getVersion(), expectedVersion);
        if (item.getLifecycleState() == ReferenceLifecycle.RETIRED) {
            return getSet(tenantId, set.getSetKey());
        }
        if (itemRepository
                .existsByTenantIdAndReferenceSetIdAndParentReferenceItemIdAndLifecycleState(
                        tenantId,
                        set.getReferenceSetId(),
                        item.getReferenceItemId(),
                        ReferenceLifecycle.ACTIVE)) {
            throw conflict("Active child reference items must be retired first.");
        }
        Map<String, Object> before = itemSnapshot(item);
        item.setLifecycleState(ReferenceLifecycle.RETIRED);
        item = itemRepository.saveAndFlush(item);
        bumpRevision(set);
        setRepository.saveAndFlush(set);
        auditService.success(
                tenantId,
                actorId,
                "reference-item.retired",
                "REFERENCE_ITEM",
                set.getSetKey() + "/" + item.getCode(),
                correlationId,
                before,
                itemSnapshot(item));
        return getSet(tenantId, set.getSetKey());
    }

    @Transactional(readOnly = true)
    public ReferenceDataDtos.RuntimeReferenceSet getRuntimeSet(
            Long tenantId,
            String rawSetKey,
            String requestedLocale) {
        ReferenceSet set = requireSet(tenantId, rawSetKey);
        if (set.getLifecycleState() != ReferenceLifecycle.ACTIVE) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        String locale = normalizeLocale(requestedLocale == null ? "en" : requestedLocale);
        List<ReferenceItem> items = itemRepository.findRuntimeItems(
                tenantId,
                set.getReferenceSetId(),
                Instant.now());
        Map<Long, List<ReferenceItemLabel>> labelsByItem = labelsByItem(tenantId, items);
        List<ReferenceDataDtos.RuntimeReferenceItem> responseItems = items.stream()
                .map(item -> toRuntimeItem(item, labelsByItem.getOrDefault(
                        item.getReferenceItemId(), List.of()), locale))
                .toList();
        return new ReferenceDataDtos.RuntimeReferenceSet(
                set.getSetKey(),
                locale,
                set.getContentRevision(),
                responseItems);
    }

    private Specification<ReferenceSet> tenantSpecification(Long tenantId) {
        return (root, ignored, builder) -> builder.equal(root.get("tenantId"), tenantId);
    }

    private ReferenceSet requireSet(Long tenantId, String rawSetKey) {
        String setKey = normalizeIdentifier(rawSetKey);
        return setRepository.findByTenantIdAndSetKey(tenantId, setKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private ReferenceSet requireMutableSet(Long tenantId, String rawSetKey) {
        ReferenceSet set = requireSet(tenantId, rawSetKey);
        if (set.getLifecycleState() == ReferenceLifecycle.RETIRED) {
            throw conflict("Retired reference sets are immutable.");
        }
        return set;
    }

    private ReferenceItem requireItem(Long tenantId, ReferenceSet set, String rawCode) {
        String code = normalizeIdentifier(rawCode);
        return itemRepository.findByTenantIdAndReferenceSetIdAndCode(
                        tenantId,
                        set.getReferenceSetId(),
                        code)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private void requireItemMutable(ReferenceItem item) {
        if (item.getLifecycleState() == ReferenceLifecycle.RETIRED) {
            throw conflict("Retired reference items are immutable.");
        }
    }

    private void requireVersion(Long actual, Long expected) {
        if (!Objects.equals(actual, expected)) {
            throw conflict("The resource changed after it was loaded. Refresh and try again.");
        }
    }

    private ReferenceItem resolveParent(
            Long tenantId,
            ReferenceSet set,
            String code,
            Long currentItemId,
            String parentCode) {
        if (parentCode == null) return null;
        if (code.equals(parentCode)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An item cannot be its own parent.");
        }

        ReferenceItem parent = itemRepository.findByTenantIdAndReferenceSetIdAndCode(
                        tenantId,
                        set.getReferenceSetId(),
                        parentCode)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Parent code does not exist."));
        Set<Long> visited = new HashSet<>();
        Long ancestorId = parent.getReferenceItemId();
        int ancestorCount = 0;
        while (ancestorId != null) {
            if (Objects.equals(ancestorId, currentItemId) || !visited.add(ancestorId)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Reference item hierarchy cannot contain a cycle.");
            }
            ancestorCount++;
            if (ancestorCount >= MAX_REFERENCE_HIERARCHY_DEPTH) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Reference item hierarchy cannot exceed "
                                + MAX_REFERENCE_HIERARCHY_DEPTH
                                + " levels.");
            }
            ReferenceItem ancestor = itemRepository
                    .findByTenantIdAndReferenceSetIdAndReferenceItemId(
                            tenantId,
                            set.getReferenceSetId(),
                            ancestorId)
                    .orElseThrow(() -> new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "Parent hierarchy is inconsistent."));
            ancestorId = ancestor.getParentReferenceItemId();
        }
        return parent;
    }

    private void validateValidity(Instant validFrom, Instant validTo) {
        if (validFrom != null && validTo != null && !validTo.isAfter(validFrom)) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "validTo must be later than validFrom.");
        }
    }

    private List<ReferenceDataDtos.LocalizedLabelRequest> normalizeLabels(
            List<ReferenceDataDtos.LocalizedLabelRequest> labels) {
        Set<String> locales = new HashSet<>();
        List<ReferenceDataDtos.LocalizedLabelRequest> normalized = new ArrayList<>();
        for (ReferenceDataDtos.LocalizedLabelRequest label : labels) {
            String locale = normalizeLocale(label.locale());
            if (!locales.add(locale)) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "Each locale can be declared only once.");
            }
            normalized.add(new ReferenceDataDtos.LocalizedLabelRequest(
                    locale,
                    label.label().trim(),
                    trimToNull(label.description())));
        }
        return normalized;
    }

    private void replaceLabels(
            Long tenantId,
            Long referenceItemId,
            List<ReferenceDataDtos.LocalizedLabelRequest> labels) {
        labelRepository.deleteByTenantIdAndReferenceItemId(tenantId, referenceItemId);
        labelRepository.flush();
        labelRepository.saveAll(labels.stream()
                .map(label -> ReferenceItemLabel.builder()
                        .tenantId(tenantId)
                        .referenceItemId(referenceItemId)
                        .locale(label.locale())
                        .label(label.label())
                        .description(label.description())
                        .build())
                .toList());
        labelRepository.flush();
    }

    private Map<Long, List<ReferenceItemLabel>> labelsByItem(
            Long tenantId,
            List<ReferenceItem> items) {
        if (items.isEmpty()) return Map.of();
        List<Long> itemIds = items.stream().map(ReferenceItem::getReferenceItemId).toList();
        return labelRepository.findByTenantIdAndReferenceItemIdIn(tenantId, itemIds).stream()
                .collect(Collectors.groupingBy(
                        ReferenceItemLabel::getReferenceItemId,
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private ReferenceDataDtos.ReferenceSetSummary toSummary(ReferenceSet set, long itemCount) {
        return new ReferenceDataDtos.ReferenceSetSummary(
                set.getSetKey(),
                set.getName(),
                set.getDescription(),
                set.getLifecycleState(),
                itemCount,
                set.getContentRevision(),
                set.getVersion(),
                set.getUpdatedAt(),
                set.getUpdatedBy());
    }

    private ReferenceDataDtos.ReferenceSetDetail toDetail(
            ReferenceSet set,
            List<ReferenceItem> items,
            Map<Long, List<ReferenceItemLabel>> labelsByItem) {
        return new ReferenceDataDtos.ReferenceSetDetail(
                set.getSetKey(),
                set.getName(),
                set.getDescription(),
                set.getLifecycleState(),
                set.getContentRevision(),
                set.getVersion(),
                set.getUpdatedAt(),
                set.getUpdatedBy(),
                items.stream()
                        .map(item -> toItemResponse(
                                item,
                                labelsByItem.getOrDefault(item.getReferenceItemId(), List.of())))
                        .toList());
    }

    private ReferenceDataDtos.ReferenceItemResponse toItemResponse(
            ReferenceItem item,
            List<ReferenceItemLabel> labels) {
        return new ReferenceDataDtos.ReferenceItemResponse(
                item.getCode(),
                item.getLifecycleState(),
                item.getSortOrder(),
                item.getParentCode(),
                item.getValidFrom(),
                item.getValidTo(),
                labels.stream()
                        .sorted(Comparator.comparing(ReferenceItemLabel::getLocale))
                        .map(label -> new ReferenceDataDtos.ReferenceLabelResponse(
                                label.getLocale(),
                                label.getLabel(),
                                label.getDescription()))
                        .toList(),
                item.getVersion(),
                item.getUpdatedAt(),
                item.getUpdatedBy());
    }

    private ReferenceDataDtos.RuntimeReferenceItem toRuntimeItem(
            ReferenceItem item,
            List<ReferenceItemLabel> labels,
            String requestedLocale) {
        ReferenceItemLabel label = selectLabel(labels, requestedLocale);
        return new ReferenceDataDtos.RuntimeReferenceItem(
                item.getCode(),
                label == null ? item.getCode() : label.getLabel(),
                label == null ? null : label.getDescription(),
                item.getSortOrder(),
                item.getParentCode());
    }

    private ReferenceItemLabel selectLabel(
            List<ReferenceItemLabel> labels,
            String requestedLocale) {
        if (labels.isEmpty()) return null;
        Map<String, ReferenceItemLabel> indexed = labels.stream()
                .collect(Collectors.toMap(
                        ReferenceItemLabel::getLocale,
                        Function.identity(),
                        (first, ignored) -> first));
        ReferenceItemLabel exact = indexed.get(requestedLocale);
        if (exact != null) return exact;
        String language = Locale.forLanguageTag(requestedLocale).getLanguage();
        ReferenceItemLabel languageMatch = labels.stream()
                .filter(label -> Locale.forLanguageTag(label.getLocale())
                        .getLanguage().equals(language))
                .findFirst()
                .orElse(null);
        if (languageMatch != null) return languageMatch;
        if (indexed.containsKey("en")) return indexed.get("en");
        if (indexed.containsKey("ko")) return indexed.get("ko");
        return labels.get(0);
    }

    private String normalizeIdentifier(String value) {
        if (value == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_.-]{0,79}")) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Invalid reference identifier.");
        }
        return normalized;
    }

    private String normalizeOptionalIdentifier(String value) {
        return value == null || value.isBlank() ? null : normalizeIdentifier(value);
    }

    private String normalizeLocale(String value) {
        if (value == null || value.isBlank()) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Locale is required.");
        }
        Locale locale;
        try {
            locale = new Locale.Builder()
                    .setLanguageTag(value.trim().replace('_', '-'))
                    .build();
        } catch (IllformedLocaleException exception) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Invalid locale.", exception);
        }
        if (locale.getLanguage().isBlank() || "und".equals(locale.toLanguageTag())) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Invalid locale.");
        }
        String normalized = locale.toLanguageTag();
        if (normalized.length() > 35) {
            throw new BaseException(ErrorCode.INVALID_FORMAT, "Locale is too long.");
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void bumpRevision(ReferenceSet set) {
        set.setContentRevision(set.getContentRevision() + 1L);
    }

    private BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }
}
