package com.dwp.services.platform.home;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;

import static com.dwp.services.platform.home.HomeExperienceDtos.snapshot;

@Service
public class HomeExperienceService {

    private static final Logger log = LoggerFactory.getLogger(HomeExperienceService.class);
    private static final String BACKGROUND_URL = "/api/platform/v1/home-experience/background";

    private final HomeExperienceRepository repository;
    private final TenantMediaStorage assetStorage;
    private final HomeBackgroundValidator validator;
    private final PlatformAuditService auditService;

    public HomeExperienceService(
            HomeExperienceRepository repository,
            TenantMediaStorage assetStorage,
            HomeBackgroundValidator validator,
            PlatformAuditService auditService) {
        this.repository = repository;
        this.assetStorage = assetStorage;
        this.validator = validator;
        this.auditService = auditService;
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

        experience.setHeadline(trimToNull(request.headline()));
        experience.setSubheadline(trimToNull(request.subheadline()));
        experience.setBackgroundPosition(request.backgroundPosition().toUpperCase(Locale.ROOT));
        experience.setOverlayOpacity(request.overlayOpacity());
        HomeExperience saved = repository.saveAndFlush(experience);
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
        String previousKey = experience.getBackgroundAssetKey();
        String storageKey = assetStorage.store(
                tenantId, "home/backgrounds", background.extension(), background.content());
        boolean synchronizedCleanup = scheduleReplacementCleanup(tenantId, previousKey, storageKey);

        try {
            experience.setBackgroundAssetKey(storageKey);
            experience.setBackgroundOriginalName(background.originalName());
            experience.setBackgroundContentType(background.contentType());
            experience.setBackgroundSizeBytes(background.sizeBytes());
            experience.setBackgroundSha256(background.sha256());
            experience.setBackgroundWidth(background.width());
            experience.setBackgroundHeight(background.height());
            HomeExperience saved = repository.saveAndFlush(experience);
            auditService.success(
                    tenantId,
                    actorId,
                    "home-experience.background-uploaded",
                    "HOME_EXPERIENCE",
                    String.valueOf(tenantId),
                    correlationId,
                    before,
                    snapshot(saved));
            if (!synchronizedCleanup) deleteQuietly(tenantId, previousKey);
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
        String previousKey = experience.getBackgroundAssetKey();
        boolean synchronizedCleanup = scheduleCommittedCleanup(tenantId, previousKey);

        clearBackground(experience);
        HomeExperience saved = repository.saveAndFlush(experience);
        auditService.success(
                tenantId,
                actorId,
                "home-experience.background-reset",
                "HOME_EXPERIENCE",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        if (!synchronizedCleanup) deleteQuietly(tenantId, previousKey);
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
                experience.getBackgroundPosition(),
                experience.getOverlayOpacity(),
                url,
                experience.getBackgroundOriginalName(),
                experience.getBackgroundContentType(),
                experience.getBackgroundSizeBytes(),
                experience.getBackgroundWidth(),
                experience.getBackgroundHeight(),
                version,
                experience.getUpdatedAt(),
                experience.getUpdatedBy());
    }

    private HomeExperienceDtos.HomeExperienceResponse defaultResponse() {
        return new HomeExperienceDtos.HomeExperienceResponse(
                null, null, "RIGHT", 18, null, null, null, null, null, null, 0L, null, null);
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

    private boolean scheduleReplacementCleanup(
            Long tenantId,
            String previousKey,
            String replacementKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    deleteQuietly(tenantId, previousKey);
                } else {
                    deleteQuietly(tenantId, replacementKey);
                }
            }
        });
        return true;
    }

    private boolean scheduleCommittedCleanup(Long tenantId, String storageKey) {
        if (storageKey == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteQuietly(tenantId, storageKey);
            }
        });
        return true;
    }

    public record BackgroundContent(
            Resource resource,
            String contentType,
            Long sizeBytes,
            String sha256) {
    }
}
