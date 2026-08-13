package com.dwp.services.platform.localization;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LocalizationService {

    private static final Pattern ENTRY_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,179}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}|\\{([A-Za-z][A-Za-z0-9_.-]*)}");
    private static final int MAX_TOTAL_BYTES = 1_000_000;

    private final LocalizationRepository repository;
    private final PlatformAuditService auditService;
    private final ObjectMapper objectMapper;

    public LocalizationService(
            LocalizationRepository repository,
            PlatformAuditService auditService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public LocalizationDtos.Workspace workspace(Long tenantId) {
        List<LocalizationDtos.BundleSummary> bundles = repository.bundles(tenantId).stream()
                .map(this::summary)
                .toList();
        return new LocalizationDtos.Workspace(
                bundles.size(),
                countState(bundles, "DRAFT"),
                countState(bundles, "IN_REVIEW") + countState(bundles, "APPROVED"),
                bundles.stream().filter(item -> item.currentPublishedRevisionId() != null).count(),
                bundles.stream().mapToLong(LocalizationDtos.BundleSummary::issueCount).sum(),
                bundles);
    }

    @Transactional
    public LocalizationDtos.Revision createBundle(
            Long tenantId,
            Long actorId,
            String correlationId,
            LocalizationDtos.CreateBundleRequest request) {
        String sourceLocale = normalizeLocale(request.sourceLocale());
        String targetLocale = normalizeLocale(request.targetLocale());
        if (sourceLocale.equalsIgnoreCase(targetLocale)) {
            throw invalid("Source and target locales must be different.");
        }
        Content content = content(request.sourceEntries(), request.entries());
        LocalizationRepository.StoredBundle bundle = repository.createBundle(
                tenantId, actorId, request.bundleKey().trim(), sourceLocale, targetLocale,
                content.sourceEntries(), content.entries(), request.changeSummary().trim(),
                hash(content.sourceEntries(), content.entries()));
        LocalizationRepository.StoredRevision created = repository.requireRevision(
                tenantId, Objects.requireNonNull(bundle.openRevisionId()));
        auditService.success(
                tenantId, actorId, "localization.bundle.created", "LOCALIZATION_BUNDLE",
                bundle.bundleId().toString(), correlationId, null, auditSnapshot(created));
        return revision(created);
    }

    @Transactional(readOnly = true)
    public List<LocalizationDtos.Revision> revisions(Long tenantId, UUID bundleId) {
        return repository.revisions(tenantId, bundleId).stream().map(this::revision).toList();
    }

    @Transactional(readOnly = true)
    public LocalizationDtos.Revision revision(Long tenantId, UUID revisionId) {
        return revision(repository.requireRevision(tenantId, revisionId));
    }

    @Transactional
    public LocalizationDtos.Revision createDraft(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID bundleId,
            LocalizationDtos.RestoreRequest request) {
        LocalizationRepository.StoredBundle bundle = repository.requireBundle(tenantId, bundleId);
        if (bundle.currentPublishedRevisionId() == null) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Publish the initial localization revision before creating a new draft.");
        }
        LocalizationRepository.StoredRevision source = repository.requireRevision(
                tenantId, bundle.currentPublishedRevisionId());
        LocalizationRepository.StoredRevision created = repository.createDraft(
                tenantId, actorId, source, request.changeSummary().trim(),
                hash(source.sourceEntries(), source.entries()));
        auditService.success(
                tenantId, actorId, "localization.revision.created", "LOCALIZATION_REVISION",
                created.revisionId().toString(), correlationId, null, auditSnapshot(created));
        return revision(created);
    }

    @Transactional
    public LocalizationDtos.Revision saveDraft(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            LocalizationDtos.SaveDraftRequest request) {
        LocalizationRepository.StoredRevision before = repository.requireRevision(tenantId, revisionId);
        requireState(before, "DRAFT");
        Content content = content(request.sourceEntries(), request.entries());
        LocalizationRepository.StoredRevision saved = repository.updateDraft(
                tenantId, actorId, revisionId, request.version(),
                content.sourceEntries(), content.entries(), request.changeSummary().trim(),
                hash(content.sourceEntries(), content.entries()));
        auditService.success(
                tenantId, actorId, "localization.revision.updated", "LOCALIZATION_REVISION",
                revisionId.toString(), correlationId, auditSnapshot(before), auditSnapshot(saved));
        return revision(saved);
    }

    @Transactional
    public LocalizationDtos.Revision submit(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            LocalizationDtos.TransitionRequest request) {
        LocalizationRepository.StoredRevision before = repository.requireRevision(tenantId, revisionId);
        requireState(before, "DRAFT");
        LocalizationDtos.Preview preview = preview(before.sourceEntries(), before.entries());
        if (!preview.publishable()) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Resolve missing, unknown, and placeholder validation issues before review.");
        }
        LocalizationRepository.StoredRevision submitted = repository.submit(
                tenantId, actorId, revisionId, request.version(), request.reason().trim());
        auditService.success(
                tenantId, actorId, "localization.revision.submitted", "LOCALIZATION_REVISION",
                revisionId.toString(), correlationId, auditSnapshot(before), auditSnapshot(submitted));
        return revision(submitted);
    }

    @Transactional
    public LocalizationDtos.Revision decide(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            LocalizationDtos.DecisionRequest request) {
        LocalizationRepository.StoredRevision before = repository.requireRevision(tenantId, revisionId);
        requireState(before, "IN_REVIEW");
        if (Objects.equals(before.submittedBy(), actorId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The revision submitter cannot approve or reject their own change.");
        }
        LocalizationRepository.StoredRevision decided = repository.decide(
                tenantId, actorId, revisionId, request.version(),
                request.decision(), request.reason().trim());
        auditService.success(
                tenantId, actorId, "localization.revision.decided", "LOCALIZATION_REVISION",
                revisionId.toString(), correlationId, auditSnapshot(before), auditSnapshot(decided));
        return revision(decided);
    }

    @Transactional
    public LocalizationDtos.Revision publish(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            LocalizationDtos.TransitionRequest request) {
        LocalizationRepository.StoredRevision before = repository.requireRevision(tenantId, revisionId);
        requireState(before, "APPROVED");
        if (Objects.equals(before.submittedBy(), actorId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "The revision submitter cannot publish their own change.");
        }
        if (!preview(before.sourceEntries(), before.entries()).publishable()) {
            throw new BaseException(ErrorCode.INVALID_STATE, "The approved revision is no longer publishable.");
        }
        LocalizationRepository.StoredRevision published = repository.publish(
                tenantId, actorId, revisionId, request.version(), request.reason().trim());
        auditService.success(
                tenantId, actorId, "localization.revision.published", "LOCALIZATION_REVISION",
                revisionId.toString(), correlationId, auditSnapshot(before), auditSnapshot(published));
        return revision(published);
    }

    @Transactional
    public LocalizationDtos.Revision restore(
            Long tenantId,
            Long actorId,
            String correlationId,
            UUID revisionId,
            LocalizationDtos.RestoreRequest request) {
        LocalizationRepository.StoredRevision source = repository.requireRevision(tenantId, revisionId);
        if (!Set.of("PUBLISHED", "SUPERSEDED", "REJECTED").contains(source.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Only a closed localization revision can be restored as a new draft.");
        }
        LocalizationRepository.StoredRevision restored = repository.restore(
                tenantId, actorId, source, request.changeSummary().trim(),
                hash(source.sourceEntries(), source.entries()));
        auditService.success(
                tenantId, actorId, "localization.revision.restored", "LOCALIZATION_REVISION",
                restored.revisionId().toString(), correlationId,
                auditSnapshot(source), auditSnapshot(restored));
        return revision(restored);
    }

    @Transactional(readOnly = true)
    public LocalizationDtos.Diff diff(Long tenantId, UUID revisionId) {
        LocalizationRepository.StoredRevision current = repository.requireRevision(tenantId, revisionId);
        LocalizationRepository.StoredRevision baseline = current.basedOnRevisionId() == null
                ? null : repository.requireRevision(tenantId, current.basedOnRevisionId());
        Map<String, String> before = baseline == null ? Map.of() : baseline.entries();
        Set<String> keys = new LinkedHashSet<>(current.sourceEntries().keySet());
        keys.addAll(before.keySet());
        keys.addAll(current.entries().keySet());
        List<LocalizationDtos.DiffEntry> entries = new ArrayList<>();
        for (String key : keys.stream().sorted().toList()) {
            String beforeValue = before.get(key);
            String afterValue = current.entries().get(key);
            String changeType;
            if (beforeValue == null && afterValue != null) changeType = "ADDED";
            else if (beforeValue != null && afterValue == null) changeType = "REMOVED";
            else if (!Objects.equals(beforeValue, afterValue)) changeType = "UPDATED";
            else changeType = "UNCHANGED";
            entries.add(new LocalizationDtos.DiffEntry(
                    key, changeType, current.sourceEntries().get(key), beforeValue, afterValue,
                    afterValue == null || afterValue.isBlank()));
        }
        return new LocalizationDtos.Diff(
                revisionId, baseline == null ? null : baseline.revisionId(),
                countDiff(entries, "ADDED"), countDiff(entries, "UPDATED"),
                countDiff(entries, "REMOVED"), countDiff(entries, "UNCHANGED"), entries);
    }

    LocalizationDtos.Preview preview(Map<String, String> sourceEntries, Map<String, String> entries) {
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        List<LocalizationDtos.PlaceholderIssue> placeholderIssues = new ArrayList<>();
        for (Map.Entry<String, String> source : sourceEntries.entrySet()) {
            String translated = entries.get(source.getKey());
            if (translated == null || translated.isBlank()) {
                missing.add(source.getKey());
                fallback.add(source.getKey());
                resolved.put(source.getKey(), source.getValue());
                continue;
            }
            resolved.put(source.getKey(), translated);
            List<String> expected = placeholders(source.getValue());
            List<String> actual = placeholders(translated);
            if (!expected.equals(actual)) {
                placeholderIssues.add(new LocalizationDtos.PlaceholderIssue(
                        source.getKey(), expected, actual));
            }
        }
        List<String> unknown = entries.keySet().stream()
                .filter(key -> !sourceEntries.containsKey(key)).sorted().toList();
        missing.sort(String::compareTo);
        fallback.sort(String::compareTo);
        placeholderIssues.sort(Comparator.comparing(LocalizationDtos.PlaceholderIssue::key));
        double completeness = sourceEntries.isEmpty()
                ? 0
                : Math.round((sourceEntries.size() - missing.size()) * 10_000d
                / sourceEntries.size()) / 100d;
        return new LocalizationDtos.Preview(
                Map.copyOf(resolved), List.copyOf(missing), List.copyOf(fallback), unknown,
                List.copyOf(placeholderIssues), completeness,
                missing.isEmpty() && unknown.isEmpty() && placeholderIssues.isEmpty());
    }

    private LocalizationDtos.BundleSummary summary(LocalizationRepository.StoredBundle bundle) {
        LocalizationRepository.StoredRevision visible = null;
        if (bundle.openRevisionId() != null) {
            visible = repository.requireRevision(bundle.tenantId(), bundle.openRevisionId());
        } else if (bundle.currentPublishedRevisionId() != null) {
            visible = repository.requireRevision(bundle.tenantId(), bundle.currentPublishedRevisionId());
        }
        LocalizationDtos.Preview preview = visible == null
                ? new LocalizationDtos.Preview(Map.of(), List.of(), List.of(), List.of(), List.of(), 0, false)
                : preview(visible.sourceEntries(), visible.entries());
        long issues = preview.missingKeys().size()
                + preview.unknownKeys().size()
                + preview.placeholderIssues().size();
        return new LocalizationDtos.BundleSummary(
                bundle.bundleId(), bundle.bundleKey(), bundle.sourceLocale(), bundle.targetLocale(),
                bundle.lifecycleState(), bundle.currentPublishedRevisionId(),
                bundle.currentPublishedRevisionNumber(), bundle.openRevisionState(),
                bundle.openRevisionNumber(), preview.completeness(), issues,
                bundle.version(), bundle.updatedAt());
    }

    private LocalizationDtos.Revision revision(LocalizationRepository.StoredRevision item) {
        return new LocalizationDtos.Revision(
                item.revisionId(), item.bundleId(), item.bundleKey(), item.sourceLocale(),
                item.targetLocale(), item.revisionNumber(), item.basedOnRevisionId(),
                item.sourceEntries(), item.entries(), item.lifecycleState(), item.changeSummary(),
                item.contentSha256(), item.submittedBy(), item.submittedAt(), item.decidedBy(),
                item.decidedAt(), item.publishedBy(), item.publishedAt(), item.version(),
                item.createdAt(), item.createdBy(), item.updatedAt(),
                repository.decisions(item.tenantId(), item.revisionId()),
                preview(item.sourceEntries(), item.entries()));
    }

    private Content content(Map<String, String> sourceEntries, Map<String, String> entries) {
        Map<String, String> source = normalizeEntries(sourceEntries, false);
        Map<String, String> translated = normalizeEntries(entries, true);
        try {
            if (objectMapper.writeValueAsBytes(Map.of("source", source, "target", translated)).length
                    > MAX_TOTAL_BYTES) {
                throw invalid("Localization content exceeds the configured size limit.");
            }
        } catch (JsonProcessingException exception) {
            throw invalid("Localization content is invalid.");
        }
        return new Content(source, translated);
    }

    private Map<String, String> normalizeEntries(Map<String, String> values, boolean allowBlank) {
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            if (!ENTRY_KEY.matcher(key).matches()) throw invalid("Localization entry keys are invalid.");
            if (!allowBlank && value.isBlank()) throw invalid("Source localization values cannot be blank.");
            if (value.length() > 10_000) throw invalid("A localization value exceeds the configured limit.");
            normalized.put(key, value);
        }
        if (normalized.isEmpty() && !allowBlank) throw invalid("At least one source entry is required.");
        return Map.copyOf(normalized);
    }

    private List<String> placeholders(String value) {
        Set<String> placeholders = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value == null ? "" : value);
        while (matcher.find()) {
            placeholders.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        }
        return placeholders.stream().sorted().toList();
    }

    private String hash(Map<String, String> sourceEntries, Map<String, String> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] content = objectMapper.writeValueAsString(
                    Map.of("sourceEntries", new TreeMap<>(sourceEntries),
                            "entries", new TreeMap<>(entries)))
                    .getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Localization hashing failed.", exception);
        }
    }

    private Map<String, Object> auditSnapshot(LocalizationRepository.StoredRevision item) {
        return Map.ofEntries(
                Map.entry("revisionId", item.revisionId()),
                Map.entry("bundleId", item.bundleId()),
                Map.entry("bundleKey", item.bundleKey()),
                Map.entry("revisionNumber", item.revisionNumber()),
                Map.entry("lifecycleState", item.lifecycleState()),
                Map.entry("sourceEntryCount", item.sourceEntries().size()),
                Map.entry("translatedEntryCount", item.entries().size()),
                Map.entry("contentSha256", item.contentSha256()),
                Map.entry("version", item.version()));
    }

    private void requireState(LocalizationRepository.StoredRevision revision, String state) {
        if (!state.equals(revision.lifecycleState())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "Localization revision is not in the required " + state + " state.");
        }
    }

    private long countState(List<LocalizationDtos.BundleSummary> bundles, String state) {
        return bundles.stream().filter(item -> state.equals(item.openRevisionState())).count();
    }

    private long countDiff(List<LocalizationDtos.DiffEntry> entries, String state) {
        return entries.stream().filter(item -> state.equals(item.changeType())).count();
    }

    private String normalizeLocale(String locale) {
        return locale.trim().replace('_', '-');
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private record Content(Map<String, String> sourceEntries, Map<String, String> entries) {
    }
}
