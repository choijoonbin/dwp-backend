package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
class WorkplaceRoomAccessAdapter implements WorkplaceRoomAccessPort {

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkplaceRuntimeGovernance governance;

    WorkplaceRoomAccessAdapter(
            NamedParameterJdbcTemplate jdbc,
            WorkplaceRuntimeGovernance governance) {
        this.jdbc = jdbc;
        this.governance = governance;
    }

    @Override
    public Set<UUID> viewableResourceIds(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            Collection<UUID> calendarResourceIds) {
        if (calendarResourceIds == null || calendarResourceIds.isEmpty()) return Set.of();
        Set<UUID> requested = calendarResourceIds.stream()
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) return Set.of();
        List<ResourceSite> locations = resourceSites(tenantId, requested);
        if (locations.isEmpty()) return Set.of();
        Set<UUID> allowedSites = governance.viewableSiteIds(tenantId, userId, verifiedGroupRefs,
                locations.stream().map(ResourceSite::siteId).collect(Collectors.toUnmodifiableSet()));
        Map<UUID, UUID> sitesByFloor = locations.stream().filter(value -> allowedSites.contains(value.siteId()))
                .collect(Collectors.toMap(ResourceSite::floorId, ResourceSite::siteId, (left, right) -> left));
        Set<UUID> allowedFloors = governance.viewableFloorIds(tenantId, userId, verifiedGroupRefs, sitesByFloor);
        return locations.stream().filter(value -> requested.contains(value.calendarResourceId())
                        && allowedSites.contains(value.siteId()) && allowedFloors.contains(value.floorId()))
                .map(ResourceSite::calendarResourceId).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void requireBook(
            Long tenantId,
            Long userId,
            String verifiedGroupRefs,
            UUID calendarResourceId) {
        ResourceSite location = location(tenantId, calendarResourceId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.FORBIDDEN,
                        "This meeting room is not mapped to an authorized Workplace location."));
        governance.requireBookAccess(tenantId, userId, verifiedGroupRefs, location.siteId(), location.floorId());
    }

    private Optional<ResourceSite> location(Long tenantId, UUID calendarResourceId) {
        if (calendarResourceId == null) return Optional.empty();
        return resourceSites(tenantId, Set.of(calendarResourceId)).stream()
                .findFirst();
    }

    private List<ResourceSite> resourceSites(
            Long tenantId, Collection<UUID> calendarResourceIds) {
        return jdbc.query("""
                SELECT resource.calendar_resource_id, site.site_id, floor.floor_id
                  FROM wp_resources resource
                  JOIN wp_floors floor
                    ON floor.tenant_id = resource.tenant_id
                   AND floor.floor_id = resource.floor_id
                  JOIN wp_sites site
                    ON site.tenant_id = floor.tenant_id
                   AND site.site_id = floor.site_id
                 WHERE resource.tenant_id = :tenantId
                   AND resource.calendar_resource_id IN (:resourceIds)
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("resourceIds", calendarResourceIds),
                (result, ignored) -> new ResourceSite(
                        result.getObject("calendar_resource_id", UUID.class),
                        result.getObject("site_id", UUID.class),
                        result.getObject("floor_id", UUID.class)));
    }

    record ResourceSite(UUID calendarResourceId, UUID siteId, UUID floorId) {
    }
}
