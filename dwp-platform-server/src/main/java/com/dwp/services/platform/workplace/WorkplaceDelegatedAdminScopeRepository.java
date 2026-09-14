package com.dwp.services.platform.workplace;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegateType;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedPermission;
import static com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceDtos.DelegatedScopeType;

@Repository
class WorkplaceDelegatedAdminScopeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    WorkplaceDelegatedAdminScopeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DelegatedGrant> candidateGrants(Long tenantId, Long userId, Set<UUID> groups) {
        return candidateGrants(tenantId, userId, groups, false);
    }

    List<DelegatedGrant> candidateGrants(Long tenantId, Long userId, Set<UUID> groups, boolean lock) {
        String groupPredicate = groups.isEmpty() ? "" : " OR (scope.delegate_type = 'GROUP_REF' AND scope.delegate_group_ref IN (:groups))";
        MapSqlParameterSource parameters = new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("userId", userId);
        if (!groups.isEmpty()) parameters.addValue("groups", groups);
        String sql = """
                SELECT scope.delegation_id, scope.delegate_type, scope.delegate_user_id, scope.delegate_group_ref,
                       scope.scope_type, scope.site_id, scope.managed_group_ref, scope.permission_codes,
                       scope.valid_from, scope.valid_until, scope.floor_scope_restricted,
                       ARRAY(SELECT child.floor_id FROM wp_delegated_admin_scope_floors child
                             WHERE child.tenant_id = scope.tenant_id AND child.delegation_id = scope.delegation_id
                             ORDER BY child.floor_id) AS floor_ids
                  FROM wp_delegated_admin_scopes scope
                 WHERE scope.tenant_id = :tenantId AND scope.lifecycle_state = 'ACTIVE'
                   AND ((scope.delegate_type = 'USER' AND scope.delegate_user_id = :userId)
                """ + groupPredicate + ") ORDER BY scope.delegation_id" + (lock ? " FOR SHARE OF scope" : "");
        return jdbc.query(sql, parameters, (result, ignored) -> new DelegatedGrant(
                result.getObject("delegation_id", UUID.class), DelegateType.valueOf(result.getString("delegate_type")),
                result.getObject("delegate_user_id", Long.class), result.getObject("delegate_group_ref", UUID.class),
                DelegatedScopeType.valueOf(result.getString("scope_type")), result.getObject("site_id", UUID.class),
                result.getObject("managed_group_ref", UUID.class), permissions(result.getArray("permission_codes")),
                result.getObject("valid_from", OffsetDateTime.class), result.getObject("valid_until", OffsetDateTime.class),
                floorIds(result.getBoolean("floor_scope_restricted"), result.getArray("floor_ids"))));
    }

    OffsetDateTime databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", new MapSqlParameterSource(), OffsetDateTime.class);
    }

    List<UUID> tenantSiteIds(long tenantId) {
        return jdbc.query("SELECT site_id FROM wp_sites WHERE tenant_id = :tenantId ORDER BY site_id",
                new MapSqlParameterSource("tenantId", tenantId), (result, row) -> result.getObject("site_id", UUID.class));
    }

    record CanonicalTarget(UUID siteId, UUID floorId, boolean siteWide) { }

    Optional<CanonicalTarget> resolveTarget(Long tenantId, WorkplaceDelegatedAdminTargetType type, UUID id) {
        String sql = switch (type) {
            case SITE -> "SELECT site_id, NULL::uuid AS floor_id FROM wp_sites WHERE tenant_id=:tenant AND site_id=:id";
            case FLOOR -> "SELECT site_id, floor_id FROM wp_floors WHERE tenant_id=:tenant AND floor_id=:id";
            case ACCESS_RULE -> "SELECT site_id, floor_id FROM wp_site_access_rules WHERE tenant_id=:tenant AND access_rule_id=:id";
            case POLICY_OVERRIDE -> """
                    SELECT COALESCE(s.site_id,f.site_id,zf.site_id,rf.site_id) AS site_id,
                           COALESCE(f.floor_id,zf.floor_id,rf.floor_id) AS floor_id
                      FROM wp_policy_overrides policy
                      LEFT JOIN wp_sites s ON policy.scope_type='SITE' AND s.tenant_id=policy.tenant_id AND s.site_id=policy.site_id
                      LEFT JOIN wp_floors f ON policy.scope_type='FLOOR' AND f.tenant_id=policy.tenant_id AND f.floor_id=policy.floor_id
                      LEFT JOIN wp_zones z ON policy.scope_type='ZONE' AND z.tenant_id=policy.tenant_id AND z.zone_id=policy.zone_id
                      LEFT JOIN wp_floors zf ON zf.tenant_id=z.tenant_id AND zf.floor_id=z.floor_id
                      LEFT JOIN wp_resources r ON policy.scope_type='RESOURCE' AND r.tenant_id=policy.tenant_id AND r.resource_id=policy.resource_id
                      LEFT JOIN wp_floors rf ON rf.tenant_id=r.tenant_id AND rf.floor_id=r.floor_id
                     WHERE policy.tenant_id=:tenant AND policy.policy_override_id=:id
                    """;
            default -> {
                String relation = switch (type) {
                    case RESOURCE -> "wp_resources"; case ZONE -> "wp_zones"; case SECTION -> "wp_sections";
                    case FLOOR_PLAN_REVISION -> "wp_floor_plan_revisions";
                    case BOOKING -> "wp_bookings"; case CLOSURE -> "wp_experience_facility_closures";
                    case FACILITY_REQUEST -> "wp_experience_facility_requests";
                    default -> throw new IllegalArgumentException("Unsupported canonical target");
                };
                String key = switch (type) {
                    case RESOURCE -> "resource_id"; case ZONE -> "zone_id"; case SECTION -> "section_id";
                    case FLOOR_PLAN_REVISION -> "floor_plan_revision_id"; case BOOKING -> "booking_id";
                    case CLOSURE -> "closure_id"; case FACILITY_REQUEST -> "request_id";
                    default -> throw new IllegalArgumentException("Unsupported canonical target");
                };
                boolean resourceChild = type == WorkplaceDelegatedAdminTargetType.BOOKING || type == WorkplaceDelegatedAdminTargetType.CLOSURE || type == WorkplaceDelegatedAdminTargetType.FACILITY_REQUEST;
                yield "SELECT f.site_id, f.floor_id FROM " + relation + " x "
                        + (resourceChild ? "JOIN wp_resources r ON r.tenant_id=x.tenant_id AND r.resource_id=x.resource_id " : "")
                        + "JOIN wp_floors f ON f.tenant_id=x.tenant_id AND f.floor_id=" + (resourceChild ? "r.floor_id " : "x.floor_id ")
                        + "WHERE x.tenant_id=:tenant AND x." + key + "=:id";
            }
        };
        return jdbc.query(sql, new MapSqlParameterSource().addValue("tenant", tenantId).addValue("id", id),
                (result, row) -> new CanonicalTarget(result.getObject("site_id", UUID.class), result.getObject("floor_id", UUID.class),
                        result.getObject("floor_id") == null)).stream().filter(target -> target.siteId() != null).findFirst();
    }

    Optional<UUID> resolveSite(Long tenantId, WorkplaceDelegatedAdminTargetType type, UUID id) {
        return resolveTarget(tenantId, type, id).map(CanonicalTarget::siteId);
    }

    private Set<UUID> floorIds(boolean restricted, Array array) throws SQLException {
        Set<UUID> ids = array == null ? Set.of() : Arrays.stream((Object[]) array.getArray())
                .map(value -> UUID.fromString(String.valueOf(value))).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (restricted == ids.isEmpty()) throw new com.dwp.core.exception.BaseException(
                com.dwp.core.common.ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "The persisted delegated floor scope is inconsistent.");
        return restricted ? ids : null;
    }

    private Set<DelegatedPermission> permissions(Array values) throws SQLException {
        if (values == null) return Set.of();
        Object raw = values.getArray();
        Object[] items = raw instanceof Object[] array ? array : new Object[0];
        EnumSet<DelegatedPermission> result = EnumSet.noneOf(DelegatedPermission.class);
        Arrays.stream(items)
                .map(String::valueOf)
                .map(DelegatedPermission::valueOf)
                .forEach(result::add);
        return Set.copyOf(result);
    }

    record DelegatedGrant(
            UUID delegationId,
            DelegateType delegateType,
            Long delegateUserId,
            UUID delegateGroupRef,
            DelegatedScopeType scopeType,
            UUID siteId,
            UUID managedGroupRef,
            Set<DelegatedPermission> permissions,
            OffsetDateTime validFrom,
            OffsetDateTime validUntil, Set<UUID> floorIds) {
        DelegatedGrant(UUID delegationId, DelegateType delegateType, Long delegateUserId, UUID delegateGroupRef,
                DelegatedScopeType scopeType, UUID siteId, UUID managedGroupRef, Set<DelegatedPermission> permissions,
                OffsetDateTime validFrom, OffsetDateTime validUntil) {
            this(delegationId, delegateType, delegateUserId, delegateGroupRef, scopeType, siteId, managedGroupRef,
                    permissions, validFrom, validUntil, null);
        }
    }
}
