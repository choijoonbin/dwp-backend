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

    enum SiteTargetType {
        SITE,
        FLOOR,
        RESOURCE,
        ZONE,
        SECTION,
        ACCESS_RULE,
        POLICY_OVERRIDE,
        FLOOR_PLAN_REVISION,
        BOOKING
    }

    private final NamedParameterJdbcTemplate jdbc;

    WorkplaceDelegatedAdminScopeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<DelegatedGrant> candidateGrants(
            Long tenantId,
            Long userId,
            Set<UUID> verifiedGroupRefs) {
        String groupPredicate = verifiedGroupRefs.isEmpty()
                ? ""
                : " OR (delegate_type = 'GROUP_REF' AND delegate_group_ref IN (:groupRefs))";
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("userId", userId);
        if (!verifiedGroupRefs.isEmpty()) {
            parameters.addValue("groupRefs", verifiedGroupRefs);
        }
        return jdbc.query("""
                SELECT delegation_id, delegate_type, delegate_user_id, delegate_group_ref,
                       scope_type, site_id, managed_group_ref, permission_codes,
                       valid_from, valid_until
                  FROM wp_delegated_admin_scopes
                 WHERE tenant_id = :tenantId
                   AND lifecycle_state = 'ACTIVE'
                   AND ((delegate_type = 'USER' AND delegate_user_id = :userId)
                """ + groupPredicate + ")", parameters, (result, ignored) -> new DelegatedGrant(
                result.getObject("delegation_id", UUID.class),
                DelegateType.valueOf(result.getString("delegate_type")),
                result.getObject("delegate_user_id", Long.class),
                result.getObject("delegate_group_ref", UUID.class),
                DelegatedScopeType.valueOf(result.getString("scope_type")),
                result.getObject("site_id", UUID.class),
                result.getObject("managed_group_ref", UUID.class),
                permissions(result.getArray("permission_codes")),
                result.getObject("valid_from", OffsetDateTime.class),
                result.getObject("valid_until", OffsetDateTime.class)));
    }

    Optional<UUID> resolveSite(
            Long tenantId,
            SiteTargetType targetType,
            UUID targetId) {
        String query = switch (targetType) {
            case SITE -> """
                    SELECT site_id
                      FROM wp_sites
                     WHERE tenant_id = :tenantId AND site_id = :targetId
                    """;
            case FLOOR -> """
                    SELECT site_id
                      FROM wp_floors
                     WHERE tenant_id = :tenantId AND floor_id = :targetId
                    """;
            case RESOURCE -> """
                    SELECT floor.site_id
                      FROM wp_resources resource
                      JOIN wp_floors floor
                        ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                     WHERE resource.tenant_id = :tenantId
                       AND resource.resource_id = :targetId
                    """;
            case ZONE -> """
                    SELECT floor.site_id
                      FROM wp_zones zone
                      JOIN wp_floors floor
                        ON floor.tenant_id = zone.tenant_id
                       AND floor.floor_id = zone.floor_id
                     WHERE zone.tenant_id = :tenantId AND zone.zone_id = :targetId
                    """;
            case SECTION -> """
                    SELECT floor.site_id
                      FROM wp_sections section
                      JOIN wp_floors floor
                        ON floor.tenant_id = section.tenant_id
                       AND floor.floor_id = section.floor_id
                     WHERE section.tenant_id = :tenantId
                       AND section.section_id = :targetId
                    """;
            case ACCESS_RULE -> """
                    SELECT site_id
                      FROM wp_site_access_rules
                     WHERE tenant_id = :tenantId AND access_rule_id = :targetId
                    """;
            case FLOOR_PLAN_REVISION -> """
                    SELECT floor.site_id
                      FROM wp_floor_plan_revisions revision
                      JOIN wp_floors floor
                        ON floor.tenant_id = revision.tenant_id
                       AND floor.floor_id = revision.floor_id
                     WHERE revision.tenant_id = :tenantId
                       AND revision.floor_plan_revision_id = :targetId
                    """;
            case BOOKING -> """
                    SELECT floor.site_id
                      FROM wp_bookings booking
                      JOIN wp_resources resource
                        ON resource.tenant_id = booking.tenant_id
                       AND resource.resource_id = booking.resource_id
                      JOIN wp_floors floor
                        ON floor.tenant_id = resource.tenant_id
                       AND floor.floor_id = resource.floor_id
                     WHERE booking.tenant_id = :tenantId
                       AND booking.booking_id = :targetId
                    """;
            case POLICY_OVERRIDE -> """
                    SELECT CASE policy.scope_type
                               WHEN 'SITE' THEN policy_site.site_id
                               WHEN 'FLOOR' THEN floor.site_id
                               WHEN 'ZONE' THEN zone_floor.site_id
                               WHEN 'RESOURCE' THEN resource_floor.site_id
                               ELSE NULL
                           END AS site_id
                      FROM wp_policy_overrides policy
                      LEFT JOIN wp_sites policy_site
                        ON policy.scope_type = 'SITE'
                       AND policy_site.tenant_id = policy.tenant_id
                       AND policy_site.site_id = policy.site_id
                      LEFT JOIN wp_floors floor
                        ON policy.scope_type = 'FLOOR'
                       AND floor.tenant_id = policy.tenant_id
                       AND floor.floor_id = policy.floor_id
                      LEFT JOIN wp_zones zone
                        ON policy.scope_type = 'ZONE'
                       AND zone.tenant_id = policy.tenant_id
                       AND zone.zone_id = policy.zone_id
                      LEFT JOIN wp_floors zone_floor
                        ON zone_floor.tenant_id = zone.tenant_id
                       AND zone_floor.floor_id = zone.floor_id
                      LEFT JOIN wp_resources resource
                        ON policy.scope_type = 'RESOURCE'
                       AND resource.tenant_id = policy.tenant_id
                       AND resource.resource_id = policy.resource_id
                      LEFT JOIN wp_floors resource_floor
                        ON resource_floor.tenant_id = resource.tenant_id
                       AND resource_floor.floor_id = resource.floor_id
                     WHERE policy.tenant_id = :tenantId
                       AND policy.policy_override_id = :targetId
                    """;
        };
        return jdbc.query(query, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("targetId", targetId),
                (result, ignored) -> result.getObject("site_id", UUID.class))
                .stream().filter(value -> value != null).findFirst();
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
            OffsetDateTime validUntil) {
    }
}
