package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProofVerifier.minimum;
import com.dwp.services.auth.repository.RoleMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class WorkflowRuntimeSourceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final RoleMemberRepository members;
    private final WorkflowRuntimeJson json;
    public WorkflowRuntimeSourceRepository(NamedParameterJdbcTemplate jdbc, RoleMemberRepository members, WorkflowRuntimeJson json) {
        this.jdbc = jdbc; this.members = members; this.json = json;
    }
    public Role currentRole(long tenant, String code) {
        if (code.startsWith("PROVIDER_")) throw denied();
        var roles = jdbc.query("SELECT role_id,version FROM com_roles WHERE tenant_id=:tenant AND code=:code AND status='ACTIVE'",
                new MapSqlParameterSource().addValue("tenant", tenant).addValue("code", code),
                (row, index) -> new Role(code, row.getLong("role_id"), row.getLong("version")));
        if (roles.size() != 1) throw denied(); return roles.getFirst();
    }
    public List<Long> completeMembers(long tenant, Role role) {
        long count = members.countEffectiveActiveUsers(tenant, role.roleId());
        if (count < 0 || count > 1000) throw unavailable();
        List<Long> result = members.enumerateEffectiveActiveUsers(tenant, role.roleId());
        if (result == null || result.size() != count || result.size() > 1000) throw unavailable();
        long previous = 0; for (Long id : result) { if (id == null || id <= previous) throw unavailable(); previous = id; }
        if (members.countEffectiveActiveUsers(tenant, role.roleId()) != count) throw changed(); return List.copyOf(result);
    }
    public Snapshot snapshot(long tenant, Collection<Long> requested) {
        var users = new TreeSet<>(requested);
        if (users.isEmpty() || users.size() > 1003 || users.first() < 1) throw denied();
        var params = new MapSqlParameterSource().addValue("tenant", tenant).addValue("users", new SqlArrayValue("bigint", users.toArray()));
        var rows = jdbc.query("""
                SELECT subject.user_id,subject.person_public_id,subject.identity_plane,subject.status,tenant.status AS tenant_status
                  FROM com_users subject JOIN com_tenants tenant ON tenant.tenant_id=subject.tenant_id
                 WHERE subject.tenant_id=:tenant AND subject.user_id=ANY(:users) ORDER BY subject.user_id
                """, params, (row, index) -> new Subject(row.getLong("user_id"), row.getObject("person_public_id", UUID.class),
                        row.getString("identity_plane"), row.getString("status"), row.getString("tenant_status")));
        if (rows.size() != users.size()) throw denied();
        var sources = new TreeMap<Long, List<JsonNode>>(); var roles = new TreeMap<Long, Set<Long>>();
        var permissions = new HashMap<Long, Map<String, Set<String>>>(); var expiry = new HashMap<Long, Instant>();
        int[] sourceRows = {0}, associations = {0};
        rows.forEach(row -> { sources.put(row.userId(), new ArrayList<>()); roles.put(row.userId(), new TreeSet<>()); permissions.put(row.userId(), new TreeMap<>()); });
        jdbc.query(WorkflowRuntimeSourceSql.MEMBERSHIPS, params, row -> {
            if (++sourceRows[0] > 50000 || ++associations[0] > 100000) throw unavailable();
            if (row.getString("code").startsWith("PROVIDER_")) throw denied();
            long user = row.getLong("user_id"), role = row.getLong("role_id"); roles.get(user).add(role);
            var source = json.read(row.getString("source"));
            var material = json.tree(Map.of("membership", source, "roleId", role, "roleCode", row.getString("code"),
                    "roleVersion", row.getLong("version"), "roleUpdated", row.getTimestamp("updated_at").toInstant().toString()));
            sources.get(user).add(material); applyExpiry(expiry, user, row.getObject("expiry", OffsetDateTime.class));
        });
        var allRoles = new TreeSet<Long>(); roles.values().forEach(allRoles::addAll);
        if (!allRoles.isEmpty()) jdbc.query(WorkflowRuntimeSourceSql.ROLE_PERMISSIONS, new MapSqlParameterSource("tenant", tenant).addValue("roles", new SqlArrayValue("bigint", allRoles.toArray())), row -> {
            if (++sourceRows[0] > 50000) throw unavailable();
            long role = row.getLong("role_id");
            for (long user : users) if (roles.get(user).contains(role)) {
                if (++associations[0] > 100000) throw unavailable();
                sources.get(user).add(json.read(row.getString("source")));
                permissions.get(user).computeIfAbsent(row.getString("permission_key"), ignored -> new TreeSet<>()).add(row.getString("effect"));
            }
        });
        jdbc.query(WorkflowRuntimeSourceSql.PRINCIPAL_PERMISSIONS, params, row -> {
            if (++sourceRows[0] > 50000 || ++associations[0] > 100000) throw unavailable();
            long user = row.getLong("user_id"); sources.get(user).add(json.read(row.getString("source")));
            permissions.get(user).computeIfAbsent(row.getString("permission_key"), ignored -> new TreeSet<>()).add(row.getString("effect"));
            applyExpiry(expiry, user, row.getObject("expiry", OffsetDateTime.class));
        });
        var subjects = new TreeMap<Long, SubjectEvidence>();
        for (var row : rows) {
            if (!row.tenantStatus().equals("ACTIVE") || !row.status().equals("ACTIVE") || !row.plane().equals("TENANT") || row.personPublicId() == null) throw denied();
            var allowed = new TreeSet<String>();
            permissions.get(row.userId()).forEach((key, effects) -> { if (effects.contains("ALLOW") && !effects.contains("DENY")) allowed.add(key); });
            var material = new TreeMap<String, Object>(); material.put("subject", row);
            material.put("sources", sources.get(row.userId()).stream().map(json::canonical).sorted().toList()); material.put("permissions", allowed);
            subjects.put(row.userId(), new SubjectEvidence(row, Set.copyOf(roles.get(row.userId())), Set.copyOf(allowed), expiry.get(row.userId()), json.tree(material)));
        }
        return new Snapshot(Map.copyOf(subjects), json.tree(subjects.entrySet().stream().map(entry -> entry.getValue().vector()).toList()));
    }
    private static void applyExpiry(Map<Long, Instant> expiry, long user, OffsetDateTime value) {
        if (value != null) expiry.put(user, expiry.containsKey(user) ? minimum(expiry.get(user), value.toInstant()) : value.toInstant());
    }
    public record Role(String roleCode, long roleId, long roleVersion) { }
    public record Subject(long userId, UUID personPublicId, String plane, String status, String tenantStatus) { }
    public record SubjectEvidence(Subject subject, Set<Long> roles, Set<String> permissions, Instant expiresAt, JsonNode vector) {
        public boolean canApprove() { return permissions.containsAll(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_TASK:VIEW", "ACTION.APPROVAL_TASK:APPROVE")); }
        public JsonNode minimal(WorkflowRuntimeJson json, long tenant, long roleId, boolean requireRole) {
            return json.tree(Map.of("tenantId", tenant, "userId", subject.userId(), "personPublicId", subject.personPublicId().toString(),
                    "identityPlane", subject.plane(), "status", subject.status(), "roleIds", roles.contains(roleId) ? List.of(roleId) : List.of(),
                    "canApprove", canApprove() && (!requireRole || roles.contains(roleId))));
        }
    }
    public record Snapshot(Map<Long, SubjectEvidence> subjects, JsonNode vector) { }
}
