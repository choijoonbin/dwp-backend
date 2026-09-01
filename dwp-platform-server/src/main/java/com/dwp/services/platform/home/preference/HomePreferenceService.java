package com.dwp.services.platform.home.preference;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.home.HomeCompositionPolicyReader;
import com.dwp.services.platform.home.personalization.HomeViewCompatibilityBridge;
import com.dwp.services.platform.home.personalization.HomePersonalizationScopeLock;
import com.dwp.services.platform.security.PlatformApprovalsAuthorizationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HomePreferenceService {

    private static final Logger log = LoggerFactory.getLogger(HomePreferenceService.class);

    public static final String WORKSPACE_HOME = HomeSurfaceKeys.WORKSPACE_HOME;
    public static final String HCM_HOME = HomeSurfaceKeys.HCM_HOME;
    public static final String LEGACY_HRIS_HOME = HomeSurfaceKeys.LEGACY_HRIS_HOME;
    public static final String APPROVAL_HOME = HomeSurfaceKeys.APPROVAL_HOME;

    private final HomePreferenceRepository repository;
    private final ObjectMapper objectMapper;
    private final PlatformAuditService auditService;
    private final HomeCompositionPolicyReader compositionPolicyReader;
    private final HomePersonalizationScopeLock scopeLock;
    private final HomeLayoutPolicy layoutPolicy;

    @Autowired(required = false)
    private HomeViewCompatibilityBridge compatibilityBridge;

    @Autowired
    public HomePreferenceService(
            HomePreferenceRepository repository,
            ObjectMapper objectMapper,
            PlatformAuditService auditService,
            HomeCompositionPolicyReader compositionPolicyReader,
            HomePersonalizationScopeLock scopeLock,
            HomeLayoutPolicy layoutPolicy) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.compositionPolicyReader = compositionPolicyReader;
        this.scopeLock = scopeLock;
        this.layoutPolicy = layoutPolicy;
    }

    public HomePreferenceService(
            HomePreferenceRepository repository,
            ObjectMapper objectMapper,
            PlatformAuditService auditService,
            HomeCompositionPolicyReader compositionPolicyReader,
            HomePersonalizationScopeLock scopeLock) {
        this(repository, objectMapper, auditService, compositionPolicyReader, scopeLock,
                new HomeLayoutPolicy(objectMapper));
    }

    @Transactional(readOnly = true)
    public HomePreferenceDtos.HomePreferenceResponse get(
            Long tenantId,
            Long userId,
            String surfaceKey) {
        String canonicalSurfaceKey = layoutPolicy.canonicalSurfaceKey(surfaceKey);
        PlatformApprovalsAuthorizationContext.requireSelf(
                tenantId, userId, canonicalSurfaceKey);
        layoutPolicy.requireRegisteredSurface(canonicalSurfaceKey);
        return repository.findByTenantIdAndUserIdAndSurfaceKey(
                        tenantId, userId, canonicalSurfaceKey)
                .map(preference -> {
                    if (compatibilityBridge != null) compatibilityBridge.shadowCompare(preference);
                    return response(preference);
                })
                .orElseGet(() -> defaultResponse(canonicalSurfaceKey));
    }

    @Transactional
    public HomePreferenceDtos.HomePreferenceResponse update(
            Long tenantId,
            Long userId,
            String surfaceKey,
            String correlationId,
            HomePreferenceDtos.UpdateHomePreferenceRequest request) {
        String canonicalSurfaceKey = layoutPolicy.canonicalSurfaceKey(surfaceKey);
        PlatformApprovalsAuthorizationContext.requireSelf(
                tenantId, userId, canonicalSurfaceKey);
        layoutPolicy.requireRegisteredSurface(canonicalSurfaceKey);
        requirePersonalCustomization(tenantId, canonicalSurfaceKey);
        scopeLock.lock(tenantId, userId, canonicalSurfaceKey);
        HomePreferenceDtos.HomeLayoutPayload normalized =
                layoutPolicy.normalizeForSurface(canonicalSurfaceKey, request.layout());
        java.util.Optional<HomePreference> existing =
                PlatformApprovalsAuthorizationContext.current().isPresent()
                        ? repository.findForUpdate(tenantId, userId, canonicalSurfaceKey)
                        : repository.findByTenantIdAndUserIdAndSurfaceKey(
                                tenantId, userId, canonicalSurfaceKey);
        HomePreference preference = existing.orElseGet(
                () -> create(tenantId, userId, canonicalSurfaceKey, request.version()));
        if (existing.isPresent()) requireVersion(preference, request.version());
        Map<String, Object> before = snapshot(preference);
        preference.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        preference.setLayoutPayload(objectMapper.valueToTree(normalized));
        preference.setCustomized(true);
        HomePreference saved;
        try {
            saved = repository.saveAndFlush(preference);
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (compatibilityBridge != null) compatibilityBridge.mirrorLegacyPreference(saved);
        auditService.success(
                tenantId,
                userId,
                "home-preference.updated",
                "HOME_PREFERENCE",
                userId + ":" + canonicalSurfaceKey,
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public HomePreferenceDtos.HomePreferenceResponse reset(
            Long tenantId,
            Long userId,
            String surfaceKey,
            String correlationId,
            Long version) {
        String canonicalSurfaceKey = layoutPolicy.canonicalSurfaceKey(surfaceKey);
        PlatformApprovalsAuthorizationContext.requireSelf(
                tenantId, userId, canonicalSurfaceKey);
        layoutPolicy.requireRegisteredSurface(canonicalSurfaceKey);
        requirePersonalCustomization(tenantId, canonicalSurfaceKey);
        scopeLock.lock(tenantId, userId, canonicalSurfaceKey);
        HomePreference preference = repository
                .findByTenantIdAndUserIdAndSurfaceKey(tenantId, userId, canonicalSurfaceKey)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(preference, version);
        Map<String, Object> before = snapshot(preference);
        HomePreferenceDtos.HomePreferenceResponse defaults =
                defaultResponse(canonicalSurfaceKey);
        preference.setSchemaVersion(HomePreferenceDtos.SCHEMA_VERSION);
        preference.setLayoutPayload(objectMapper.valueToTree(defaults.layout()));
        preference.setCustomized(false);
        try {
            repository.saveAndFlush(preference);
        } catch (ObjectOptimisticLockingFailureException
                 | DataIntegrityViolationException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        if (compatibilityBridge != null) {
            compatibilityBridge.mirrorLegacyReset(
                    tenantId,
                    userId,
                    canonicalSurfaceKey,
                    objectMapper.valueToTree(defaults.layout()));
        }
        auditService.success(
                tenantId,
                userId,
                "home-preference.reset",
                "HOME_PREFERENCE",
                userId + ":" + canonicalSurfaceKey,
                correlationId,
                before,
                snapshot(null));
        return response(preference);
    }

    private HomePreference create(
            Long tenantId,
            Long userId,
            String surfaceKey,
            Long requestedVersion) {
        if (requestedVersion == null || requestedVersion != 0L) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        return HomePreference.builder()
                .tenantId(tenantId)
                .userId(userId)
                .surfaceKey(surfaceKey)
                .schemaVersion(HomePreferenceDtos.SCHEMA_VERSION)
                // Version zero is the create precondition. Persist new rows at one so a
                // second version-zero request serialized behind the scope lock is stale.
                .version(1L)
                .customized(true)
                .build();
    }

    public HomePreferenceDtos.HomeLayoutPayload normalizeForSurface(
            String surfaceKey,
            HomePreferenceDtos.HomeLayoutPayload layout) {
        return layoutPolicy.normalizeForSurface(surfaceKey, layout);
    }

    /** Reconciles registry-stale persisted layouts for read-cutover without mutating storage. */
    public HomePreferenceDtos.HomeLayoutPayload reconcileStoredForSurface(
            String surfaceKey,
            HomePreferenceDtos.HomeLayoutPayload layout) {
        return layoutPolicy.reconcileStoredForSurface(surfaceKey, layout);
    }

    public HomePreferenceDtos.HomeLayoutPayload defaultLayoutForSurface(String surfaceKey) {
        return layoutPolicy.defaultLayoutForSurface(surfaceKey);
    }

    private HomePreferenceDtos.HomePreferenceResponse response(HomePreference preference) {
        try {
            String surfaceKey = preference.getSurfaceKey() == null
                    ? WORKSPACE_HOME
                    : preference.getSurfaceKey();
            HomePreferenceDtos.HomeLayoutPayload stored = objectMapper.treeToValue(
                    preference.getLayoutPayload(),
                    HomePreferenceDtos.HomeLayoutPayload.class);
            HomePreferenceDtos.HomeLayoutPayload normalized =
                    layoutPolicy.reconcileStoredForSurface(surfaceKey, stored);
            boolean unchanged = preference.getSchemaVersion() != null
                    && preference.getSchemaVersion() == HomePreferenceDtos.SCHEMA_VERSION
                    && objectMapper.valueToTree(normalized).equals(preference.getLayoutPayload());
            return new HomePreferenceDtos.HomePreferenceResponse(
                    HomePreferenceDtos.SCHEMA_VERSION,
                    surfaceKey,
                    preference.isCustomized(),
                    unchanged
                            ? HomePreferenceDtos.HomePreferenceIntegrityStatus.VALID
                            : HomePreferenceDtos.HomePreferenceIntegrityStatus.RECONCILED,
                    normalized,
                    preference.getVersion() == null ? 0L : preference.getVersion(),
                    offset(preference.getUpdatedAt()),
                    unchanged ? List.of() : List.of("LAYOUT_RECONCILED"));
        } catch (Exception exception) {
            log.warn(
                    "Invalid stored home preference for tenant {}, user {}, surface {}; returning a recoverable default.",
                    preference.getTenantId(),
                    preference.getUserId(),
                    preference.getSurfaceKey(),
                    exception);
            return recoveryResponse(preference);
        }
    }

    private HomePreferenceDtos.HomePreferenceResponse defaultResponse(String surfaceKey) {
        return new HomePreferenceDtos.HomePreferenceResponse(
                HomePreferenceDtos.SCHEMA_VERSION,
                surfaceKey,
                false,
                HomePreferenceDtos.HomePreferenceIntegrityStatus.VALID,
                layoutPolicy.defaultLayoutForSurface(surfaceKey),
                0L,
                null,
                List.of());
    }

    private HomePreferenceDtos.HomePreferenceResponse recoveryResponse(HomePreference preference) {
        String surfaceKey = preference.getSurfaceKey() == null
                ? WORKSPACE_HOME
                : preference.getSurfaceKey();
        HomePreferenceDtos.HomePreferenceResponse defaults =
                defaultResponse(surfaceKey);
        return new HomePreferenceDtos.HomePreferenceResponse(
                HomePreferenceDtos.SCHEMA_VERSION,
                surfaceKey,
                preference.isCustomized(),
                HomePreferenceDtos.HomePreferenceIntegrityStatus.RECOVERED,
                defaults.layout(),
                preference.getVersion() == null ? 0L : preference.getVersion(),
                offset(preference.getUpdatedAt()),
                List.of("INVALID_STORED_LAYOUT"));
    }

    public HomePreferenceDtos.AppLayoutPayloadV1 emptyAppLayout() {
        return layoutPolicy.emptyAppLayout();
    }

    /**
     * Shares the same registry contract used by the base layout validator with
     * device-specific overlays. An overlay may change presentation only inside
     * the widget's declared responsive size set.
     */
    public boolean isWidgetSizeAllowed(String surfaceKey, String widgetKey, String size) {
        return layoutPolicy.isWidgetSizeAllowed(surfaceKey, widgetKey, size);
    }

    private java.time.OffsetDateTime offset(java.time.LocalDateTime value) {
        return value == null
                ? null
                : value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private void requireVersion(HomePreference preference, Long requestedVersion) {
        long current = preference.getVersion() == null ? 0L : preference.getVersion();
        if (requestedVersion == null || current != requestedVersion) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private Map<String, Object> snapshot(HomePreference preference) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (preference == null) {
            value.put("customized", false);
            return value;
        }
        value.put("customized", preference.isCustomized());
        value.put("surfaceKey", preference.getSurfaceKey());
        value.put("schemaVersion", preference.getSchemaVersion());
        value.put("layout", preference.getLayoutPayload());
        value.put("version", preference.getVersion() == null ? 0L : preference.getVersion());
        return value;
    }

    private void requirePersonalCustomization(Long tenantId, String surfaceKey) {
        if (WORKSPACE_HOME.equals(surfaceKey)
                && !compositionPolicyReader.personalCustomizationEnabled(tenantId)) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Personal workspace customization is disabled by the tenant policy.");
        }
    }

}
