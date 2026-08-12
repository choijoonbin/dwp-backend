package com.dwp.services.platform.branding;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.audit.PlatformAuditService;
import com.dwp.services.platform.experience.ExperienceRevisionStore;
import com.dwp.services.platform.media.TenantMediaStorage;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.dwp.services.platform.branding.TenantBrandingDtos.revisionSnapshot;
import static com.dwp.services.platform.branding.TenantBrandingDtos.snapshot;
import static com.dwp.services.platform.experience.ExperienceRevisionStore.BRANDING;

@Service
public class TenantBrandingService {

    private static final Logger log = LoggerFactory.getLogger(TenantBrandingService.class);
    private static final String LOGO_URL = "/api/platform/v1/tenant-branding/logo";

    private final TenantBrandingRepository repository;
    private final TenantMediaStorage mediaStorage;
    private final BrandLogoValidator logoValidator;
    private final PlatformAuditService auditService;
    private final ExperienceRevisionStore revisionStore;

    public TenantBrandingService(
            TenantBrandingRepository repository,
            TenantMediaStorage mediaStorage,
            BrandLogoValidator logoValidator,
            PlatformAuditService auditService,
            ExperienceRevisionStore revisionStore) {
        this.repository = repository;
        this.mediaStorage = mediaStorage;
        this.logoValidator = logoValidator;
        this.auditService = auditService;
        this.revisionStore = revisionStore;
    }

    @Transactional(readOnly = true)
    public TenantBrandingDtos.TenantBrandingResponse get(Long tenantId) {
        return repository.findById(tenantId).map(this::response).orElseGet(this::defaultResponse);
    }

    @Transactional(readOnly = true)
    public LogoContent getLogo(Long tenantId) {
        TenantBranding branding = repository.findById(tenantId)
                .filter(value -> value.getLogoAssetKey() != null)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        return new LogoContent(
                mediaStorage.load(tenantId, branding.getLogoAssetKey()),
                branding.getLogoContentType(),
                branding.getLogoSizeBytes(),
                branding.getLogoSha256());
    }

    @Transactional
    public TenantBrandingDtos.TenantBrandingResponse update(
            Long tenantId,
            Long actorId,
            String correlationId,
            TenantBrandingDtos.UpdateTenantBrandingRequest request) {
        TenantBranding branding = findOrCreate(tenantId, request.version());
        requireVersion(branding, request.version());
        Object before = snapshot(branding);
        ensureBaseline(tenantId, actorId, correlationId, branding);
        branding.setOrganizationName(trimToNull(request.organizationName()));
        if (request.accentColor() != null) {
            branding.setAccentColor(request.accentColor().toUpperCase());
        }
        TenantBranding saved = repository.saveAndFlush(branding);
        appendRevision(tenantId, actorId, correlationId, "SETTINGS_PUBLISHED", saved);
        auditService.success(
                tenantId,
                actorId,
                "tenant-branding.updated",
                "TENANT_BRANDING",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional
    public TenantBrandingDtos.TenantBrandingResponse uploadLogo(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long version,
            MultipartFile file) {
        TenantBranding branding = findOrCreate(tenantId, version);
        requireVersion(branding, version);
        BrandLogoValidator.ValidatedLogo logo = logoValidator.validate(file);
        Object before = snapshot(branding);
        ensureBaseline(tenantId, actorId, correlationId, branding);
        String storageKey = mediaStorage.store(
                tenantId, "branding/logos", logo.extension(), logo.content());
        boolean synchronizedCleanup = scheduleNewAssetRollbackCleanup(tenantId, storageKey);

        try {
            branding.setLogoAssetKey(storageKey);
            branding.setLogoOriginalName(logo.originalName());
            branding.setLogoContentType(logo.contentType());
            branding.setLogoSizeBytes(logo.sizeBytes());
            branding.setLogoSha256(logo.sha256());
            branding.setLogoWidth(logo.width());
            branding.setLogoHeight(logo.height());
            TenantBranding saved = repository.saveAndFlush(branding);
            appendRevision(tenantId, actorId, correlationId, "ASSET_PUBLISHED", saved);
            auditService.success(
                    tenantId,
                    actorId,
                    "tenant-branding.logo-uploaded",
                    "TENANT_BRANDING",
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
    public TenantBrandingDtos.TenantBrandingResponse resetLogo(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long version) {
        TenantBranding branding = repository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(branding, version);
        Object before = snapshot(branding);
        ensureBaseline(tenantId, actorId, correlationId, branding);

        clearLogo(branding);
        TenantBranding saved = repository.saveAndFlush(branding);
        appendRevision(tenantId, actorId, correlationId, "ASSET_RESET", saved);
        auditService.success(
                tenantId,
                actorId,
                "tenant-branding.logo-reset",
                "TENANT_BRANDING",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    @Transactional(readOnly = true)
    public List<TenantBrandingDtos.BrandingRevisionResponse> history(Long tenantId, int limit) {
        long currentVersion = repository.findById(tenantId)
                .map(value -> value.getVersion() == null ? 0L : value.getVersion())
                .orElse(0L);
        return revisionStore.list(tenantId, BRANDING, limit).stream()
                .map(revision -> revisionResponse(revision, currentVersion))
                .toList();
    }

    @Transactional
    public TenantBrandingDtos.TenantBrandingResponse rollback(
            Long tenantId,
            Long actorId,
            String correlationId,
            Long revisionId,
            Long version) {
        ExperienceRevisionStore.ExperienceRevision revision =
                revisionStore.require(tenantId, BRANDING, revisionId);
        TenantBranding branding = repository.findById(tenantId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        requireVersion(branding, version);
        Object before = snapshot(branding);
        ensureBaseline(tenantId, actorId, correlationId, branding);
        applyRevision(tenantId, branding, revision.snapshot());
        TenantBranding saved = repository.saveAndFlush(branding);
        appendRevision(tenantId, actorId, correlationId, "ROLLBACK", saved);
        auditService.success(
                tenantId,
                actorId,
                "tenant-branding.rolled-back",
                "TENANT_BRANDING",
                String.valueOf(tenantId),
                correlationId,
                before,
                snapshot(saved));
        return response(saved);
    }

    private TenantBranding findOrCreate(Long tenantId, Long requestedVersion) {
        return repository.findById(tenantId).orElseGet(() -> {
            if (requestedVersion == null || requestedVersion != 0L) {
                throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
            }
            return TenantBranding.builder().tenantId(tenantId).build();
        });
    }

    private void requireVersion(TenantBranding branding, Long requestedVersion) {
        long current = branding.getVersion() == null ? 0L : branding.getVersion();
        if (requestedVersion == null || current != requestedVersion) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    private TenantBrandingDtos.TenantBrandingResponse response(TenantBranding branding) {
        long version = branding.getVersion() == null ? 0L : branding.getVersion();
        String logoUrl = branding.getLogoAssetKey() == null ? null : LOGO_URL + "?v=" + version;
        return new TenantBrandingDtos.TenantBrandingResponse(
                branding.getOrganizationName(),
                branding.getAccentColor(),
                logoUrl,
                branding.getLogoOriginalName(),
                branding.getLogoContentType(),
                branding.getLogoSizeBytes(),
                branding.getLogoWidth(),
                branding.getLogoHeight(),
                version,
                branding.getUpdatedAt(),
                branding.getUpdatedBy());
    }

    private TenantBrandingDtos.TenantBrandingResponse defaultResponse() {
        return new TenantBrandingDtos.TenantBrandingResponse(
                null, "#2457D6", null, null, null, null, null, null, 0L, null, null);
    }

    private void clearLogo(TenantBranding branding) {
        branding.setLogoAssetKey(null);
        branding.setLogoOriginalName(null);
        branding.setLogoContentType(null);
        branding.setLogoSizeBytes(null);
        branding.setLogoSha256(null);
        branding.setLogoWidth(null);
        branding.setLogoHeight(null);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void deleteQuietly(Long tenantId, String storageKey) {
        if (storageKey == null) return;
        try {
            mediaStorage.delete(tenantId, storageKey);
        } catch (RuntimeException exception) {
            log.warn("Tenant logo cleanup failed for tenant {} and key {}", tenantId, storageKey, exception);
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
            TenantBranding branding) {
        revisionStore.ensureBaseline(
                tenantId,
                BRANDING,
                versionOf(branding),
                revisionSnapshot(branding),
                actorId,
                correlationId);
    }

    private void appendRevision(
            Long tenantId,
            Long actorId,
            String correlationId,
            String changeType,
            TenantBranding branding) {
        revisionStore.append(
                tenantId,
                BRANDING,
                versionOf(branding),
                changeType,
                revisionSnapshot(branding),
                actorId,
                correlationId);
    }

    private TenantBrandingDtos.BrandingRevisionResponse revisionResponse(
            ExperienceRevisionStore.ExperienceRevision revision,
            long currentVersion) {
        JsonNode value = revision.snapshot();
        return new TenantBrandingDtos.BrandingRevisionResponse(
                revision.revisionId(),
                revision.sourceVersion(),
                revision.changeType(),
                text(value, "organizationName"),
                text(value, "accentColor") == null ? "#2457D6" : text(value, "accentColor"),
                text(value, "logoOriginalName"),
                integer(value, "logoWidth"),
                integer(value, "logoHeight"),
                revision.sourceVersion() == currentVersion && !"BASELINE".equals(revision.changeType()),
                revision.createdAt(),
                revision.createdBy());
    }

    private void applyRevision(Long tenantId, TenantBranding branding, JsonNode value) {
        String assetKey = text(value, "logoAssetKey");
        if (assetKey != null) {
            mediaStorage.load(tenantId, assetKey);
        }
        branding.setOrganizationName(text(value, "organizationName"));
        branding.setAccentColor(text(value, "accentColor") == null ? "#2457D6" : text(value, "accentColor"));
        branding.setLogoAssetKey(assetKey);
        branding.setLogoOriginalName(text(value, "logoOriginalName"));
        branding.setLogoContentType(text(value, "logoContentType"));
        branding.setLogoSizeBytes(longValue(value, "logoSizeBytes"));
        branding.setLogoSha256(text(value, "logoSha256"));
        branding.setLogoWidth(integer(value, "logoWidth"));
        branding.setLogoHeight(integer(value, "logoHeight"));
    }

    private long versionOf(TenantBranding branding) {
        return branding.getVersion() == null ? 0L : branding.getVersion();
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private Integer integer(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node == null || !node.isNumber() ? null : node.intValue();
    }

    private Long longValue(JsonNode value, String field) {
        JsonNode node = value.get(field);
        return node == null || !node.isNumber() ? null : node.longValue();
    }

    public record LogoContent(Resource resource, String contentType, Long sizeBytes, String sha256) {
    }
}
