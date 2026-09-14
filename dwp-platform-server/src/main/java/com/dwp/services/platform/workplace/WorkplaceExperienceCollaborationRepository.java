package com.dwp.services.platform.workplace;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceExperienceCollaborationDtos.*;

@Repository
public class WorkplaceExperienceCollaborationRepository {
    private final JdbcTemplate jdbc;

    public WorkplaceExperienceCollaborationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    void ensurePolicy(long tenantId) {
        jdbc.update("INSERT INTO wp_experience_sharing_policies (tenant_id) VALUES (?) ON CONFLICT DO NOTHING", tenantId);
    }

    SharingPolicy policy(long tenantId) {
        return jdbc.query("SELECT * FROM wp_experience_sharing_policies WHERE tenant_id = ?",
                (r, i) -> new SharingPolicy(r.getBoolean("sharing_enabled"),
                        Visibility.valueOf(r.getString("maximum_visibility")), r.getLong("version")),
                tenantId).stream().findFirst().orElse(new SharingPolicy(false, Visibility.SITE, 0));
    }

    boolean updatePolicy(long tenantId, long actorId, SharingPolicyRequest request) {
        return jdbc.update("""
                UPDATE wp_experience_sharing_policies
                   SET sharing_enabled = ?, maximum_visibility = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND version = ?
                """, request.sharingEnabled(), request.maximumVisibility().name(), actorId,
                tenantId, request.version()) == 1;
    }

    void ensurePreference(long tenantId, long userId) {
        jdbc.update("""
                INSERT INTO wp_experience_sharing_preferences (tenant_id, user_id)
                VALUES (?, ?) ON CONFLICT DO NOTHING
                """, tenantId, userId);
    }

    SharingPreference preference(long tenantId, long userId, boolean lock) {
        return jdbc.query("SELECT * FROM wp_experience_sharing_preferences WHERE tenant_id = ? AND user_id = ?"
                        + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new SharingPreference(r.getBoolean("opt_in"),
                        Visibility.valueOf(r.getString("visibility")), r.getLong("version")),
                tenantId, userId).stream().findFirst().orElse(new SharingPreference(false, Visibility.PRIVATE, 0));
    }

    boolean updatePreference(long tenantId, long userId, SharingPreferenceRequest request) {
        return jdbc.update("""
                UPDATE wp_experience_sharing_preferences
                   SET opt_in = ?, visibility = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND version = ?
                """, request.optIn(), request.visibility().name(), tenantId, userId, request.version()) == 1;
    }

    void privatizePlans(long tenantId, long userId) {
        jdbc.update("""
                UPDATE wp_experience_work_plans SET visibility = 'PRIVATE', version = version + 1,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND visibility <> 'PRIVATE'
                """, tenantId, userId);
    }

    List<WorkPlan> ownPlans(long tenantId, long userId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT * FROM wp_experience_work_plans
                 WHERE tenant_id = ? AND user_id = ? AND plan_date BETWEEN ? AND ?
                   AND expires_at >= CURRENT_DATE ORDER BY plan_date, plan_id
                """, this::workPlan, tenantId, userId, from, to);
    }

    Optional<WorkPlan> ownPlanOnDate(long tenantId, long userId, LocalDate date) {
        return jdbc.query("SELECT * FROM wp_experience_work_plans WHERE tenant_id = ? AND user_id = ? AND plan_date = ?",
                this::workPlan, tenantId, userId, date).stream().findFirst();
    }

    List<SharedCandidate> sharedPlans(long tenantId, long userId, LocalDate from, LocalDate to, Set<UUID> groups) {
        if (groups.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(groups.size(), "?"));
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(List.of(tenantId, userId, from, to));
        args.addAll(groups);
        return jdbc.query("""
                SELECT plan.*, preference.visibility AS preference_visibility
                  FROM wp_experience_work_plans plan
                  JOIN wp_experience_sharing_preferences preference
                    ON preference.tenant_id = plan.tenant_id AND preference.user_id = plan.user_id
                  JOIN wp_experience_sharing_policies policy ON policy.tenant_id = plan.tenant_id
                 WHERE plan.tenant_id = ? AND plan.user_id <> ? AND plan.plan_date BETWEEN ? AND ?
                   AND plan.expires_at >= CURRENT_DATE AND policy.sharing_enabled
                   AND preference.opt_in AND preference.visibility <> 'PRIVATE'
                   AND plan.visibility <> 'PRIVATE' AND plan.group_ref IN (%s)
                 ORDER BY plan.plan_date, plan.plan_id LIMIT 500
                """.formatted(placeholders), (r, i) -> new SharedCandidate(workPlan(r, i),
                r.getLong("user_id"), Visibility.valueOf(r.getString("preference_visibility"))), args.toArray());
    }

    void createPlan(long tenantId, long userId, UUID id, WorkPlanRequest request, long retentionDays) {
        jdbc.update("""
                INSERT INTO wp_experience_work_plans
                  (plan_id, tenant_id, user_id, plan_date, plan_mode, site_id, floor_id, resource_id,
                   group_ref, visibility, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, tenantId, userId, request.planDate(), request.mode().name(), request.siteId(),
                request.floorId(), request.resourceId(), request.groupRef(), request.visibility().name(),
                request.planDate().plusDays(retentionDays));
    }

    boolean updatePlan(long tenantId, long userId, WorkPlanRequest request, long retentionDays) {
        return jdbc.update("""
                UPDATE wp_experience_work_plans
                   SET plan_mode = ?, site_id = ?, floor_id = ?, resource_id = ?, group_ref = ?,
                       visibility = ?, expires_at = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND user_id = ? AND plan_date = ? AND version = ?
                """, request.mode().name(), request.siteId(), request.floorId(), request.resourceId(),
                request.groupRef(), request.visibility().name(), request.planDate().plusDays(retentionDays),
                tenantId, userId, request.planDate(), request.version()) == 1;
    }

    boolean deletePlan(long tenantId, long userId, UUID planId, long version) {
        return jdbc.update("DELETE FROM wp_experience_work_plans WHERE tenant_id = ? AND user_id = ? AND plan_id = ? AND version = ?",
                tenantId, userId, planId, version) == 1;
    }

    long retentionDays(long tenantId) {
        return jdbc.queryForObject("SELECT booking_retention_days FROM wp_tenant_policies WHERE tenant_id = ?", Long.class, tenantId);
    }

    @Transactional
    public int deleteExpiredWorkPlans(int batchSize) {
        if (batchSize < 1 || batchSize > 5000) throw new IllegalArgumentException("Invalid privacy maintenance batch size");
        return jdbc.update("""
                WITH expired AS (SELECT plan_id FROM wp_experience_work_plans
                    WHERE expires_at < CURRENT_DATE ORDER BY expires_at, plan_id
                    FOR UPDATE SKIP LOCKED LIMIT ?)
                DELETE FROM wp_experience_work_plans plan USING expired
                WHERE plan.plan_id = expired.plan_id
                """, batchSize);
    }

    boolean locationMatches(long tenantId, UUID siteId, UUID floorId, UUID resourceId) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wp_sites site
                 WHERE site.tenant_id = ? AND site.site_id = ? AND site.lifecycle_state = 'ACTIVE'
                   AND (?::UUID IS NULL OR EXISTS (SELECT 1 FROM wp_floors floor
                        WHERE floor.tenant_id = site.tenant_id AND floor.site_id = site.site_id
                          AND floor.floor_id = ? AND floor.lifecycle_state = 'ACTIVE'
                          AND (?::UUID IS NULL OR EXISTS (SELECT 1 FROM wp_resources resource
                              WHERE resource.tenant_id = floor.tenant_id AND resource.floor_id = floor.floor_id
                                AND resource.resource_id = ? AND resource.lifecycle_state = 'AVAILABLE'))))
                """, Long.class, tenantId, siteId, floorId, floorId, resourceId, resourceId);
        return count != null && count == 1;
    }

    Optional<UUID> resourceSite(long tenantId, UUID resourceId) {
        return jdbc.query("""
                SELECT floor.site_id FROM wp_resources resource JOIN wp_floors floor
                  ON floor.tenant_id = resource.tenant_id AND floor.floor_id = resource.floor_id
                 WHERE resource.tenant_id = ? AND resource.resource_id = ?
                """, (r, i) -> r.getObject("site_id", UUID.class), tenantId, resourceId).stream().findFirst();
    }

    Optional<ResourceLocation> resourceLocation(long tenantId, UUID resourceId) {
        return jdbc.query("""
                SELECT floor.site_id, floor.floor_id FROM wp_resources resource JOIN wp_floors floor
                  ON floor.tenant_id = resource.tenant_id AND floor.floor_id = resource.floor_id
                 WHERE resource.tenant_id = ? AND resource.resource_id = ?
                """, (r, i) -> new ResourceLocation(r.getObject("site_id", UUID.class),
                r.getObject("floor_id", UUID.class)), tenantId, resourceId).stream().findFirst();
    }

    record ResourceLocation(UUID siteId, UUID floorId) { }

    ConnectorStatus connector(long tenantId, ConnectorKind kind) {
        return jdbc.query("SELECT * FROM wp_experience_connector_configurations WHERE tenant_id = ? AND connector_kind = ?",
                (r, i) -> new ConnectorStatus(kind, r.getString("provider"),
                        !r.getBoolean("enabled") ? ConnectionState.DISABLED
                                : r.getString("configuration_reference") == null ? ConnectionState.NOT_CONFIGURED
                                : ConnectionState.CONFIGURED_UNVERIFIED,
                        r.getString("configuration_reference"), null, r.getLong("version")), tenantId, kind.name())
                .stream().findFirst().orElse(new ConnectorStatus(kind, null, ConnectionState.NOT_CONFIGURED, null, null, 0));
    }

    void ensureConnector(long tenantId, ConnectorKind kind) {
        jdbc.update("INSERT INTO wp_experience_connector_configurations (tenant_id, connector_kind) VALUES (?, ?) ON CONFLICT DO NOTHING",
                tenantId, kind.name());
    }

    boolean updateConnector(long tenantId, long actorId, ConnectorKind kind, ConnectorRequest request) {
        return jdbc.update("""
                UPDATE wp_experience_connector_configurations SET provider = ?, enabled = ?,
                    configuration_reference = ?, version = version + 1, updated_by = ?, updated_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = ? AND connector_kind = ? AND version = ?
                """, request.provider(), request.enabled(), request.configurationReference(), actorId,
                tenantId, kind.name(), request.version()) == 1;
    }

    PrivacySummary privacy(long tenantId) {
        return jdbc.query("""
                SELECT (SELECT booking_retention_days FROM wp_tenant_policies WHERE tenant_id = ?) AS retention,
                    COUNT(*) FILTER (WHERE legal_hold) AS holds,
                    COUNT(*) FILTER (WHERE anonymized_at IS NOT NULL) AS anonymized,
                    (SELECT COUNT(*) FROM wp_experience_facility_retention_eligible_requests x WHERE x.tenant_id=?) AS facility_requests,
                    (SELECT COUNT(*) FROM wp_experience_facility_retention_eligible_closures x WHERE x.tenant_id=?) AS facility_closures,
                    COALESCE((SELECT requests_purged FROM wp_experience_facility_retention_counters c WHERE c.tenant_id=?),0) AS requests_purged,
                    COALESCE((SELECT closures_purged FROM wp_experience_facility_retention_counters c WHERE c.tenant_id=?),0) AS closures_purged,
                    COUNT(*) FILTER (WHERE NOT legal_hold AND anonymized_at IS NULL
                        AND personal_data_expires_at <= CURRENT_TIMESTAMP
                        AND booking_status IN ('COMPLETED','NO_SHOW','RELEASED','CANCELLED')) AS eligible
                FROM wp_bookings WHERE tenant_id = ?
                """, (r, i) -> {
                    long retention = r.getLong("retention");
                    if (r.wasNull()) throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                            "The native Workplace booking policy has not been provisioned. Configure it before reading retention status.");
                    return new PrivacySummary(retention, r.getLong("holds"),
                            r.getLong("anonymized"), r.getLong("eligible"), r.getLong("facility_requests"),
                            r.getLong("facility_closures"), r.getLong("requests_purged"), r.getLong("closures_purged"));
                }, tenantId, tenantId, tenantId, tenantId, tenantId, tenantId).getFirst();
    }

    Optional<PhotoRow> photo(long tenantId, UUID resourceId) {
        return jdbc.query("SELECT * FROM wp_experience_resource_photos WHERE tenant_id = ? AND resource_id = ?",
                (r, i) -> new PhotoRow(resourceId, r.getString("storage_key"), r.getString("alt_text"),
                        r.getString("content_type"), r.getLong("size_bytes"), r.getString("sha256"), r.getLong("version")),
                tenantId, resourceId).stream().findFirst();
    }

    boolean savePhoto(long tenantId, long actorId, PhotoRow photo, long expectedVersion) {
        return jdbc.update("""
                INSERT INTO wp_experience_resource_photos
                    (tenant_id, resource_id, storage_key, alt_text, content_type, size_bytes, sha256, updated_by, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, floor(extract(epoch from clock_timestamp()) * 1000000)::BIGINT)
                ON CONFLICT (tenant_id, resource_id) DO UPDATE SET storage_key = EXCLUDED.storage_key,
                    alt_text = EXCLUDED.alt_text, content_type = EXCLUDED.content_type,
                    size_bytes = EXCLUDED.size_bytes, sha256 = EXCLUDED.sha256,
                    version = wp_experience_resource_photos.version + 1,
                    updated_by = EXCLUDED.updated_by, updated_at = CURRENT_TIMESTAMP
                WHERE wp_experience_resource_photos.version = ?
                """, tenantId, photo.resourceId(), photo.storageKey(), photo.altText(), photo.contentType(),
                photo.sizeBytes(), photo.sha256(), actorId, expectedVersion) == 1;
    }

    boolean deletePhoto(long tenantId, UUID resourceId, long version) {
        return jdbc.update("DELETE FROM wp_experience_resource_photos WHERE tenant_id = ? AND resource_id = ? AND version = ?",
                tenantId, resourceId, version) == 1;
    }

    void enqueueMediaCleanup(long tenantId, String key) {
        jdbc.update("""
                UPDATE wp_floor_plan_media_assets SET reference_count = 0, asset_status = 'PENDING_DELETE',
                    unreferenced_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND storage_key = ?
                """, tenantId, key);
        jdbc.update("""
                INSERT INTO sys_tenant_media_cleanup_outbox (tenant_id, storage_key, cleanup_reason)
                VALUES (?, ?, 'WORKPLACE_RESOURCE_PHOTO_REPLACED') ON CONFLICT DO NOTHING
                """, tenantId, key);
    }

    void referencePhotoMedia(long tenantId, String key) {
        jdbc.update("""
                UPDATE wp_floor_plan_media_assets SET reference_count = 1, asset_status = 'REFERENCED',
                    last_referenced_at = CURRENT_TIMESTAMP, unreferenced_at = NULL
                 WHERE tenant_id = ? AND storage_key = ? AND asset_status = 'STAGED'
                """, tenantId, key);
    }

    private WorkPlan workPlan(ResultSet r, int row) throws SQLException {
        return new WorkPlan(r.getObject("plan_id", UUID.class), r.getObject("plan_date", LocalDate.class),
                PlanMode.valueOf(r.getString("plan_mode")), r.getObject("site_id", UUID.class),
                r.getObject("floor_id", UUID.class), r.getObject("resource_id", UUID.class),
                r.getObject("group_ref", UUID.class), Visibility.valueOf(r.getString("visibility")), r.getLong("version"));
    }

    record SharedCandidate(WorkPlan plan, long userId, Visibility preferenceVisibility) {}
    record PhotoRow(UUID resourceId, String storageKey, String altText, String contentType,
                    long sizeBytes, String sha256, long version) {}
}
