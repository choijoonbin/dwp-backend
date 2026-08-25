package com.dwp.services.platform.home.personalization;

import com.dwp.services.platform.home.preference.HomePreference;
import com.dwp.services.platform.home.preference.HomePreferenceDtos;
import com.dwp.services.platform.home.preference.HomePreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit rollout bridge between the legacy single preference and the Phase 2 default view.
 * Both switches default off; the legacy row is never removed by this component.
 */
@Component
public class HomeViewCompatibilityBridge {
    private static final Logger log = LoggerFactory.getLogger(HomeViewCompatibilityBridge.class);
    private static final int READINESS_PAGE_SIZE = 1_000;
    private static final Duration READINESS_CACHE_TTL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<HomePreferenceService> preferenceServices;
    private final ConcurrentHashMap<Long, CachedReadiness> readinessCache =
            new ConcurrentHashMap<>();

    @Value("${dwp.platform.home.views-dual-write-enabled:false}")
    private boolean dualWriteEnabled;

    @Value("${dwp.platform.home.views-shadow-compare-enabled:false}")
    private boolean shadowCompareEnabled;

    @Autowired
    public HomeViewCompatibilityBridge(
            JdbcTemplate jdbc,
            MeterRegistry meters,
            ObjectMapper objectMapper,
            ObjectProvider<HomePreferenceService> preferenceServices) {
        this.jdbc = jdbc;
        this.meters = meters;
        this.objectMapper = objectMapper;
        this.preferenceServices = preferenceServices;
    }

    HomeViewCompatibilityBridge(
            JdbcTemplate jdbc,
            MeterRegistry meters,
            ObjectMapper objectMapper) {
        this(jdbc, meters, objectMapper, null);
    }

    public void mirrorLegacyPreference(HomePreference preference) {
        if (!dualWriteEnabled || preference == null) return;
        invalidateReadiness(preference.getTenantId());
        int affected = jdbc.update("""
                UPDATE usr_home_views
                   SET schema_version = ?, layout_payload = ?::jsonb,
                       integrity_state = 'VALID', is_customized = ?,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND surface_key = ? AND is_default
                   AND deleted_at IS NULL
                """,
                preference.getSchemaVersion(), preference.getLayoutPayload().toString(),
                preference.isCustomized(), preference.getUpdatedBy(),
                preference.getTenantId(), preference.getUserId(),
                preference.getSurfaceKey());
        if (affected > 0) {
            appendClassicRevision(preference.getTenantId(), preference.getUserId(),
                    preference.getSurfaceKey(), "Classic preference updated");
            return;
        }
        affected = jdbc.update("""
                INSERT INTO usr_home_views (
                    view_id, tenant_id, user_id, surface_key, view_key, name, is_default,
                    schema_version, layout_payload, is_customized,
                    version, created_by, updated_by)
                SELECT gen_random_uuid(), ?, ?, ?, 'default', 'My home', TRUE,
                       ?, ?::jsonb, ?, 0, ?, ?
                 WHERE NOT EXISTS (
                    SELECT 1 FROM usr_home_views
                     WHERE tenant_id = ? AND user_id = ? AND surface_key = ?
                       AND deleted_at IS NULL)
                ON CONFLICT (tenant_id, user_id, surface_key, view_key)
                    WHERE deleted_at IS NULL
                DO UPDATE
                    SET schema_version = EXCLUDED.schema_version,
                        layout_payload = EXCLUDED.layout_payload,
                        integrity_state = 'VALID',
                        is_customized = EXCLUDED.is_customized,
                        version = usr_home_views.version + 1,
                        updated_at = CURRENT_TIMESTAMP,
                        updated_by = EXCLUDED.updated_by
                """,
                preference.getTenantId(), preference.getUserId(), preference.getSurfaceKey(),
                preference.getSchemaVersion(), preference.getLayoutPayload().toString(),
                preference.isCustomized(), preference.getCreatedBy(), preference.getUpdatedBy(),
                preference.getTenantId(), preference.getUserId(), preference.getSurfaceKey());
        if (affected == 0) {
            log.warn("Skipped legacy-to-view dual write because the view scope has no active default.");
        } else {
            appendClassicRevision(preference.getTenantId(), preference.getUserId(),
                    preference.getSurfaceKey(), "Classic preference created");
        }
    }

    public void mirrorLegacyReset(
            Long tenantId,
            Long userId,
            String surfaceKey,
            JsonNode serializedLayout) {
        if (!dualWriteEnabled) return;
        invalidateReadiness(tenantId);
        int affected = jdbc.update("""
                UPDATE usr_home_views
                   SET schema_version = ?, layout_payload = ?::jsonb,
                       integrity_state = 'VALID', is_customized = FALSE,
                       version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND user_id = ? AND surface_key = ? AND is_default
                   AND deleted_at IS NULL
                """,
                HomePreferenceDtos.SCHEMA_VERSION, serializedLayout.toString(), userId,
                tenantId, userId, surfaceKey);
        if (affected > 0) {
            // A Classic reset is the same durable organization-default operation as a
            // Flow View reset. Remove advanced child overlays before capturing the revision,
            // otherwise a later VIEWS cutover would silently reapply stale personalization.
            jdbc.update("""
                    DELETE FROM usr_home_widget_configurations child
                     USING usr_home_views active
                     WHERE child.view_id = active.view_id
                       AND child.tenant_id = active.tenant_id
                       AND child.user_id = active.user_id
                       AND active.tenant_id = ? AND active.user_id = ?
                       AND active.surface_key = ? AND active.is_default
                       AND active.deleted_at IS NULL
                    """, tenantId, userId, surfaceKey);
            jdbc.update("""
                    DELETE FROM usr_home_view_device_layouts child
                     USING usr_home_views active
                     WHERE child.view_id = active.view_id
                       AND child.tenant_id = active.tenant_id
                       AND child.user_id = active.user_id
                       AND active.tenant_id = ? AND active.user_id = ?
                       AND active.surface_key = ? AND active.is_default
                       AND active.deleted_at IS NULL
                    """, tenantId, userId, surfaceKey);
            appendClassicRevision(
                    tenantId, userId, surfaceKey, "Classic preference reset");
        }
    }

    public void mirrorDefaultView(HomeView view) {
        if (!dualWriteEnabled || view == null || !view.isDefaultView()) return;
        invalidateReadiness(view.getTenantId());
        // usr_home_preferences is the legacy LocalDateTime/TIMESTAMP store. Pass
        // the JVM wall clock explicitly instead of mixing PostgreSQL UTC
        // CURRENT_TIMESTAMP into rows that JPA interprets in the JVM zone.
        LocalDateTime legacyClock = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO usr_home_preferences (
                    tenant_id, user_id, surface_key, schema_version, layout_payload,
                    version, created_at, created_by, updated_at, updated_by, is_customized)
                VALUES (?, ?, ?, ?, ?::jsonb, 0, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, user_id, surface_key) DO UPDATE
                    SET schema_version = EXCLUDED.schema_version,
                        layout_payload = EXCLUDED.layout_payload,
                        version = usr_home_preferences.version + 1,
                        updated_at = EXCLUDED.updated_at,
                        is_customized = EXCLUDED.is_customized,
                        updated_by = EXCLUDED.updated_by
                """,
                view.getTenantId(), view.getUserId(), view.getSurfaceKey(),
                view.getSchemaVersion(), view.getLayoutPayload().toString(),
                legacyClock, view.getCreatedBy(), legacyClock, view.getUpdatedBy(),
                view.isCustomized());
    }

    public void shadowCompare(HomePreference legacy) {
        if (!shadowCompareEnabled || legacy == null) return;
        try {
            String candidateJson = jdbc.query("""
                            SELECT layout_payload
                              FROM usr_home_views
                             WHERE tenant_id = ? AND user_id = ? AND surface_key = ? AND is_default
                               AND deleted_at IS NULL
                             LIMIT 1
                            """,
                    result -> result.next() ? result.getString("layout_payload") : null,
                    legacy.getTenantId(), legacy.getUserId(), legacy.getSurfaceKey());
            JsonNode candidate = candidateJson == null
                    ? null : objectMapper.readTree(candidateJson);
            String outcome = sameNormalizedLayout(
                    legacy.getSurfaceKey(), candidate, legacy.getLayoutPayload())
                    ? "match" : "mismatch";
            meters.counter("dwp.home.preference.shadow.compare", "outcome", outcome).increment();
            if ("mismatch".equals(outcome)) {
                log.warn("Home preference shadow mismatch for tenant {}, user {}, surface {}.",
                        legacy.getTenantId(), legacy.getUserId(), legacy.getSurfaceKey());
            }
        } catch (Exception exception) {
            meters.counter("dwp.home.preference.shadow.compare", "outcome", "unavailable")
                    .increment();
            log.warn("Home preference shadow comparison was unavailable.", exception);
        }
    }

    boolean sameLayout(JsonNode left, JsonNode right) {
        return left != null && right != null && left.equals(right);
    }

    public boolean readCutoverReady(Long tenantId) {
        if (!dualWriteEnabled || !shadowCompareEnabled) return false;
        CachedReadiness cached = readinessCache.get(tenantId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (cached != null && cached.expiresAt().isAfter(now)) return cached.ready();
        Boolean ready = jdbc.queryForObject("""
                SELECT NOT EXISTS (
                    SELECT 1
                      FROM usr_home_preferences legacy
                      LEFT JOIN usr_home_views active
                        ON active.tenant_id = legacy.tenant_id
                       AND active.user_id = legacy.user_id
                       AND active.surface_key = legacy.surface_key
                       AND active.is_default
                       AND active.deleted_at IS NULL
                     WHERE legacy.tenant_id = ?
                       AND (active.view_id IS NULL
                            OR active.integrity_state <> 'VALID'
                            OR active.is_customized IS DISTINCT FROM legacy.is_customized))
                   AND NOT EXISTS (
                    SELECT 1
                      FROM usr_home_views active
                      LEFT JOIN usr_home_preferences legacy
                        ON legacy.tenant_id = active.tenant_id
                       AND legacy.user_id = active.user_id
                       AND legacy.surface_key = active.surface_key
                     WHERE active.tenant_id = ?
                       AND active.is_default
                       AND active.deleted_at IS NULL
                       AND (active.integrity_state <> 'VALID'
                            OR legacy.home_preference_id IS NULL
                            OR active.is_customized IS DISTINCT FROM legacy.is_customized))
                """, Boolean.class, tenantId, tenantId);
        boolean verified = Boolean.TRUE.equals(ready) && normalizedRowsMatch(tenantId);
        readinessCache.put(tenantId, new CachedReadiness(
                verified, now.plus(READINESS_CACHE_TTL)));
        return verified;
    }

    boolean normalizedRowsMatch(Long tenantId) {
        HomePreferenceService validator = preferenceServices == null
                ? null : preferenceServices.getIfAvailable();
        if (validator == null) return false;
        RowMapper<LayoutCandidate> mapper = (result, row) -> new LayoutCandidate(
                result.getLong("user_id"), result.getString("surface_key"),
                result.getString("legacy_layout"), result.getString("view_layout"));
        Long afterUserId = null;
        String afterSurfaceKey = null;
        while (true) {
            List<LayoutCandidate> candidates = afterUserId == null
                    ? jdbc.query("""
                            SELECT legacy.user_id, legacy.surface_key,
                                   legacy.layout_payload::text AS legacy_layout,
                                   active.layout_payload::text AS view_layout
                              FROM usr_home_preferences legacy
                              JOIN usr_home_views active
                                ON active.tenant_id = legacy.tenant_id
                               AND active.user_id = legacy.user_id
                               AND active.surface_key = legacy.surface_key
                               AND active.is_default
                               AND active.deleted_at IS NULL
                             WHERE legacy.tenant_id = ?
                             ORDER BY legacy.user_id, legacy.surface_key
                             LIMIT ?
                            """, mapper, tenantId, READINESS_PAGE_SIZE)
                    : jdbc.query("""
                            SELECT legacy.user_id, legacy.surface_key,
                                   legacy.layout_payload::text AS legacy_layout,
                                   active.layout_payload::text AS view_layout
                              FROM usr_home_preferences legacy
                              JOIN usr_home_views active
                                ON active.tenant_id = legacy.tenant_id
                               AND active.user_id = legacy.user_id
                               AND active.surface_key = legacy.surface_key
                               AND active.is_default
                               AND active.deleted_at IS NULL
                             WHERE legacy.tenant_id = ?
                               AND (legacy.user_id > ? OR
                                    (legacy.user_id = ? AND legacy.surface_key > ?))
                             ORDER BY legacy.user_id, legacy.surface_key
                             LIMIT ?
                            """, mapper, tenantId, afterUserId, afterUserId,
                            afterSurfaceKey, READINESS_PAGE_SIZE);
            for (LayoutCandidate candidate : candidates) {
                try {
                    JsonNode legacy = objectMapper.readTree(candidate.legacyLayout());
                    JsonNode view = objectMapper.readTree(candidate.viewLayout());
                    if (!sameNormalizedLayout(candidate.surfaceKey(), legacy, view)) return false;
                } catch (Exception exception) {
                    log.warn("Home view normalized cutover verification failed for tenant {}.",
                            tenantId, exception);
                    return false;
                }
            }
            if (candidates.size() < READINESS_PAGE_SIZE) return true;
            LayoutCandidate last = candidates.getLast();
            afterUserId = last.userId();
            afterSurfaceKey = last.surfaceKey();
        }
    }

    boolean sameNormalizedLayout(String surfaceKey, JsonNode left, JsonNode right) {
        HomePreferenceService validator = preferenceServices == null
                ? null : preferenceServices.getIfAvailable();
        if (validator == null) return sameLayout(left, right);
        try {
            HomePreferenceDtos.HomeLayoutPayload leftLayout = objectMapper.treeToValue(
                    left, HomePreferenceDtos.HomeLayoutPayload.class);
            HomePreferenceDtos.HomeLayoutPayload rightLayout = objectMapper.treeToValue(
                    right, HomePreferenceDtos.HomeLayoutPayload.class);
            JsonNode leftNormalized = objectMapper.valueToTree(
                    validator.reconcileStoredForSurface(surfaceKey, leftLayout));
            JsonNode rightNormalized = objectMapper.valueToTree(
                    validator.reconcileStoredForSurface(surfaceKey, rightLayout));
            return leftNormalized.equals(rightNormalized);
        } catch (Exception exception) {
            return false;
        }
    }

    private void invalidateReadiness(Long tenantId) {
        if (tenantId != null) readinessCache.remove(tenantId);
    }

    private void appendClassicRevision(
            Long tenantId, Long userId, String surfaceKey, String summary) {
        jdbc.update("""
                INSERT INTO usr_home_view_revisions (
                    revision_id, view_id, tenant_id, user_id, revision_number,
                    schema_version, snapshot, source, change_summary, created_at, created_by)
                SELECT gen_random_uuid(), active.view_id, active.tenant_id, active.user_id,
                       COALESCE((SELECT MAX(previous.revision_number)
                                   FROM usr_home_view_revisions previous
                                  WHERE previous.view_id = active.view_id), 0) + 1,
                       active.schema_version,
                       jsonb_build_object(
                           'snapshotVersion', 1,
                           'legacyLayoutOnly', false,
                           'view', jsonb_build_object(
                               'name', active.name,
                               'customized', active.is_customized,
                               'schemaVersion', active.schema_version,
                               'layout', active.layout_payload),
                           'widgetConfigurations', COALESCE((
                               SELECT jsonb_object_agg(config.widget_key, config.configuration_payload)
                                 FROM usr_home_widget_configurations config
                                WHERE config.view_id = active.view_id
                                  AND config.tenant_id = active.tenant_id
                                  AND config.user_id = active.user_id), '{}'::jsonb),
                           'deviceLayouts', COALESCE((
                               SELECT jsonb_object_agg(device.device_class, device.overlay_payload)
                                 FROM usr_home_view_device_layouts device
                                WHERE device.view_id = active.view_id
                                  AND device.tenant_id = active.tenant_id
                                  AND device.user_id = active.user_id), '{}'::jsonb)),
                       'USER', ?, CURRENT_TIMESTAMP, ?
                  FROM usr_home_views active
                 WHERE active.tenant_id = ? AND active.user_id = ?
                   AND active.surface_key = ? AND active.is_default
                   AND active.deleted_at IS NULL
                """, summary, userId, tenantId, userId, surfaceKey);
    }

    record LayoutCandidate(
            Long userId,
            String surfaceKey,
            String legacyLayout,
            String viewLayout) {
    }

    private record CachedReadiness(boolean ready, OffsetDateTime expiresAt) {
    }
}
