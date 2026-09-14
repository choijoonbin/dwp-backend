package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission.*;

/** Writable transactions fence delegated revocation even for catalog reads. Member owners stay separate. */
@Service
@Transactional
public class WorkplaceScopedCatalogAdminService {
    private final WorkplaceService workplace;
    private final WorkplaceCatalogRepository catalog;
    private final WorkplaceDelegatedAdminScopeGuard guard;

    public WorkplaceScopedCatalogAdminService(WorkplaceService workplace, WorkplaceCatalogRepository catalog,
                                              WorkplaceDelegatedAdminScopeGuard guard) {
        this.workplace = workplace;
        this.catalog = catalog;
        this.guard = guard;
    }

    public List<WorkplaceDtos.Site> sites(Long tenant, String locale,
                                         List<WorkplaceDelegatedAdminAccessScope> requested) {
        return requested.stream().map(scope -> {
            var fresh = fresh(scope, tenant, CATALOG_VIEW);
            var row = catalog.scopedSite(tenant, fresh.siteId(), korean(locale), fresh.floorIds())
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            return new WorkplaceDtos.Site(row.siteId(), row.campusId(), row.code(), row.name(),
                    row.nameKo(), row.nameEn(), row.type(), row.address(), row.timeZone(),
                    fresh.floorIds() == null ? row.totalFloorCount() : null,
                    row.configuredFloorCount(), row.resourceCount(), row.state(), row.version(),
                    fresh.floorIds() == null ? "SITE" : "FLOORS",
                    fresh.floorIds() == null ? null : fresh.floorIds().stream().sorted().toList());
        }).toList();
    }

    public List<WorkplaceDtos.Floor> floors(Long tenant, String locale,
                                           WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(requested, tenant, CATALOG_VIEW);
        return catalog.scopedFloors(tenant, fresh.siteId(), korean(locale), fresh.floorIds()).stream()
                .map(row -> new WorkplaceDtos.Floor(row.floorId(), row.siteId(), row.siteName(), row.floorNumber(),
                        row.name(), row.nameKo(), row.nameEn(), row.planWidth(), row.planHeight(),
                        row.backgroundAssetPath(), row.state(), row.resourceCount(), row.version())).toList();
    }

    public List<WorkplaceDtos.Resource> resources(Long tenant, UUID floor, String locale,
                                                  WorkplaceDelegatedAdminAccessScope requested) {
        requireFloor(tenant, floor, fresh(requested, tenant, CATALOG_VIEW));
        return workplace.resources(tenant, floor, locale);
    }

    public WorkplaceDtos.Site saveSite(Long tenant, Long actor, UUID site, String locale, String correlation,
                                        WorkplaceDtos.SiteRequest input, WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(requested, tenant, CATALOG_MANAGE);
        fresh.requireSiteWide();
        if (!fresh.siteId().equals(site)) throw new BaseException(ErrorCode.FORBIDDEN);
        return workplace.saveSite(tenant, actor, site, locale, correlation, input);
    }

    public WorkplaceDtos.Floor saveFloor(Long tenant, Long actor, UUID site, UUID floor, String locale,
                                          String correlation, WorkplaceDtos.FloorRequest input,
                                          WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(requested, tenant, CATALOG_MANAGE);
        if (!fresh.siteId().equals(site)) throw new BaseException(ErrorCode.FORBIDDEN);
        if (floor == null) fresh.requireSiteWide(); else requireFloor(tenant, floor, fresh);
        return workplace.saveFloor(tenant, actor, site, floor, locale, correlation, input);
    }

    public WorkplaceDtos.Resource saveResource(Long tenant, Long actor, UUID floor, UUID resource, String locale,
                                                String correlation, WorkplaceDtos.ResourceRequest input,
                                                WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(requested, tenant, CATALOG_MANAGE);
        requireFloor(tenant, floor, fresh);
        if (resource != null) {
            guard.requireTarget(fresh, com.dwp.services.platform.workplace.WorkplaceDelegatedAdminTargetType.RESOURCE, resource);
            var actual = catalog.resource(tenant, resource, korean(locale))
                    .orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
            fresh.requireFloor(actual.floorId());
            if (!actual.floorId().equals(floor)) throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return workplace.saveResource(tenant, actor, floor, resource, locale, correlation, input);
    }

    public List<WorkplaceDtos.Resource> updateLayout(Long tenant, Long actor, UUID floor, String locale,
                                                     String correlation, WorkplaceDtos.LayoutRequest input,
                                                     WorkplaceDelegatedAdminAccessScope requested) {
        requireFloor(tenant, floor, fresh(requested, tenant, CATALOG_MANAGE));
        return workplace.updateLayout(tenant, actor, floor, locale, correlation, input);
    }

    public WorkplaceDtos.Floor uploadFloorBackground(Long tenant, Long actor, UUID floor, Long version,
                                                      String locale, String correlation, MultipartFile file,
                                                      WorkplaceDelegatedAdminAccessScope requested) {
        requireFloor(tenant, floor, fresh(requested, tenant, CATALOG_MANAGE));
        return workplace.uploadFloorBackground(tenant, actor, floor, version, locale, correlation, file);
    }

    public WorkplaceSpatialGovernanceDtos.FloorPlanRevision uploadDraftFloorBackground(Long tenant, Long actor,
            UUID revision, Long version, String summary, String correlation, MultipartFile file,
            WorkplaceDelegatedAdminAccessScope requested) {
        requireFloor(tenant, catalog.revisionFloor(tenant, revision), fresh(requested, tenant, FLOOR_PLAN_MANAGE));
        return workplace.uploadDraftFloorBackground(tenant, actor, revision, version, summary, correlation, file);
    }

    public WorkplaceService.FloorBackground revisionBackground(Long tenant, UUID revision,
                                                                WorkplaceDelegatedAdminAccessScope requested) {
        var fresh = fresh(requested, tenant, CATALOG_VIEW);
        requireFloor(tenant, catalog.revisionFloor(tenant, revision), fresh);
        return workplace.floorPlanRevisionBackground(tenant, revision);
    }

    private void requireFloor(Long tenant, UUID floor, WorkplaceDelegatedAdminAccessScope fresh) {
        fresh.requireFloor(floor);
        var actual = catalog.floor(tenant, floor, false).orElseThrow(() -> new BaseException(ErrorCode.NOT_FOUND));
        if (!fresh.siteId().equals(actual.siteId())) throw new BaseException(ErrorCode.FORBIDDEN);
        fresh.requireFloor(floor);
    }

    private WorkplaceDelegatedAdminAccessScope fresh(WorkplaceDelegatedAdminAccessScope requested, Long tenant, DelegatedPermission permission) {
        var fresh = guard.revalidate(requested);
        if (tenant == null || fresh.tenantId() != tenant || fresh.permission() != permission) throw new BaseException(ErrorCode.FORBIDDEN);
        return fresh;
    }

    private boolean korean(String locale) { return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("ko"); }
}
