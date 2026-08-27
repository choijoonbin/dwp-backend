package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.experience.ExperienceRevisionStore;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.dwp.services.platform.home.personalization.HomeViewCompatibilityBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.time.ZoneId;

import static com.dwp.services.platform.experience.ExperienceRevisionStore.HOME;
import static com.dwp.services.platform.home.HomeExperienceDtos.revisionSnapshot;
import static com.dwp.services.platform.home.HomeExperienceDtos.snapshot;

@Service
public class HomeExperienceService implements HomeCompositionPolicyReader {

    private static final Logger log = LoggerFactory.getLogger(HomeExperienceService.class);
    private static final String BACKGROUND_URL = "/api/platform/v1/home-experience/background";
    private static final List<String> ROLLBACK_AFFECTED_SCOPES = List.of(
            "PRESENTATION",
            "BACKGROUND_ASSET",
            "LAUNCHPAD",
            "COMPOSITION");
    private final HomeExperienceRepository repository;
    private final TenantMediaStorage assetStorage;
    private final HomeBackgroundValidator validator;
    private final PlatformAuditService auditService;
    private final ExperienceRevisionStore revisionStore;
    private final ObjectMapper objectMapper;
    private final HomeLaunchpadPolicy launchpadPolicy;
    private final HomeCompositionPolicyRegistry compositionPolicyRegistry;
    private final HomeViewCompatibilityBridge compatibilityBridge;
    private final HomeExperiencePresentationPolicy presentationPolicy;

    @Value("${dwp.platform.home.flow-enabled:false}")
    private boolean homeFlowEnabled;

    @Value("${dwp.platform.home.personalization-v2-enabled:false}")
    private boolean advancedPersonalizationEnabled;

    @Value("${dwp.platform.home.composer-enabled:false}")
    private boolean composerEnabled;

    @Value("${dwp.platform.home.views-read-enabled:false}")
    private boolean viewsReadEnabled;

    @Value("${dwp.platform.home.views-dual-write-enabled:false}")
    private boolean viewsDualWriteEnabled;

    @Value("${dwp.platform.home.views-shadow-compare-enabled:false}")
    private boolean viewsShadowCompareEnabled;

    public HomeExperienceService(
            HomeExperienceRepository repository,
            TenantMediaStorage assetStorage,
            HomeBackgroundValidator validator,
            PlatformAuditService auditService,
            ExperienceRevisionStore revisionStore,
            ObjectMapper objectMapper,
            HomeLaunchpadPolicy launchpadPolicy,
            HomeCompositionPolicyRegistry compositionPolicyRegistry,
            HomeViewCompatibilityBridge compatibilityBridge,
            HomeExperiencePresentationPolicy presentationPolicy) {
        this.repository = repository;
        this.assetStorage = assetStorage;
        this.validator = validator;
        this.auditService = auditService;
        this.revisionStore = revisionStore;
        this.objectMapper = objectMapper;
        this.launchpadPolicy = launchpadPolicy;
        this.compositionPolicyRegistry = compositionPolicyRegistry;
        this.compatibilityBridge = compatibilityBridge;
        this.presentationPolicy = presentationPolicy;
    }

    @Transactional(readOnly = true)
    public HomeExperienceDtos.HomeExperienceResponse get(Long tenantId) {
        return repository.findById(tenantId).map(this::response)
                .orElseGet(() -> defaultResponse(tenantId));
    }

    @Transactional(readOnly = true)
    public BackgroundContent getBackground(Long tenantId) {
        HomeExperience experience = repository.findById(tenantId)
                .filter(value -> value.getBackgroundAssetKey() != null)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        Resource resource = assetStorage.load(tenantId, experience.getBackgroundAssetKey());
        return new BackgroundContent(
                resource,
                experience.getBackgroundContentType(),
                experience.getBackgroundSizeBytes(),
                experience.getBackgroundSha256());
    }

    @Transactional
    public HomeExperienceDtos.HomeExperienceResponse update(
            Long tenantId,
            Long actorId,
            String correlationId,
            HomeExperienceDtos.UpdateHomeExperienceRequest request) {
        HomeExperience experience = findOrCreate(tenantId, request.version());
        requireVersion(experience, request.version());
        Object before = snapshot(experience);
        ensureBaseline(tenantId, actorId, correlationId, experience);

        presentationPolicy.applySettings(experience, request);
        HomeExperience saved = repository.saveAndFlush(experience);
        appendRevision(tenantId, actorId, correlationId, "SETTINGS_PUBLISHED", saved);
        auditService.success(
                tenantId,
                actorId,
                "home-experience.updated",
                "HOME_EXPERIENCE",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    /**
     * Publishes presentation settings and an optional replacement background as one aggregate
     * revision. The stored object is deleted if the surrounding database transaction does not
     * commit; prior objects remain available because published revisions may reference them.
     */
    @Transactional
    public HomeExperienceDtos.HomeExperienceResponse publish(
            Long tenantId,
            Long actorId,
            String correlationId,
            HomeExperienceDtos.UpdateHomeExperienceRequest request,
            MultipartFile file,
            boolean resetBackground) {
        HomeExperience experience = findOrCreate(tenantId, request.version());
        requireVersion(experience, request.version());
        if (file != null && resetBackground) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "A home background cannot be replaced and reset in the same publication.");
        }
        HomeBackgroundValidator.ValidatedBackground background =
                file == null ? null : validator.validate(file);
        Object before = snapshot(experience);
        ensureBaseline(tenantId, actorId, correlationId, experience);

        String replacementKey = null;
        boolean synchronizedCleanup = false;
        try {
            presentationPolicy.applySettings(experience, request);
            if (background != null) {
                replacementKey = assetStorage.store(
                        tenantId,
                        "home/backgrounds",
                        background.extension(),
                        background.content());
                synchronizedCleanup = scheduleNewAssetRollbackCleanup(tenantId, replacementKey);
                presentationPolicy.applyBackground(experience, replacementKey, background);
            } else if (resetBackground) {
                clearBackground(experience);
            }
            HomeExperience saved = repository.saveAndFlush(experience);
            appendRevision(tenantId, actorId, correlationId, "EXPERIENCE_PUBLISHED", saved);
            auditService.success(
                    tenantId,
                    actorId,
                    "home-experience.published",
                    "HOME_EXPERIENCE",
                    String.valueOf(tenantId),
                    correlationId,
                    before,
                    snapshot(saved));
            return response(saved);
        } catch (RuntimeException exception) {
            if (replacementKey != null && !synchronizedCleanup) {
                deleteQuietly(tenantId, replacementKey);
            }
            throw exception;
        }
    }

    @Transactional
    public HomeExperienceDtos.HomeExperienceResponse updateLaunchpad(
            Long tenantId,
            Long actorId,
            String correlationId,
            HomeExperienceDtos.UpdateLaunchpadConfigurationRequest request) {
        HomeExperience experience = findOrCreate(tenantId, request.version());
        requireVersion(experience, request.version());
        Object before = snapshot(experience);
        ensureBaseline(tenantId, actorId, correlationId, experience);

        HomeExperienceDtos.HomeLaunchpadConfiguration normalized =
                launchpadPolicy.normalize(request.configuration());
        experience.setLaunchpadConfiguration(objectMapper.valueToTree(normalized));
        HomeExperience saved = repository.saveAndFlush(experience);
        appendRevision(tenantId, actorId, correlationId, "SETTINGS_PUBLISHED", saved);
        auditService.success(
                tenantId,
                actorId,
                "home-experience.launchpad-updated",
                "HOME_LAUNCHPAD_CONFIGURATION",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public HomeExperienceDtos.HomeExperienceResponse updateComposition(
            Long tenantId,
            Long actorId,
            String correlationId,
            HomeExperienceDtos.UpdateHomeCompositionPolicyRequest request) {
        HomeExperience experience = findOrCreate(tenantId, request.version());
        requireVersion(experience, request.version());
        Object before = snapshot(experience);
        ensureBaseline(tenantId, actorId, correlationId, experience);

        HomeExperienceDtos.HomeCompositionPolicy normalized =
                compositionPolicyRegistry.normalize(request.policy());
        experience.setCompositionPolicy(objectMapper.valueToTree(normalized));
        HomeExperience saved = repository.saveAndFlush(experience);
        appendRevision(tenantId, actorId, correlationId, "SETTINGS_PUBLISHED", saved);
        auditService.success(
                tenantId,
                actorId,
                "home-experience.composition-updated",
                "HOME_COMPOSITION_POLICY",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean personalCustomizationEnabled(Long tenantId) {
        return repository.findById(tenantId)
                .map(HomeExperience::getCompositionPolicy)
                .map(this::compositionPolicy)
                .map(HomeExperienceDtos.HomeCompositionPolicy::personalCustomizationEnabled)
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean flowPersonalizationEnabled(Long tenantId) {
        // A Flow mutation must always be mirrored to the Classic preference so
        // the global kill switch remains a lossless rollback mechanism.
        if (!homeFlowEnabled || !advancedPersonalizationEnabled || !viewsDualWriteEnabled) {
            return false;
        }
        HomeExperienceDtos.HomeCompositionPolicy policy = repository.findById(tenantId)
                .map(HomeExperience::getCompositionPolicy)
                .map(this::compositionPolicy)
                .orElseGet(compositionPolicyRegistry::defaultPolicy);
        return Boolean.TRUE.equals(policy.personalCustomizationEnabled())
                && HomeCompositionPolicyRegistry.FLOW_V1.equals(
                        compositionPolicyRegistry.effectiveVariant(policy, homeFlowEnabled));
    }

    @Transactional
    public HomeExperienceDtos.HomeExperienceResponse uploadBackground(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long version,
            MultipartFile file) {
        HomeExperience experience = findOrCreate(tenantId, version);
        requireVersion(experience, version);
        HomeBackgroundValidator.ValidatedBackground background = validator.validate(file);
        Object before = snapshot(experience);
        ensureBaseline(tenantId, actorId, correlationId, experience);
        String storageKey = assetStorage.store(
                tenantId, "home/backgrounds", background.extension(), background.content());
        boolean synchronizedCleanup = scheduleNewAssetRollbackCleanup(tenantId, storageKey);

        try {
            presentationPolicy.applyBackground(experience, storageKey, background);
            HomeExperience saved = repository.saveAndFlush(experience);
            appendRevision(tenantId, actorId, correlationId, "ASSET_PUBLISHED", saved);
            auditService.success(
                    tenantId,
                    actorId,
                    "home-experience.background-uploaded",
                    "HOME_EXPERIENCE",
                    String.valueOf(tenantId),
                    correlationId,
                    before,
                    snapshot(saved));
            return response(saved);
        } catch (RuntimeException exception) {
            if (!synchronizedCleanup) deleteQuietly(tenantId, storageKey);
            throw exception;
        }
    }

    @Transactional
    public HomeExperienceDtos.HomeExperienceResponse resetBackground(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long version) {
        HomeExperience experience = repository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(experience, version);
        Object before = snapshot(experience);
        ensureBaseline(tenantId, actorId, correlationId, experience);

        clearBackground(experience);
        HomeExperience saved = repository.saveAndFlush(experience);
        appendRevision(tenantId, actorId, correlationId, "ASSET_RESET", saved);
        auditService.success(
                tenantId,
                actorId,
                "home-experience.background-reset",
                "HOME_EXPERIENCE",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional(readOnly = true)
    public List<HomeExperienceDtos.HomeExperienceRevisionResponse> history(Long tenantId, int limit) {
        long currentVersion = repository.findById(tenantId)
                .map(this::versionOf)
                .orElse(0L);
        return revisionStore.list(tenantId, HOME, limit).stream()
                .map(revision -> revisionResponse(revision, currentVersion))
                .toList();
    }

    @Transactional
    public HomeExperienceDtos.HomeExperienceResponse rollback(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long revisionId,
            Long version) {
        ExperienceRevisionStore.ExperienceRevision revision =
                revisionStore.require(tenantId, HOME, revisionId);
        HomeExperience experience = repository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(experience, version);
        Object before = snapshot(experience);
        ensureBaseline(tenantId, actorId, correlationId, experience);
        applyRevision(tenantId, experience, revision.snapshot());
        HomeExperience saved = repository.saveAndFlush(experience);
        appendRevision(tenantId, actorId, correlationId, "ROLLBACK", saved);
        auditService.success(
                tenantId,
                actorId,
                "home-experience.rolled-back",
                "HOME_EXPERIENCE",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    private HomeExperience findOrCreate(Long tenantId, Long requestedVersion) {
        return repository.findById(tenantId).orElseGet(() -> {
            if (requestedVersion == null || requestedVersion != 0L) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
            return HomeExperience.builder()
                    .tenantId(tenantId)
                    .backgroundPosition("RIGHT")
                    .backgroundFocalX(50)
                    .backgroundFocalY(50)
                    .mobileBackgroundFocalX(50)
                    .mobileBackgroundFocalY(50)
                    .contentAlignment("LEFT")
                    .overlayOpacity(18)
                    .launchpadConfiguration(
                            objectMapper.valueToTree(launchpadPolicy.defaultConfiguration()))
                    .compositionPolicy(
                            objectMapper.valueToTree(compositionPolicyRegistry.defaultPolicy()))
                    .build();
        });
    }

    private void requireVersion(HomeExperience experience, Long requestedVersion) {
        long current = experience.getVersion() == null ? 0L : experience.getVersion();
        if (requestedVersion == null || current != requestedVersion) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private HomeExperienceDtos.HomeExperienceResponse response(HomeExperience experience) {
        Long version = experience.getVersion() == null ? 0L : experience.getVersion();
        String url = experience.getBackgroundAssetKey() == null
                ? null
                : BACKGROUND_URL + "?v=" + version;
        HomeExperienceDtos.HomeCompositionPolicy compositionPolicy =
                compositionPolicy(experience.getCompositionPolicy());
        return new HomeExperienceDtos.HomeExperienceResponse(
                experience.getHeadline(),
                experience.getSubheadline(),
                presentationPolicy.localizedContent(experience.getLocalizedContent()),
                experience.getDefaultLocale(),
                experience.getBackgroundPosition(),
                presentationPolicy.percentOrDefault(experience.getBackgroundFocalX()),
                presentationPolicy.percentOrDefault(experience.getBackgroundFocalY()),
                presentationPolicy.percentOrDefault(experience.getMobileBackgroundFocalX()),
                presentationPolicy.percentOrDefault(experience.getMobileBackgroundFocalY()),
                presentationPolicy.alignmentOrDefault(experience.getContentAlignment()),
                experience.getOverlayOpacity(),
                url,
                experience.getBackgroundOriginalName(),
                experience.getBackgroundContentType(),
                experience.getBackgroundSizeBytes(),
                experience.getBackgroundWidth(),
                experience.getBackgroundHeight(),
                launchpadConfiguration(experience.getLaunchpadConfiguration()),
                compositionPolicy,
                compositionPolicyRegistry.effectiveVariant(compositionPolicy, homeFlowEnabled),
                personalizationAvailable(compositionPolicy),
                composerAvailable(compositionPolicy),
                homePreferenceStore(experience.getTenantId(), compositionPolicy),
                version,
                experience.getUpdatedAt() == null
                        ? null
                        : experience.getUpdatedAt().atZone(
                                ZoneId.systemDefault()).toOffsetDateTime(),
                experience.getUpdatedBy());
    }

    private HomeExperienceDtos.HomeExperienceResponse defaultResponse(Long tenantId) {
        HomeExperienceDtos.HomeCompositionPolicy compositionPolicy =
                compositionPolicyRegistry.defaultPolicy();
        return new HomeExperienceDtos.HomeExperienceResponse(
                null,
                null,
                Map.of(),
                "ko",
                "RIGHT",
                50,
                50,
                50,
                50,
                "LEFT",
                18,
                null,
                null,
                null,
                null,
                null,
                null,
                launchpadPolicy.defaultConfiguration(),
                compositionPolicy,
                compositionPolicyRegistry.effectiveVariant(compositionPolicy, homeFlowEnabled),
                personalizationAvailable(compositionPolicy),
                composerAvailable(compositionPolicy),
                homePreferenceStore(tenantId, compositionPolicy),
                0L,
                null,
                null);
    }

    private String homePreferenceStore(
            Long tenantId,
            HomeExperienceDtos.HomeCompositionPolicy compositionPolicy) {
        boolean flowEffective = HomeCompositionPolicyRegistry.FLOW_V1.equals(
                compositionPolicyRegistry.effectiveVariant(compositionPolicy, homeFlowEnabled));
        return flowEffective
                && advancedPersonalizationEnabled
                && viewsReadEnabled
                && viewsDualWriteEnabled
                && viewsShadowCompareEnabled
                && compatibilityBridge.readCutoverReady(tenantId)
                && Boolean.TRUE.equals(compositionPolicy.personalCustomizationEnabled())
                ? "VIEWS"
                : "LEGACY";
    }

    private boolean composerAvailable(
            HomeExperienceDtos.HomeCompositionPolicy compositionPolicy) {
        return personalizationAvailable(compositionPolicy)
                && composerEnabled;
    }

    private boolean personalizationAvailable(
            HomeExperienceDtos.HomeCompositionPolicy compositionPolicy) {
        return advancedPersonalizationEnabled
                && Boolean.TRUE.equals(compositionPolicy.personalCustomizationEnabled());
    }

    private void clearBackground(HomeExperience experience) {
        experience.setBackgroundAssetKey(null);
        experience.setBackgroundOriginalName(null);
        experience.setBackgroundContentType(null);
        experience.setBackgroundSizeBytes(null);
        experience.setBackgroundSha256(null);
        experience.setBackgroundWidth(null);
        experience.setBackgroundHeight(null);
    }

    private void deleteQuietly(Long tenantId, String storageKey) {
        if (storageKey == null) return;
        try {
            assetStorage.delete(tenantId, storageKey);
        } catch (RuntimeException exception) {
            log.warn(
                    "Home asset cleanup failed for tenant {} and key {}",
                    tenantId,
                    storageKey,
                    exception);
        }
    }

    private boolean scheduleNewAssetRollbackCleanup(Long tenantId, String replacementKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    deleteQuietly(tenantId, replacementKey);
                }
            }
        });
        return true;
    }

    private void ensureBaseline(
            Long tenantId,
            Long actorId,
            String correlationId,
            HomeExperience experience) {
        revisionStore.ensureBaseline(
                tenantId,
                HOME,
                versionOf(experience),
                revisionSnapshot(experience),
                actorId,
                correlationId);
    }

    private void appendRevision(
            Long tenantId,
            Long actorId,
            String correlationId,
            String changeType,
            HomeExperience experience) {
        revisionStore.append(
                tenantId,
                HOME,
                versionOf(experience),
                changeType,
                revisionSnapshot(experience),
                actorId,
                correlationId);
    }

    private HomeExperienceDtos.HomeExperienceRevisionResponse revisionResponse(
            ExperienceRevisionStore.ExperienceRevision revision,
            long currentVersion) {
        JsonNode value = revision.snapshot();
        JsonNode localized = value.get("localizedContent");
        return new HomeExperienceDtos.HomeExperienceRevisionResponse(
                revision.revisionId(),
                revision.sourceVersion(),
                revision.changeType(),
                text(value, "headline"),
                text(value, "backgroundOriginalName"),
                integer(value, "backgroundWidth"),
                integer(value, "backgroundHeight"),
                localized != null && localized.isObject() ? localized.size() : 0,
                ROLLBACK_AFFECTED_SCOPES,
                revision.sourceVersion() == currentVersion && !"BASELINE".equals(revision.changeType()),
                revision.createdAt(),
                revision.createdBy());
    }

    private void applyRevision(Long tenantId, HomeExperience experience, JsonNode value) {
        String assetKey = text(value, "backgroundAssetKey");
        if (assetKey != null) {
            assetStorage.load(tenantId, assetKey);
        }
        presentationPolicy.restorePresentation(experience, value);
        experience.setLaunchpadConfiguration(
                objectMapper.valueToTree(
                        launchpadConfiguration(value.get("launchpadConfiguration"))));
        experience.setCompositionPolicy(
                objectMapper.valueToTree(
                        compositionPolicy(value.get("compositionPolicy"))));
        experience.setBackgroundAssetKey(assetKey);
        experience.setBackgroundOriginalName(text(value, "backgroundOriginalName"));
        experience.setBackgroundContentType(text(value, "backgroundContentType"));
        experience.setBackgroundSizeBytes(longValue(value, "backgroundSizeBytes"));
        experience.setBackgroundSha256(text(value, "backgroundSha256"));
        experience.setBackgroundWidth(integer(value, "backgroundWidth"));
        experience.setBackgroundHeight(integer(value, "backgroundHeight"));
    }

    private long versionOf(HomeExperience experience) {
        return experience.getVersion() == null ? 0L : experience.getVersion();
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private Integer integer(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || !node.isNumber() ? null : node.intValue();
    }

    private Long longValue(JsonNode value, String field) {
        JsonNode node = value == null ? null : value.get(field);
        return node == null || !node.isNumber() ? null : node.longValue();
    }

    private HomeExperienceDtos.HomeLaunchpadConfiguration launchpadConfiguration(JsonNode value) {
        if (value == null || !value.isObject() || value.isEmpty()) {
            return launchpadPolicy.defaultConfiguration();
        }
        try {
            HomeExperienceDtos.HomeLaunchpadConfiguration configuration =
                    objectMapper.treeToValue(
                            value,
                            HomeExperienceDtos.HomeLaunchpadConfiguration.class);
            return launchpadPolicy.normalize(configuration);
        } catch (Exception exception) {
            log.warn(
                    "Invalid persisted home launchpad configuration; using the governed default.",
                    exception);
            return launchpadPolicy.defaultConfiguration();
        }
    }

    private HomeExperienceDtos.HomeCompositionPolicy compositionPolicy(JsonNode value) {
        if (value == null || !value.isObject() || value.isEmpty()) {
            return compositionPolicyRegistry.failClosedPolicy();
        }
        try {
            HomeExperienceDtos.HomeCompositionPolicy policy = objectMapper.treeToValue(
                    value,
                    HomeExperienceDtos.HomeCompositionPolicy.class);
            return compositionPolicyRegistry.normalize(policy);
        } catch (Exception exception) {
            log.warn(
                    "Invalid persisted home composition policy; disabling personal customization.",
                    exception);
            return compositionPolicyRegistry.failClosedPolicy();
        }
    }

    public record BackgroundContent(
            Resource resource,
            String contentType,
            Long sizeBytes,
            String sha256) {
    }
}
