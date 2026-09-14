package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.*;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceDelegatedAdminTargetType.RESOURCE;

/** Native resource ownership is checked before photo metadata, bytes, storage, or version mutation. */
@Service
@Transactional
public class WorkplaceScopedExperienceMediaService {
    private final WorkplaceExperienceCollaborationMediaService service;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    public WorkplaceScopedExperienceMediaService(WorkplaceExperienceCollaborationMediaService service,
                                                 WorkplaceDelegatedAdminScopeGuard guard) {
        this.service = service;
        this.guard = guard;
    }

    public ResourcePhoto adminMetadata(long tenant, UUID resource, WorkplaceDelegatedAdminAccessScope requested) {
        requireResource(tenant, resource, requested, CATALOG_VIEW);
        return service.adminMetadata(tenant, resource);
    }

    public WorkplaceExperienceCollaborationMediaService.PhotoContent adminContent(long tenant, UUID resource,
                                                                                  WorkplaceDelegatedAdminAccessScope requested) {
        requireResource(tenant, resource, requested, CATALOG_VIEW);
        return service.adminContent(tenant, resource);
    }

    public ResourcePhoto upload(long tenant, long actor, UUID resource, long version, String reason,
                                String altText, MultipartFile file, String correlation,
                                WorkplaceDelegatedAdminAccessScope requested) {
        requireResource(tenant, resource, requested, CATALOG_MANAGE);
        return service.upload(tenant, actor, resource, version, reason, altText, file, correlation);
    }

    public MutationResult delete(long tenant, long actor, UUID resource, long version, String reason,
                                 String correlation, WorkplaceDelegatedAdminAccessScope requested) {
        requireResource(tenant, resource, requested, CATALOG_MANAGE);
        return service.delete(tenant, actor, resource, version, reason, correlation);
    }

    private void requireResource(long tenant, UUID resource, WorkplaceDelegatedAdminAccessScope requested, DelegatedPermission permission) {
        var fresh = guard.revalidate(requested);
        if (fresh.tenantId() != tenant || fresh.permission() != permission) throw new BaseException(ErrorCode.FORBIDDEN);
        guard.requireTarget(fresh, RESOURCE, resource);
    }
}
