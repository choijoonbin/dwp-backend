package com.dwp.services.platform.home.personalization;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class HomeTemplateService {
    private static final int MAX_TEMPLATES_PER_TENANT = 100;
    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String REVOKED = "REVOKED";

    private final HomeTemplateRepository templates;
    private final HomeViewService views;
    private final HomePreferenceService preferenceService;
    private final HomePersonalizationAccess access;
    private final PlatformAuditService audit;
    private final ObjectMapper objectMapper;
    private final HomeTemplateRevisionRepository revisions;
    private final HomeCommandReceiptService commandReceipts;
    private final HomeTemplateScopeLock scopeLock;

    public HomeTemplateService(
            HomeTemplateRepository templates,
            HomeViewService views,
            HomePreferenceService preferenceService,
            HomePersonalizationAccess access,
            PlatformAuditService audit,
            ObjectMapper objectMapper,
            HomeTemplateRevisionRepository revisions,
            HomeCommandReceiptService commandReceipts,
            HomeTemplateScopeLock scopeLock) {
        this.templates = templates;
        this.views = views;
        this.preferenceService = preferenceService;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.revisions = revisions;
        this.commandReceipts = commandReceipts;
        this.scopeLock = scopeLock;
    }

    @Transactional(readOnly = true)
    public List<HomeTemplateDtos.HomeTemplateResponse> list(
            Long tenantId, String permissions, String roles) {
        access.requirePersonalization();
        List<HomeTemplate> result = access.canViewDraftTemplates(permissions)
                ? templates.findTop100ByTenantIdOrderByUpdatedAtDesc(tenantId)
                : templates.findTop100ByTenantIdAndLifecycleStateOrderByUpdatedAtDesc(
                        tenantId, PUBLISHED);
        Set<String> userRoles = access.roles(roles);
        return result.stream().filter(value -> access.canViewDraftTemplates(permissions)
                        || audienceAllows(value, userRoles))
                .map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public HomeTemplateDtos.HomeTemplateResponse get(
            Long tenantId, UUID templateId, String permissions, String roles) {
        access.requirePersonalization();
        HomeTemplate template = requireTemplate(tenantId, templateId);
        if (!access.canViewDraftTemplates(permissions)
                && (!PUBLISHED.equals(template.getLifecycleState())
                || !audienceAllows(template, access.roles(roles)))) {
            throw new BaseException(ErrorCode.NOT_FOUND);
        }
        return response(template);
    }

    @Transactional(readOnly = true)
    public List<HomeTemplateDtos.HomeTemplateRevisionResponse> revisions(
            Long tenantId, UUID templateId, String permissions) {
        access.requirePersonalization();
        if (!access.canViewDraftTemplates(permissions)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        requireTemplate(tenantId, templateId);
        return revisions.findTop50ByTemplateIdAndTenantIdOrderByRevisionNumberDesc(
                        templateId, tenantId).stream()
                .map(this::revisionResponse)
                .toList();
    }

    @Transactional
    public HomeTemplateDtos.HomeTemplateResponse create(
            Long tenantId,
            Long actorId,
            String permissions,
            UUID commandId,
            String correlationId,
            HomeTemplateDtos.CreateHomeTemplateRequest request) {
        access.requireTemplateManage(permissions);
        String fingerprint = views.fingerprint(Map.of(
                "operation", "CREATE_TEMPLATE", "templateKey", request.templateKey(),
                "request", request));
        String target = "template-key:" + request.templateKey();
        HomeTemplateDtos.HomeTemplateResponse replay = commandReceipts.replay(
                tenantId, actorId, commandId, "CREATE_TEMPLATE", target,
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        views.requirePolicy(tenantId, HomePreferenceService.WORKSPACE_HOME);
        scopeLock.lock(tenantId);
        replay = commandReceipts.replay(
                tenantId, actorId, commandId, "CREATE_TEMPLATE", target,
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        HomeTemplateDtos.TemplateAudience audience = normalizedAudience(request.audience());
        if (templates.countByTenantId(tenantId) >= MAX_TEMPLATES_PER_TENANT) {
            throw invalid("A tenant can own up to one hundred home templates.");
        }
        HomePreferenceDtos.HomeLayoutPayload layout = preferenceService.normalizeForSurface(
                HomePreferenceService.WORKSPACE_HOME, request.layout());
        HomeTemplate template = HomeTemplate.builder()
                .templateId(UUID.randomUUID()).tenantId(tenantId)
                .templateKey(request.templateKey()).name(request.name().trim())
                .audiencePayload(objectMapper.valueToTree(audience))
                .lifecycleState(DRAFT).schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                .layoutPayload(objectMapper.valueToTree(layout)).build();
        save(template);
        appendRevision(template, "CREATE", commandId, fingerprint, actorId);
        audit.success(tenantId, actorId, "home-template.created", "HOME_TEMPLATE",
                template.getTemplateId().toString(), correlationId, null, snapshot(template));
        HomeTemplateDtos.HomeTemplateResponse result = response(template);
        commandReceipts.record(tenantId, actorId, commandId, "CREATE_TEMPLATE",
                target, fingerprint, result);
        return result;
    }

    @Transactional
    public HomeTemplateDtos.HomeTemplateResponse update(
            Long tenantId,
            Long actorId,
            String permissions,
            UUID templateId,
            UUID commandId,
            String correlationId,
            HomeTemplateDtos.UpdateHomeTemplateRequest request) {
        access.requireTemplateManage(permissions);
        String fingerprint = views.fingerprint(Map.of(
                "operation", "UPDATE_TEMPLATE", "templateId", templateId,
                "request", request));
        HomeTemplateDtos.HomeTemplateResponse replay = commandReceipts.replay(
                tenantId, actorId, commandId, "UPDATE_TEMPLATE", templateId.toString(),
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        views.requirePolicy(tenantId, HomePreferenceService.WORKSPACE_HOME);
        scopeLock.lock(tenantId);
        replay = commandReceipts.replay(
                tenantId, actorId, commandId, "UPDATE_TEMPLATE", templateId.toString(),
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        HomeTemplate template = requireTemplateForUpdate(tenantId, templateId);
        requireVersion(template, request.version());
        if (!DRAFT.equals(template.getLifecycleState())) {
            throw invalid("Only a draft home template can be edited.");
        }
        HomeTemplateDtos.TemplateAudience audience = normalizedAudience(request.audience());
        Object before = snapshot(template);
        HomePreferenceDtos.HomeLayoutPayload layout = preferenceService.normalizeForSurface(
                HomePreferenceService.WORKSPACE_HOME, request.layout());
        template.setName(request.name().trim());
        template.setAudiencePayload(objectMapper.valueToTree(audience));
        template.setLayoutPayload(objectMapper.valueToTree(layout));
        template.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        save(template);
        appendRevision(template, "UPDATE", commandId, fingerprint, actorId);
        audit.success(tenantId, actorId, "home-template.updated", "HOME_TEMPLATE",
                templateId.toString(), correlationId, before, snapshot(template));
        HomeTemplateDtos.HomeTemplateResponse result = response(template);
        commandReceipts.record(tenantId, actorId, commandId, "UPDATE_TEMPLATE",
                templateId.toString(), fingerprint, result);
        return result;
    }

    @Transactional
    public HomeTemplateDtos.HomeTemplateResponse publish(
            Long tenantId,
            Long actorId,
            String permissions,
            UUID templateId,
            UUID commandId,
            String correlationId,
            Long version) {
        access.requireTemplateManage(permissions);
        String fingerprint = views.fingerprint(Map.of(
                "operation", "PUBLISH_TEMPLATE", "templateId", templateId,
                "version", version));
        HomeTemplateDtos.HomeTemplateResponse replay = commandReceipts.replay(
                tenantId, actorId, commandId, "PUBLISH_TEMPLATE", templateId.toString(),
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        views.requirePolicy(tenantId, HomePreferenceService.WORKSPACE_HOME);
        scopeLock.lock(tenantId);
        replay = commandReceipts.replay(
                tenantId, actorId, commandId, "PUBLISH_TEMPLATE", templateId.toString(),
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        HomeTemplate template = requireTemplateForUpdate(tenantId, templateId);
        requireVersion(template, version);
        if (!DRAFT.equals(template.getLifecycleState())) {
            throw invalid("Only a draft home template can be published.");
        }
        Object before = snapshot(template);
        template.setLifecycleState(PUBLISHED);
        template.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
        template.setPublishedBy(actorId);
        save(template);
        appendRevision(template, "PUBLISH", commandId, fingerprint, actorId);
        audit.success(tenantId, actorId, "home-template.published", "HOME_TEMPLATE",
                templateId.toString(), correlationId, before, snapshot(template));
        HomeTemplateDtos.HomeTemplateResponse result = response(template);
        commandReceipts.record(tenantId, actorId, commandId, "PUBLISH_TEMPLATE",
                templateId.toString(), fingerprint, result);
        return result;
    }

    @Transactional
    public HomeTemplateDtos.HomeTemplateResponse revoke(
            Long tenantId,
            Long actorId,
            String permissions,
            UUID templateId,
            UUID commandId,
            String correlationId,
            Long version) {
        access.requireTemplateManage(permissions);
        String fingerprint = views.fingerprint(Map.of(
                "operation", "REVOKE_TEMPLATE", "templateId", templateId,
                "version", version));
        HomeTemplateDtos.HomeTemplateResponse replay = commandReceipts.replay(
                tenantId, actorId, commandId, "REVOKE_TEMPLATE", templateId.toString(),
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        views.requirePolicy(tenantId, HomePreferenceService.WORKSPACE_HOME);
        scopeLock.lock(tenantId);
        replay = commandReceipts.replay(
                tenantId, actorId, commandId, "REVOKE_TEMPLATE", templateId.toString(),
                fingerprint, HomeTemplateDtos.HomeTemplateResponse.class);
        if (replay != null) return replay;
        HomeTemplate template = requireTemplateForUpdate(tenantId, templateId);
        requireVersion(template, version);
        if (!PUBLISHED.equals(template.getLifecycleState())) {
            throw invalid("Only a published home template can be revoked.");
        }
        Object before = snapshot(template);
        template.setLifecycleState(REVOKED);
        save(template);
        appendRevision(template, "REVOKE", commandId, fingerprint, actorId);
        audit.success(tenantId, actorId, "home-template.revoked", "HOME_TEMPLATE",
                templateId.toString(), correlationId, before, snapshot(template));
        HomeTemplateDtos.HomeTemplateResponse result = response(template);
        commandReceipts.record(tenantId, actorId, commandId, "REVOKE_TEMPLATE",
                templateId.toString(), fingerprint, result);
        return result;
    }

    @Transactional
    public HomeViewDtos.HomeViewResponse apply(
            Long tenantId,
            Long userId,
            String roles,
            UUID templateId,
            UUID commandId,
            String correlationId,
            HomeTemplateDtos.ApplyHomeTemplateRequest request) {
        access.requirePersonalization();
        String fingerprint = views.fingerprint(Map.of(
                "operation", "APPLY_TEMPLATE",
                "templateId", templateId,
                "request", request));
        HomeViewDtos.HomeViewResponse replay = commandReceipts.replay(
                tenantId, userId, commandId, "APPLY_TEMPLATE", templateId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        views.lockPersonalizationScopeForView(tenantId, userId, request.viewId());
        scopeLock.lock(tenantId);
        replay = commandReceipts.replay(
                tenantId, userId, commandId, "APPLY_TEMPLATE", templateId.toString(),
                fingerprint, HomeViewDtos.HomeViewResponse.class);
        if (replay != null) return replay;
        HomeTemplate template = requireTemplateForUpdate(tenantId, templateId);
        if (!PUBLISHED.equals(template.getLifecycleState())
                || !audienceAllows(template, access.roles(roles))) {
            throw new BaseException(ErrorCode.FORBIDDEN,
                    "The home template is not available to the current user.");
        }
        HomePreferenceDtos.HomeLayoutPayload layout = layout(template.getLayoutPayload());
        HomeViewDtos.HomeViewResponse result = views.applyExternalLayout(
                tenantId, userId, request.viewId(), request.viewVersion(), layout,
                "TEMPLATE", "Template " + template.getTemplateKey() + " applied",
                commandId, fingerprint, userId, correlationId);
        commandReceipts.record(tenantId, userId, commandId, "APPLY_TEMPLATE",
                templateId.toString(), fingerprint, result);
        return result;
    }

    private boolean audienceAllows(HomeTemplate template, Set<String> roles) {
        HomeTemplateDtos.TemplateAudience audience = audience(template.getAudiencePayload());
        if ("ALL".equals(audience.type())) return true;
        return audience.values() != null && audience.values().stream().anyMatch(roles::contains);
    }

    private void validateAudience(HomeTemplateDtos.TemplateAudience audience) {
        if (audience == null || audience.type() == null) {
            throw invalid("The home template audience is invalid.");
        }
        List<String> values = audience.values() == null ? List.of() : audience.values();
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw invalid("The home template audience is invalid.");
        }
        if (("ALL".equals(audience.type()) && !values.isEmpty())
                || ("ROLE".equals(audience.type())
                && (values.isEmpty() || Set.copyOf(values).size() != values.size()))) {
            throw invalid("The home template audience is invalid.");
        }
    }

    private HomeTemplateDtos.TemplateAudience normalizedAudience(
            HomeTemplateDtos.TemplateAudience audience) {
        validateAudience(audience);
        return new HomeTemplateDtos.TemplateAudience(
                audience.type(),
                "ALL".equals(audience.type()) ? List.of() : List.copyOf(audience.values()));
    }

    private HomeTemplate requireTemplate(Long tenantId, UUID templateId) {
        return templates.findByTemplateIdAndTenantId(templateId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private HomeTemplate requireTemplateForUpdate(Long tenantId, UUID templateId) {
        return templates.findOwnedForUpdate(templateId, tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private HomeTemplateDtos.HomeTemplateResponse response(HomeTemplate value) {
        return new HomeTemplateDtos.HomeTemplateResponse(
                value.getTemplateId(), value.getTemplateKey(), value.getName(),
                normalizedAudience(audience(value.getAudiencePayload())), value.getLifecycleState(),
                value.getSchemaVersion(), layout(value.getLayoutPayload()), version(value),
                value.getPublishedAt(), value.getPublishedBy(),
                value.getUpdatedAt() == null
                        ? null : value.getUpdatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }

    private HomeTemplateDtos.TemplateAudience audience(JsonNode value) {
        try {
            return objectMapper.treeToValue(value, HomeTemplateDtos.TemplateAudience.class);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored home template audience is invalid.", exception);
        }
    }

    private HomePreferenceDtos.HomeLayoutPayload layout(JsonNode value) {
        return views.layout(value);
    }

    private void save(HomeTemplate template) {
        try {
            templates.saveAndFlush(template);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private void appendRevision(
            HomeTemplate template,
            String source,
            UUID commandId,
            String fingerprint,
            Long actorId) {
        long revisionNumber = revisions
                .findTopByTemplateIdOrderByRevisionNumberDesc(template.getTemplateId())
                .map(HomeTemplateRevision::getRevisionNumber)
                .orElse(0L) + 1L;
        try {
            revisions.saveAndFlush(HomeTemplateRevision.builder()
                    .templateRevisionId(UUID.randomUUID())
                    .templateId(template.getTemplateId()).tenantId(template.getTenantId())
                    .revisionNumber(revisionNumber)
                    .snapshot(objectMapper.valueToTree(templateSnapshot(template)))
                    .source(source).commandId(commandId).requestFingerprint(fingerprint)
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC)).createdBy(actorId)
                    .build());
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private HomeTemplateDtos.HomeTemplateRevisionResponse revisionResponse(
            HomeTemplateRevision revision) {
        try {
            return new HomeTemplateDtos.HomeTemplateRevisionResponse(
                    revision.getTemplateRevisionId(), revision.getTemplateId(),
                    revision.getRevisionNumber(), revision.getSource(),
                    objectMapper.treeToValue(
                            revision.getSnapshot(), HomeTemplateDtos.HomeTemplateSnapshot.class),
                    revision.getCreatedAt(), revision.getCreatedBy());
        } catch (JsonProcessingException exception) {
            throw new BaseException(
                    ErrorCode.INTERNAL_SERVER_ERROR,
                    "The stored home template revision is invalid.",
                    exception);
        }
    }

    private HomeTemplateDtos.HomeTemplateSnapshot templateSnapshot(HomeTemplate template) {
        return new HomeTemplateDtos.HomeTemplateSnapshot(
                template.getName(), normalizedAudience(audience(template.getAudiencePayload())),
                template.getLifecycleState(), template.getSchemaVersion(),
                layout(template.getLayoutPayload()), version(template),
                template.getPublishedAt(), template.getPublishedBy());
    }

    private Map<String, Object> snapshot(HomeTemplate template) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("templateId", template.getTemplateId());
        value.put("templateKey", template.getTemplateKey());
        value.put("name", template.getName());
        value.put("audience", template.getAudiencePayload());
        value.put("lifecycle", template.getLifecycleState());
        value.put("schemaVersion", template.getSchemaVersion());
        value.put("layout", template.getLayoutPayload());
        value.put("version", version(template));
        return value;
    }

    private long version(HomeTemplate template) {
        return template.getVersion() == null ? 0L : template.getVersion();
    }

    private void requireVersion(HomeTemplate template, Long expected) {
        if (expected == null || version(template) != expected) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
