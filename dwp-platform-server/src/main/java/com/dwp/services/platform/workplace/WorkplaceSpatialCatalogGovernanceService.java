package com.dwp.services.platform.workplace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.*;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository.*;

final class WorkplaceSpatialCatalogGovernanceService
        extends WorkplaceSpatialGovernanceSupport {

    WorkplaceSpatialCatalogGovernanceService(
            WorkplaceSpatialGovernanceRepository repository,
            ObjectMapper objectMapper) {
        super(repository, objectMapper);
    }

    List<Campus> campuses(Long tenantId, Set<UUID> visibleSiteIds) {
        List<CampusRow> rows = visibleSiteIds == null
                ? repository.campuses(tenantId)
                : repository.campusesForSites(tenantId, visibleSiteIds);
        return rows.stream().map(this::campus).toList();
    }

    Campus saveCampus(
            Long tenantId,
            Long actorId,
            UUID campusId,
            String correlationId,
            CampusRequest request) {
        requireCreateOrUpdateVersion(campusId, request.version(), "campus");
        CampusRow before = campusId == null ? null : requireCampus(tenantId, campusId);
        UUID targetId = campusId == null ? UUID.randomUUID() : campusId;
        try {
            if (campusId == null) {
                repository.createCampus(tenantId, actorId, targetId, request);
            } else if (!repository.updateCampus(tenantId, actorId, targetId, request)) {
                throw conflict("The campus changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The campus code is already in use.", exception);
        }
        CampusRow after = requireCampus(tenantId, targetId);
        audit(tenantId, actorId,
                campusId == null ? "workplace.governance.campus.created"
                        : "workplace.governance.campus.updated",
                "WP_CAMPUS", targetId, correlationId, before, after, null);
        return campus(after);
    }

    SiteCampusAssignment assignSiteCampus(
            Long tenantId,
            Long actorId,
            UUID siteId,
            String correlationId,
            SiteCampusAssignmentRequest request) {
        SiteCampusRow before = repository.siteCampus(tenantId, siteId)
                .orElseThrow(this::notFound);
        requireCampus(tenantId, request.campusId());
        if (!repository.assignSiteCampus(
                tenantId, actorId, siteId, request.campusId(), request.siteVersion())) {
            throw conflict("The building changed. Refresh and retry.");
        }
        SiteCampusRow after = repository.siteCampus(tenantId, siteId)
                .orElseThrow(this::notFound);
        audit(tenantId, actorId, "workplace.governance.building.campus.assigned",
                "WP_SITE", siteId, correlationId, before, after, null);
        return new SiteCampusAssignment(after.siteId(), after.campusId(), after.version());
    }

    List<Zone> zones(Long tenantId, UUID floorId) {
        requireFloor(tenantId, floorId);
        return repository.zones(tenantId, floorId).stream().map(this::zone).toList();
    }

    Zone saveZone(
            Long tenantId,
            Long actorId,
            UUID floorId,
            UUID zoneId,
            String correlationId,
            ZoneRequest request) {
        requireFloor(tenantId, floorId);
        validateSpatialJson(request.boundary(), "Zone boundary");
        requireCreateOrUpdateVersion(zoneId, request.version(), "zone");
        ZoneRow before = null;
        if (zoneId != null) {
            before = requireZone(tenantId, zoneId);
            if (!before.floorId().equals(floorId)) throw notFound();
        }
        UUID targetId = zoneId == null ? UUID.randomUUID() : zoneId;
        try {
            if (zoneId == null) {
                repository.createZone(tenantId, actorId, floorId, targetId, request);
            } else if (!repository.updateZone(
                    tenantId, actorId, floorId, targetId, request)) {
                throw conflict("The zone changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The zone code is already in use on this floor.", exception);
        }
        ZoneRow after = requireZone(tenantId, targetId);
        audit(tenantId, actorId,
                zoneId == null ? "workplace.governance.zone.created"
                        : "workplace.governance.zone.updated",
                "WP_ZONE", targetId, correlationId, before, after, null);
        return zone(after);
    }

    List<Section> sections(Long tenantId, UUID zoneId) {
        requireZone(tenantId, zoneId);
        return repository.sections(tenantId, zoneId).stream().map(this::section).toList();
    }

    Section saveSection(
            Long tenantId,
            Long actorId,
            UUID zoneId,
            UUID sectionId,
            String correlationId,
            SectionRequest request) {
        ZoneRow parent = requireZone(tenantId, zoneId);
        validateSpatialJson(request.boundary(), "Section boundary");
        requireCreateOrUpdateVersion(sectionId, request.version(), "section");
        SectionRow before = null;
        if (sectionId != null) {
            before = requireSection(tenantId, sectionId);
            if (!before.zoneId().equals(zoneId)) throw notFound();
        }
        UUID targetId = sectionId == null ? UUID.randomUUID() : sectionId;
        try {
            if (sectionId == null) {
                repository.createSection(
                        tenantId, actorId, parent.floorId(), zoneId, targetId, request);
            } else if (!repository.updateSection(
                    tenantId, actorId, zoneId, targetId, request)) {
                throw conflict("The section changed. Refresh and retry.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw conflict("The section code is already in use in this zone.", exception);
        }
        SectionRow after = requireSection(tenantId, targetId);
        audit(tenantId, actorId,
                sectionId == null ? "workplace.governance.section.created"
                        : "workplace.governance.section.updated",
                "WP_SECTION", targetId, correlationId, before, after, null);
        return section(after);
    }
}
