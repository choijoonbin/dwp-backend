package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.media.TenantMediaStorage;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;

@Service
public class WorkplaceExperienceCollaborationMediaService {
    private final WorkplaceExperienceCollaborationRepository repository;
    private final WorkplaceRuntimeGovernance runtime;
    private final WorkplaceExperienceCollaborationService collaboration;
    private final TenantMediaStorage storage;
    private final WorkplaceMediaCleanupRepository cleanup;
    private final WorkplaceFloorPlanValidator validator;

    public WorkplaceExperienceCollaborationMediaService(WorkplaceExperienceCollaborationRepository repository,
            WorkplaceRuntimeGovernance runtime, WorkplaceExperienceCollaborationService collaboration,
            TenantMediaStorage storage, WorkplaceMediaCleanupRepository cleanup, WorkplaceFloorPlanValidator validator) {
        this.repository = repository;
        this.runtime = runtime;
        this.collaboration = collaboration;
        this.storage = storage;
        this.cleanup = cleanup;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public ResourcePhoto metadata(long tenantId, long userId, String groups, UUID resourceId) {
        requireMemberScope(tenantId, userId, groups, resourceId);
        return metadata(requirePhoto(tenantId, resourceId));
    }

    @Transactional(readOnly = true)
    public ResourcePhoto adminMetadata(long tenantId, UUID resourceId) {
        requireResource(tenantId, resourceId);
        return metadata(requirePhoto(tenantId, resourceId), true);
    }

    @Transactional(readOnly = true)
    public PhotoContent content(long tenantId, long userId, String groups, UUID resourceId) {
        requireMemberScope(tenantId, userId, groups, resourceId);
        var photo = requirePhoto(tenantId, resourceId);
        return new PhotoContent(storage.load(tenantId, photo.storageKey()), metadata(photo));
    }

    @Transactional(readOnly = true)
    public PhotoContent adminContent(long tenantId, UUID resourceId) {
        requireResource(tenantId, resourceId);
        var photo = requirePhoto(tenantId, resourceId);
        return new PhotoContent(storage.load(tenantId, photo.storageKey()), metadata(photo, true));
    }

    @Transactional
    public ResourcePhoto upload(long tenantId, long actorId, UUID resourceId, long version,
                                String reason, String altText, MultipartFile file, String correlationId) {
        requireResource(tenantId, resourceId);
        WorkplaceExperienceCollaborationService.requireConfirmation(reason, true);
        if (altText == null || altText.isBlank() || altText.trim().length() > 160 || version < 0) {
            throw invalid("Provide a photo description and the saved photo version.");
        }
        var before = repository.photo(tenantId, resourceId).orElse(null);
        if ((before == null && version != 0) || (before != null && before.version() != version)) throw conflict();
        var image = validator.validate(file);
        if (image.sizeBytes() > 10_485_760L || !image.contentType().equals(file.getContentType())) {
            throw invalid("A verified PNG or JPEG photograph of at most 10 MiB is required.");
        }
        String key = storage.store(tenantId, "workplace/resources/" + resourceId, image.extension(), image.content());
        try {
            // Durable staged metadata survives rollback and uses the existing fenced media cleanup worker.
            cleanup.registerStaged(tenantId, key);
            var row = new WorkplaceExperienceCollaborationRepository.PhotoRow(resourceId, key, altText.trim(),
                    image.contentType(), image.sizeBytes(), image.sha256(), 0);
            if (!repository.savePhoto(tenantId, actorId, row, version)) throw conflict();
            repository.referencePhotoMedia(tenantId, key);
            if (before != null) repository.enqueueMediaCleanup(tenantId, before.storageKey());
            ResourcePhoto saved = metadata(requirePhoto(tenantId, resourceId), true);
            collaboration.audit(tenantId, actorId, "workplace.experience.photo.saved", "WP_RESOURCE", resourceId,
                    correlationId, before == null ? null : metadata(before), saved, reason);
            return saved;
        } catch (RuntimeException exception) {
            try { storage.delete(tenantId, key); } catch (RuntimeException cleanupFailure) { exception.addSuppressed(cleanupFailure); }
            throw exception;
        }
    }

    @Transactional
    public MutationResult delete(long tenantId, long actorId, UUID resourceId, long version,
                                 String reason, String correlationId) {
        requireResource(tenantId, resourceId);
        WorkplaceExperienceCollaborationService.requireConfirmation(reason, true);
        var before = requirePhoto(tenantId, resourceId);
        if (version < 0 || !repository.deletePhoto(tenantId, resourceId, version)) throw conflict();
        repository.enqueueMediaCleanup(tenantId, before.storageKey());
        collaboration.audit(tenantId, actorId, "workplace.experience.photo.deleted", "WP_RESOURCE", resourceId,
                correlationId, metadata(before), new MutationResult(true), reason);
        return new MutationResult(true);
    }

    private void requireMemberScope(long tenantId, long userId, String groups, UUID resourceId) {
        var location = repository.resourceLocation(tenantId, resourceId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        runtime.requireViewAccess(tenantId, userId, groups, location.siteId(), location.floorId());
    }

    private UUID requireResource(long tenantId, UUID resourceId) {
        return repository.resourceSite(tenantId, resourceId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
    }

    private WorkplaceExperienceCollaborationRepository.PhotoRow requirePhoto(long tenantId, UUID resourceId) {
        return repository.photo(tenantId, resourceId).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND, "No photograph is registered for this resource."));
    }

    private ResourcePhoto metadata(WorkplaceExperienceCollaborationRepository.PhotoRow row) {
        return metadata(row, false);
    }

    private ResourcePhoto metadata(WorkplaceExperienceCollaborationRepository.PhotoRow row, boolean administrator) {
        return new ResourcePhoto(row.resourceId(), "/api/platform/v1/" + (administrator ? "admin/" : "") + "workplace/experience/collaboration/resources/"
                + row.resourceId() + "/photo", row.altText(), row.contentType(), row.sizeBytes(), row.sha256(), row.version());
    }

    private BaseException invalid(String message) { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message); }
    private BaseException conflict() { return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, "The photograph changed. Refresh its metadata before retrying."); }
    public record PhotoContent(Resource resource, ResourcePhoto metadata) {}
}
