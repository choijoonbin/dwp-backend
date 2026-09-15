package com.dwp.services.auth.systemslaauthority;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class SystemSlaSubjectRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SystemSlaJson json;
    public SystemSlaSubjectRepository(NamedParameterJdbcTemplate jdbc, SystemSlaJson json) { this.jdbc = jdbc; this.json = json; }
    public Snapshot snapshot(SystemSlaBindings binding) {
        var users = new TreeSet<Long>(); binding.audience().forEach(seat -> users.add(seat.get("userId").longValue()));
        var params = new MapSqlParameterSource("tenant", binding.tenantId()).addValue("users", new SqlArrayValue("bigint", users.toArray()));
        var subjects = new TreeMap<Long, Subject>(); users.forEach(user -> subjects.put(user, new Subject()));
        var rows = jdbc.query("""
                SELECT subject.user_id,jsonb_build_object('person_public_id',subject.person_public_id,
                       'status',subject.status,'identity_plane',subject.identity_plane,'updated_at',subject.updated_at,
                       'tenant_updated_at',tenant.updated_at)::text AS subject,tenant.status AS tenant_status
                  FROM com_users subject JOIN com_tenants tenant ON tenant.tenant_id=subject.tenant_id
                 WHERE subject.tenant_id=:tenant AND subject.user_id=ANY(:users) ORDER BY subject.user_id
                """, params, (row, index) -> {
            var subject = json.parse(row.getString("subject").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Never include password hashes or broad user rows in a signed vector.
            return Map.<String, Object>of("userId", row.getLong("user_id"), "personPublicId", subject.get("person_public_id"),
                    "status", subject.get("status"), "identityPlane", subject.get("identity_plane"),
                    "updatedAt", subject.get("updated_at"), "tenantUpdatedAt", subject.get("tenant_updated_at"), "tenantStatus", row.getString("tenant_status"));
        });
        rows.forEach(row -> subjects.get((Long) row.get("userId")).principal = json.tree(row));
        int[] sources = {0}, associations = {0};
        jdbc.query(SystemSlaSourceSql.MEMBERSHIPS, params, row -> {
            bounded(++sources[0], ++associations[0]); var subject = subjects.get(row.getLong("user_id"));
            long role = row.getLong("role_id"); subject.roleIds.add(role); subject.roleCodes.add(row.getString("code"));
            subject.sources.add(json.tree(Map.of("membership", json.parse(row.getString("source").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "roleId", role, "code", row.getString("code"), "version", row.getLong("version"), "updatedAt", row.getTimestamp("updated_at").toInstant())));
            subject.expiry(row.getObject("expiry", OffsetDateTime.class));
        });
        var roles = new TreeSet<Long>(); subjects.values().forEach(subject -> roles.addAll(subject.roleIds));
        if (!roles.isEmpty()) jdbc.query(SystemSlaSourceSql.ROLE_PERMISSIONS,
                new MapSqlParameterSource("tenant", binding.tenantId()).addValue("roles", new SqlArrayValue("bigint", roles.toArray())), row -> {
            bounded(++sources[0], associations[0]); long role = row.getLong("role_id");
            for (var subject : subjects.values()) if (subject.roleIds.contains(role)) {
                bounded(sources[0], ++associations[0]); subject.permission(row.getString("permission_key"), row.getString("effect"));
                subject.sources.add(json.parse(row.getString("source").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        });
        jdbc.query(SystemSlaSourceSql.PRINCIPAL_PERMISSIONS, params, row -> {
            bounded(++sources[0], ++associations[0]); var subject = subjects.get(row.getLong("user_id"));
            subject.permission(row.getString("permission_key"), row.getString("effect"));
            subject.sources.add(json.parse(row.getString("source").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            subject.expiry(row.getObject("expiry", OffsetDateTime.class));
        });
        params.addValue("resourceSet", binding.source().get("request").get("resourceSetKey").textValue());
        var resources = jdbc.query("""
                SELECT to_jsonb(resource_set)::text AS resource_set,to_jsonb(member)::text AS member,to_jsonb(resource)::text AS resource
                  FROM com_admin_resource_sets resource_set JOIN com_admin_resource_set_members member
                    ON member.tenant_id=resource_set.tenant_id AND member.resource_set_id=resource_set.resource_set_id
                  JOIN com_resources resource ON resource.tenant_id=member.tenant_id AND resource.type=member.resource_type AND resource.key=member.resource_key
                 WHERE resource_set.tenant_id=:tenant AND resource_set.resource_set_key=:resourceSet
                   AND resource_set.lifecycle_state='ACTIVE' AND member.lifecycle_state='ACTIVE' AND resource.enabled
                   AND member.resource_key='APP.APPROVALS' AND member.resource_type='APP'
                """, params, (row, index) -> json.tree(Map.of("set", json.parse(row.getString("resource_set").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "member", json.parse(row.getString("member").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "resource", json.parse(row.getString("resource").getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        var material = subjects.entrySet().stream().map(entry -> Map.of("userId", entry.getKey(), "principal", entry.getValue().principal,
                "sources", entry.getValue().sources.stream().map(json::digest).sorted().toList(), "roles", entry.getValue().roleCodes)).toList();
        return new Snapshot(Map.copyOf(subjects), resources.size() == 1, json.digest(Map.of("subjects", material, "resources", resources)));
    }
    private static void bounded(int sources, int associations) { if (sources > 50000 || associations > 100000) throw SystemSlaJson.unavailable(); }
    public static final class Subject {
        private JsonNode principal = com.fasterxml.jackson.databind.node.NullNode.instance;
        private final Set<Long> roleIds = new TreeSet<>();
        private final Set<String> roleCodes = new TreeSet<>();
        private final Map<String, Set<String>> permissions = new TreeMap<>();
        private final List<JsonNode> sources = new java.util.ArrayList<>();
        private Instant expiresAt;
        private void permission(String key, String effect) { permissions.computeIfAbsent(key, ignored -> new TreeSet<>()).add(effect); }
        private void expiry(OffsetDateTime expiry) { if (expiry != null && (expiresAt == null || expiry.toInstant().isBefore(expiresAt))) expiresAt = expiry.toInstant(); }
        public JsonNode principal() { return principal.deepCopy(); }
        public boolean role(String code) { return roleCodes.contains(code) && roleCodes.stream().noneMatch(role -> role.startsWith("PROVIDER_")); }
        public boolean permission(String code) { var effects = permissions.getOrDefault(code, Set.of()); return effects.contains("ALLOW") && !effects.contains("DENY"); }
        public Instant expiresAt() { return expiresAt; }
    }
    public record Snapshot(Map<Long, Subject> subjects, boolean resourceActive, String sha256) { }
}
