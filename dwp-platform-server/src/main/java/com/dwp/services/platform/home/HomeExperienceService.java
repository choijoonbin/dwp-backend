package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.experience.ExperienceRevisionStore;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.dwp.services.platform.experience.ExperienceRevisionStore.HOME;
import static com.dwp.services.platform.home.HomeExperienceDtos.revisionSnapshot;
import static com.dwp.services.platform.home.HomeExperienceDtos.snapshot;

@Service
public class HomeExperienceService {

    private static final Logger log = LoggerFactory.getLogger(HomeExperienceService.class);
    private static final String BACKGROUND_URL = "/api/platform/v1/home-experience/background";
    private static final Pattern LOCALE_PATTERN =
            Pattern.compile("^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$");

    private final HomeExperienceRepository repository;
    private final TenantMediaStorage assetStorage;
    private final HomeBackgroundValidator validator;
    private final PlatformAuditService auditService;
    private final ExperienceRevisionStore revisionStore;
    private final ObjectMapper objectMapper;
    private final HomeLaunchpadPolicy launchpadPolicy;

    public HomeExperienceService(
            HomeExperienceRepository repository,
            TenantMediaStorage assetStorage,
            HomeBackgroundValidator validator,
            PlatformAuditService auditService,
            ExperienceRevisionStore revisionStore,
            ObjectMapper objectMapper,
            HomeLaunchpadPolicy launchpadPolicy) {
        this.repository = repository;
        this.assetStorage = assetStorage;
        this.validator = validator;
        this.auditService = auditService;
        this.revisionStore = revisionStore;
        this.objectMapper = objectMapper;
        this.launchpadPolicy = launchpadPolicy;
    }

    @Transactional(readOnly = true)
    public HomeExperienceDtos.HomeExperienceResponse get(Long tenantId) {
        return repository.findById(tenantId).map(this::response).orElseGet(this::defaultResponse);
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

        experience.setHeadline(trimToNull(request.headline()));
        experience.setSubheadline(trimToNull(request.subheadline()));
        if (request.localizedContent() != null) {
            experience.setLocalizedContent(normalizeLocalizedContent(request.localizedContent()));
        }
        if (request.defaultLocale() != null) {
            String defaultLocale = request.defaultLocale().toLowerCase(Locale.ROOT);
            if (!LOCALE_PATTERN.matcher(defaultLocale).matches()) {
                throw invalid("Home experience default locale is invalid.");
            }
            experience.setDefaultLocale(defaultLocale);
        }
        validateDefaultLocalizedCopy(experience);
        experience.setBackgroundPosition(request.backgroundPosition().toUpperCase(Locale.ROOT));
        experience.setOverlayOpacity(request.overlayOpacity());
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
            experience.setBackgroundAssetKey(storageKey);
            experience.setBackgroundOriginalName(background.originalName());
            experience.setBackgroundContentType(background.contentType());
            experience.setBackgroundSizeBytes(background.sizeBytes());
            experience.setBackgroundSha256(background.sha256());
            experience.setBackgroundWidth(background.width());
            experience.setBackgroundHeight(background.height());
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
                    .overlayOpacity(18)
                    .launchpadConfiguration(
                            objectMapper.valueToTree(launchpadPolicy.defaultConfiguration()))
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
        return new HomeExperienceDtos.HomeExperienceResponse(
                experience.getHeadline(),
                experience.getSubheadline(),
                localizedContent(experience.getLocalizedContent()),
                experience.getDefaultLocale(),
                experience.getBackgroundPosition(),
                experience.getOverlayOpacity(),
                url,
                experience.getBackgroundOriginalName(),
                experience.getBackgroundContentType(),
                experience.getBackgroundSizeBytes(),
                experience.getBackgroundWidth(),
                experience.getBackgroundHeight(),
                launchpadConfiguration(experience.getLaunchpadConfiguration()),
                version,
                experience.getUpdatedAt(),
                experience.getUpdatedBy());
    }

    private HomeExperienceDtos.HomeExperienceResponse defaultResponse() {
        return new HomeExperienceDtos.HomeExperienceResponse(
                null,
                null,
                Map.of(),
                "ko",
                "RIGHT",
                18,
                null,
                null,
                null,
                null,
                null,
                null,
                launchpadPolicy.defaultConfiguration(),
                0L,
                null,
                null);
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

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
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

    private ObjectNode normalizeLocalizedContent(
            Map<String, HomeExperienceDtos.LocalizedCopy> requested) {
        if (requested.size() > 20) {
            throw invalid("Home experience supports up to 20 locales.");
        }
        ObjectNode result = objectMapper.createObjectNode();
        requested.forEach((rawLocale, copy) -> {
            if (rawLocale == null || !LOCALE_PATTERN.matcher(rawLocale).matches()) {
                throw invalid("Home experience locale is invalid.");
            }
            if (copy == null) {
                throw invalid("Localized home copy is required.");
            }
            String headline = trimToNull(copy.headline());
            String subheadline = trimToNull(copy.subheadline());
            if (headline != null && headline.length() > 160) {
                throw invalid("Localized home headline is too long.");
            }
            if (subheadline != null && subheadline.length() > 500) {
                throw invalid("Localized home supporting message is too long.");
            }
            ObjectNode localeValue = result.putObject(rawLocale.toLowerCase(Locale.ROOT));
            if (headline != null) localeValue.put("headline", headline);
            if (subheadline != null) localeValue.put("subheadline", subheadline);
        });
        return result;
    }

    private Map<String, HomeExperienceDtos.LocalizedCopy> localizedContent(JsonNode value) {
        if (value == null || !value.isObject()) return Map.of();
        Map<String, HomeExperienceDtos.LocalizedCopy> result = new LinkedHashMap<>();
        value.properties().forEach(entry -> result.put(
                entry.getKey(),
                new HomeExperienceDtos.LocalizedCopy(
                        text(entry.getValue(), "headline"),
                        text(entry.getValue(), "subheadline"))));
        return result;
    }

    private void validateDefaultLocalizedCopy(HomeExperience experience) {
        JsonNode localized = experience.getLocalizedContent();
        if (localized == null || !localized.isObject() || localized.isEmpty()) return;
        String defaultLocale = experience.getDefaultLocale();
        JsonNode defaultCopy = defaultLocale == null ? null : localized.get(defaultLocale);
        if (defaultCopy == null || !defaultCopy.isObject()) {
            throw invalid("Home experience default locale must have localized content.");
        }
        if (trimToNull(text(defaultCopy, "headline")) == null
                || trimToNull(text(defaultCopy, "subheadline")) == null) {
            throw invalid("Home experience default locale copy must be complete.");
        }
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
                revision.sourceVersion() == currentVersion && !"BASELINE".equals(revision.changeType()),
                revision.createdAt(),
                revision.createdBy());
    }

    private void applyRevision(Long tenantId, HomeExperience experience, JsonNode value) {
        String assetKey = text(value, "backgroundAssetKey");
        if (assetKey != null) {
            assetStorage.load(tenantId, assetKey);
        }
        experience.setHeadline(text(value, "headline"));
        experience.setSubheadline(text(value, "subheadline"));
        JsonNode localized = value.get("localizedContent");
        experience.setLocalizedContent(
                localized != null && localized.isObject()
                        ? localized.deepCopy()
                        : objectMapper.createObjectNode());
        experience.setDefaultLocale(
                text(value, "defaultLocale") == null ? "ko" : text(value, "defaultLocale"));
        experience.setBackgroundPosition(
                text(value, "backgroundPosition") == null
                        ? "CENTER"
                        : text(value, "backgroundPosition"));
        Integer overlay = integer(value, "overlayOpacity");
        experience.setOverlayOpacity(overlay == null ? 18 : overlay);
        experience.setLaunchpadConfiguration(
                objectMapper.valueToTree(
                        launchpadConfiguration(value.get("launchpadConfiguration"))));
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

    private BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    public record BackgroundContent(
            Resource resource,
            String contentType,
            Long sizeBytes,
            String sha256) {
    }
}
