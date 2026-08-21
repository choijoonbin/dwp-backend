package com.dwp.services.notification.domain;

import com.dwp.audit.AuditEvent;
import com.dwp.core.audit.AuditOutboxRecorder;
import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.domain.NotificationIdempotencyRepository.Request;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateContent;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDecisionRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateDraftRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplatePreview;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplatePreviewRequest;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateRevision;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateVariant;
import com.dwp.services.notification.domain.NotificationTemplateModels.TemplateWorkspace;
import com.dwp.services.notification.domain.NotificationTemplateRepository.ProviderVariant;
import com.dwp.services.notification.security.NotificationDatabaseScope;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.dwp.services.notification.api.NotificationVersionCodec.nonNegative;
import static com.dwp.services.notification.api.NotificationVersionCodec.positive;

@Service
public class NotificationTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9_]*)}}");
    private static final Pattern UNRESOLVED_DELIMITER = Pattern.compile("[{}]");

    private final NotificationDatabaseScope databaseScope;
    private final NotificationTemplateRepository repository;
    private final NotificationIdempotencyRepository idempotencyRepository;
    private final AuditOutboxRecorder audit;

    public NotificationTemplateService(
            NotificationDatabaseScope databaseScope,
            NotificationTemplateRepository repository,
            NotificationIdempotencyRepository idempotencyRepository,
            AuditOutboxRecorder audit) {
        this.databaseScope = databaseScope;
        this.repository = repository;
        this.idempotencyRepository = idempotencyRepository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TemplateWorkspace workspace(NotificationRequestContext.Actor actor) {
        databaseScope.applyWorker(actor.tenantId());
        List<TemplateRevision> revisions = repository.revisions(actor.tenantId());
        List<TemplateVariant> items = repository.providerVariants(actor.tenantId()).stream()
                .map(provider -> variant(provider, revisions))
                .toList();
        return new TemplateWorkspace(items, Instant.now());
    }

    @Transactional(readOnly = true)
    public TemplatePreview preview(
            NotificationRequestContext.Actor actor,
            TemplatePreviewRequest request) {
        databaseScope.applyWorker(actor.tenantId());
        ProviderVariant provider = requireProviderVariant(actor.tenantId(), request);
        TemplateContent content = content(request);
        List<String> variables = validateContent(provider, content);
        Map<String, String> samples = sampleValues(variables, request.sampleData());
        List<String> warnings = new ArrayList<>();
        if (content.preview().isBlank()) warnings.add("EMPTY_PREVIEW");
        return new TemplatePreview(render(content, samples), variables, List.copyOf(warnings));
    }

    @Transactional
    public TemplateRevision createDraft(
            NotificationRequestContext.Actor actor,
            TemplateDraftRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        ProviderVariant provider = requireProviderVariant(actor.tenantId(), request);
        TemplateContent content = content(request);
        validateContent(provider, content);
        Request receipt = idempotencyRepository.begin(
                actor, idempotencyKey, "TENANT_NOTIFICATION_TEMPLATE_DRAFT", request);
        TemplateRevision replay = idempotencyRepository.replay(receipt, TemplateRevision.class);
        if (replay != null) return replay;
        long currentVersion = repository.latestRevision(
                actor.tenantId(), request.typeVersionId(), request.channel(), request.locale());
        if (nonNegative(request.expectedVersion(), "expectedVersion") != currentVersion) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        try {
            TemplateRevision result = repository.createDraft(
                    actor.tenantId(),
                    actor.userId(),
                    request,
                    Math.toIntExact(currentVersion + 1),
                    checksum(request, content));
            record(actor, "notification.template.draft.created", result, request.changeReason());
            idempotencyRepository.complete(actor, receipt, result);
            return result;
        } catch (DuplicateKeyException exception) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
    }

    @Transactional
    public TemplateRevision publish(
            NotificationRequestContext.Actor actor,
            UUID revisionId,
            TemplateDecisionRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "TENANT_NOTIFICATION_TEMPLATE_PUBLISH",
                Map.of("revisionId", revisionId, "request", request));
        TemplateRevision replay = idempotencyRepository.replay(receipt, TemplateRevision.class);
        if (replay != null) return replay;
        TemplateRevision draft = requireDraft(actor.tenantId(), revisionId);
        if (draft.createdBy() != null && draft.createdBy().equals(actor.userId())) {
            throw new NotificationException(
                    NotificationErrorCode.FORBIDDEN,
                    "A notification template author cannot publish the same revision.");
        }
        int expected = Math.toIntExact(positive(request.expectedVersion(), "expectedVersion"));
        if (!repository.publish(
                actor.tenantId(), actor.userId(), revisionId, expected, request.reason())) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        TemplateRevision result = repository.revision(actor.tenantId(), revisionId).orElseThrow();
        record(actor, "notification.template.published", result, request.reason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    @Transactional
    public TemplateRevision retireDraft(
            NotificationRequestContext.Actor actor,
            UUID revisionId,
            TemplateDecisionRequest request,
            String idempotencyKey) {
        databaseScope.applyWorker(actor.tenantId());
        Request receipt = idempotencyRepository.begin(
                actor,
                idempotencyKey,
                "TENANT_NOTIFICATION_TEMPLATE_DRAFT_RETIRE",
                Map.of("revisionId", revisionId, "request", request));
        TemplateRevision replay = idempotencyRepository.replay(receipt, TemplateRevision.class);
        if (replay != null) return replay;
        requireDraft(actor.tenantId(), revisionId);
        int expected = Math.toIntExact(positive(request.expectedVersion(), "expectedVersion"));
        if (!repository.retireDraft(actor.tenantId(), revisionId, expected)) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        TemplateRevision result = repository.revision(actor.tenantId(), revisionId).orElseThrow();
        record(actor, "notification.template.draft.retired", result, request.reason());
        idempotencyRepository.complete(actor, receipt, result);
        return result;
    }

    private TemplateVariant variant(
            ProviderVariant provider,
            List<TemplateRevision> revisions) {
        List<TemplateRevision> matching = revisions.stream()
                .filter(revision -> revision.typeVersionId().equals(provider.typeVersionId())
                        && revision.channel().equals(provider.channel())
                        && revision.locale().equals(provider.locale()))
                .toList();
        TemplateRevision published = matching.stream()
                .filter(revision -> "PUBLISHED".equals(revision.state()))
                .max(Comparator.comparingInt(TemplateRevision::revision))
                .orElse(null);
        TemplateRevision draft = matching.stream()
                .filter(revision -> "DRAFT".equals(revision.state()))
                .findFirst()
                .orElse(null);
        return new TemplateVariant(
                provider.typeVersionId(),
                provider.typeKey(),
                displayName(provider.typeKey()),
                provider.appKey(),
                NotificationQueryRepository.appName(provider.appKey()),
                provider.channel(),
                provider.locale(),
                variables(provider.variableMaterial()),
                Integer.toString(matching.stream()
                        .mapToInt(TemplateRevision::revision)
                        .max()
                        .orElse(0)),
                provider.content(),
                published,
                draft,
                matching);
    }

    private ProviderVariant requireProviderVariant(long tenantId, TemplatePreviewRequest request) {
        return repository.providerVariant(
                        tenantId, request.typeVersionId(), request.channel(), request.locale())
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_CONTRACT_QUARANTINED));
    }

    private ProviderVariant requireProviderVariant(long tenantId, TemplateDraftRequest request) {
        return repository.providerVariant(
                        tenantId, request.typeVersionId(), request.channel(), request.locale())
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_CONTRACT_QUARANTINED));
    }

    private TemplateRevision requireDraft(long tenantId, UUID revisionId) {
        TemplateRevision draft = repository.revision(tenantId, revisionId)
                .orElseThrow(() -> new NotificationException(
                        NotificationErrorCode.NOTIFICATION_NOT_FOUND));
        if (!"DRAFT".equals(draft.state())) {
            throw new NotificationException(NotificationErrorCode.NOTIFICATION_STALE_VERSION);
        }
        return draft;
    }

    private List<String> validateContent(ProviderVariant provider, TemplateContent content) {
        if (containsMarkup(content) || containsMalformedDelimiter(content)) {
            throw new NotificationException(
                    NotificationErrorCode.INVALID_INPUT,
                    "Notification templates accept plain text and validated placeholders only.");
        }
        List<String> allowed = variables(provider.variableMaterial());
        Set<String> requested = new LinkedHashSet<>();
        requested.addAll(variables(content.title()));
        requested.addAll(variables(content.preview()));
        requested.addAll(variables(content.body()));
        requested.addAll(variables(content.actionLabel()));
        if (!allowed.containsAll(requested)) {
            Set<String> unknown = new LinkedHashSet<>(requested);
            unknown.removeAll(allowed);
            throw new NotificationException(
                    NotificationErrorCode.INVALID_INPUT,
                    "Unknown notification template variables: " + String.join(", ", unknown));
        }
        return List.copyOf(requested);
    }

    private boolean containsMarkup(TemplateContent content) {
        return List.of(content.title(), content.preview(), content.body(), content.actionLabel())
                .stream()
                .anyMatch(value -> value.contains("<") || value.contains(">"));
    }

    private boolean containsMalformedDelimiter(TemplateContent content) {
        return List.of(content.title(), content.preview(), content.body(), content.actionLabel())
                .stream()
                .map(value -> PLACEHOLDER.matcher(value).replaceAll(""))
                .anyMatch(value -> UNRESOLVED_DELIMITER.matcher(value).find());
    }

    private List<String> variables(String material) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = PLACEHOLDER.matcher(material == null ? "" : material);
        while (matcher.find()) values.add(matcher.group(1));
        return values.stream().sorted().toList();
    }

    private Map<String, String> sampleValues(
            List<String> variables,
            Map<String, String> requested) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String variable : variables) {
            String value = requested.get(variable);
            result.put(variable, value == null || value.isBlank() ? synthetic(variable) : value);
        }
        return Map.copyOf(result);
    }

    private String synthetic(String variable) {
        String normalized = variable.toLowerCase(Locale.ROOT);
        if (normalized.contains("name")) return "김민서";
        if (normalized.contains("title")) return "검토가 필요한 업무";
        if (normalized.contains("message")) return "새로운 업무 메시지가 도착했습니다.";
        if (normalized.endsWith("id")) return "sample-001";
        return "예시 " + variable;
    }

    private TemplateContent render(TemplateContent content, Map<String, String> values) {
        return new TemplateContent(
                render(content.title(), values),
                render(content.preview(), values),
                render(content.body(), values),
                render(content.actionLabel(), values));
    }

    private String render(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(
                    rendered,
                    Matcher.quoteReplacement(values.getOrDefault(matcher.group(1), "")));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private TemplateContent content(TemplatePreviewRequest request) {
        return new TemplateContent(
                request.title().trim(),
                request.preview() == null ? "" : request.preview().trim(),
                request.body().trim(),
                normalizeOptional(request.actionLabel()));
    }

    private TemplateContent content(TemplateDraftRequest request) {
        return new TemplateContent(
                request.title().trim(),
                request.preview() == null ? "" : request.preview().trim(),
                request.body().trim(),
                normalizeOptional(request.actionLabel()));
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }

    private String checksum(TemplateDraftRequest request, TemplateContent content) {
        String material = String.join("\u0000",
                request.typeVersionId().toString(),
                request.channel(),
                request.locale(),
                content.title(),
                content.preview(),
                content.body(),
                content.actionLabel());
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String displayName(String typeKey) {
        String leaf = typeKey.substring(typeKey.lastIndexOf('.') + 1).replace('_', ' ');
        String normalized = leaf.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private void record(
            NotificationRequestContext.Actor actor,
            String action,
            TemplateRevision revision,
            String reason) {
        audit.record(AuditEvent.builder()
                .tenantId(actor.tenantId())
                .category("ADMIN_CHANGE")
                .action(action)
                .outcome("SUCCESS")
                .severity("MEDIUM")
                .riskScore("notification.template.published".equals(action) ? 55 : 35)
                .actorType("USER")
                .actorId(actor.userId().toString())
                .actorRoles(List.copyOf(actor.roles()))
                .sourceService("dwp-notification-server")
                .sourceModule("notification-template-governance")
                .targetType("NOTIFICATION_TEMPLATE_REVISION")
                .targetId(revision.revisionId().toString())
                .targetDisplayName(revision.typeKey() + ":" + revision.locale())
                .reason(reason)
                .policyDecision("notification.template.published".equals(action)
                        ? "ALLOW"
                        : "NOT_APPLICABLE")
                .afterState(Map.of(
                        "typeKey", revision.typeKey(),
                        "channel", revision.channel(),
                        "locale", revision.locale(),
                        "state", revision.state(),
                        "version", revision.version(),
                        "checksum", revision.checksum()))
                .retentionClass("EXTENDED")
                .build());
    }
}
