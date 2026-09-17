package com.dwp.services.approval.routingdirectory;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.*;

@Repository
public class RoutingDirectoryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;

    public RoutingDirectoryRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    <T> T prior(
            Context context,
            String operation,
            UUID targetId,
            Object input,
            Class<T> type) {
        requireActiveTenant(context);
        String digest = canonical.fingerprint(input);
        MapSqlParameterSource parameters = base(context)
                .addValue("operation", operation)
                .addValue("targetId", targetId)
                .addValue("digest", digest);
        lock(context, operation);
        int inserted = jdbc.update("""
                INSERT INTO apr_routing_directory_commands(
                    tenant_id,resource_set_key,actor_user_id,operation,
                    idempotency_key,target_id,command_sha256)
                VALUES(:tenant,:scope,:actor,:operation,:key,:targetId,:digest)
                ON CONFLICT DO NOTHING
                """, parameters);
        Command command = jdbc.query("""
                SELECT target_id,command_sha256,status,result_type,result_payload::text
                  FROM apr_routing_directory_commands
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key
                 FOR UPDATE
                """, parameters, result -> {
            if (!result.next()) {
                throw RoutingDirectoryRejected.unavailable(
                        "Routing command receipt is unavailable.");
            }
            return new Command(result.getObject(1, UUID.class), result.getString(2),
                    result.getString(3), result.getString(4), result.getString(5));
        });
        if (!java.util.Objects.equals(targetId, command.targetId())
                || !digest.equals(command.digest())) {
            throw RoutingDirectoryRejected.conflict(
                    "The idempotency key is bound to a different routing command.");
        }
        if (inserted == 1) return null;
        if (!"SUCCEEDED".equals(command.status()) || command.payload() == null
                || !type.getName().equals(command.resultType())) {
            throw RoutingDirectoryRejected.unavailable(
                    "The routing command outcome is unknown.");
        }
        return canonical.read(command.payload(), type);
    }

    void complete(
            Context context,
            String operation,
            Object input,
            Object result) {
        int updated = jdbc.update("""
                UPDATE apr_routing_directory_commands
                   SET status='SUCCEEDED',result_type=:type,
                       result_payload=CAST(:result AS jsonb),completed_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND actor_user_id=:actor AND operation=:operation
                   AND idempotency_key=:key AND command_sha256=:digest
                   AND status='UNKNOWN'
                """, base(context).addValue("operation", operation)
                .addValue("digest", canonical.fingerprint(input))
                .addValue("type", result.getClass().getName())
                .addValue("result", canonical.json(result)));
        if (updated != 1) {
            throw RoutingDirectoryRejected.conflict(
                    "Routing command completion lost its exact command fence.");
        }
    }

    ResolverView saveResolver(Context context, ResolverDraft input) {
        validateResolver(input);
        MapSqlParameterSource parameters = base(context)
                .addValue("id", input.resolverId())
                .addValue("keyValue", input.resolverKey().trim().toUpperCase())
                .addValue("name", input.displayName().trim())
                .addValue("kind", input.resolverKind().name())
                .addValue("definition", canonical.json(input.definition()))
                .addValue("lifecycle", input.lifecycle().name())
                .addValue("effectiveFrom", Timestamp.from(input.effectiveFrom()))
                .addValue("effectiveTo", timestamp(input.effectiveTo()))
                .addValue("expected", input.expectedVersion());
        try {
            int changed;
            if (input.expectedVersion() == 0) {
                changed = jdbc.update("""
                        INSERT INTO apr_routing_resolvers(
                            tenant_id,resource_set_key,resolver_id,resolver_key,display_name,
                            resolver_kind,definition,lifecycle_state,effective_from,effective_to,
                            created_by,updated_by)
                        VALUES(:tenant,:scope,:id,:keyValue,:name,:kind,CAST(:definition AS jsonb),
                            :lifecycle,:effectiveFrom,:effectiveTo,:actor,:actor)
                        """, parameters);
            } else {
                changed = jdbc.update("""
                        UPDATE apr_routing_resolvers
                           SET resolver_key=:keyValue,display_name=:name,resolver_kind=:kind,
                               definition=CAST(:definition AS jsonb),lifecycle_state=:lifecycle,
                               effective_from=:effectiveFrom,effective_to=:effectiveTo,
                               source_state='NOT_VERIFIED',source_revision=NULL,
                               source_evidence_sha256=NULL,source_observed_at=NULL,
                               source_valid_until=NULL,version=version+1,
                               updated_by=:actor,updated_at=clock_timestamp()
                         WHERE tenant_id=:tenant AND resource_set_key=:scope
                           AND resolver_id=:id AND version=:expected
                           AND lifecycle_state<>'RETIRED'
                        """, parameters);
            }
            if (changed != 1) throw RoutingDirectoryRejected.conflict(
                    "Approver resolver version changed or cannot be edited.");
        } catch (DuplicateKeyException exception) {
            throw RoutingDirectoryRejected.conflict(
                    "Approver resolver key already exists in this management scope.");
        }
        return requireResolver(context, input.resolverId(), true);
    }

    ResolverView observe(Context context, UUID resolverId, SourceObservation input) {
        if (input == null || input.observationId() == null
                || input.state() == null || input.state() == SourceState.NOT_VERIFIED
                || !digest(input.evidenceSha256()) || blank(input.sourceRevision())
                || input.observedAt() == null || input.validUntil() == null
                || !input.validUntil().isAfter(input.observedAt())
                || input.expectedVersion() < 1) {
            throw RoutingDirectoryRejected.invalid("Resolver source observation is invalid.");
        }
        MapSqlParameterSource parameters = base(context)
                .addValue("id", resolverId)
                .addValue("observation", input.observationId())
                .addValue("state", input.state().name())
                .addValue("revision", input.sourceRevision().trim())
                .addValue("evidence", input.evidenceSha256())
                .addValue("observed", Timestamp.from(input.observedAt()))
                .addValue("valid", Timestamp.from(input.validUntil()))
                .addValue("expected", input.expectedVersion());
        int updated = jdbc.update("""
                UPDATE apr_routing_resolvers
                   SET source_state=:state,source_revision=:revision,
                       source_evidence_sha256=:evidence,source_observed_at=:observed,
                       source_valid_until=:valid,version=version+1,
                       updated_by=:actor,updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND resolver_id=:id AND version=:expected
                   AND lifecycle_state<>'RETIRED'
                """, parameters);
        if (updated != 1) throw RoutingDirectoryRejected.conflict(
                "Approver resolver changed before its observation was recorded.");
        int inserted = jdbc.update("""
                INSERT INTO apr_routing_resolver_observations(
                    tenant_id,resource_set_key,resolver_id,observation_id,
                    resolver_version,source_state,source_revision,evidence_sha256,
                    observed_at,valid_until,recorded_by)
                VALUES(:tenant,:scope,:id,:observation,:expected + 1,:state,:revision,
                    :evidence,:observed,:valid,:actor)
                """, parameters);
        if (inserted != 1) throw RoutingDirectoryRejected.unavailable(
                "Resolver source evidence could not be recorded.");
        return requireResolver(context, resolverId, true);
    }

    GroupView saveGroup(Context context, GroupDraft input) {
        validateGroup(input);
        MapSqlParameterSource parameters = base(context)
                .addValue("id", input.groupId())
                .addValue("keyValue", input.groupKey().trim().toUpperCase())
                .addValue("name", input.displayName().trim())
                .addValue("description", input.description() == null ? "" : input.description().trim())
                .addValue("lifecycle", input.lifecycle().name())
                .addValue("effectiveFrom", Timestamp.from(input.effectiveFrom()))
                .addValue("effectiveTo", timestamp(input.effectiveTo()))
                .addValue("expected", input.expectedVersion());
        try {
            int changed;
            if (input.expectedVersion() == 0) {
                changed = jdbc.update("""
                        INSERT INTO apr_routing_groups(
                            tenant_id,resource_set_key,group_id,group_key,display_name,
                            description,lifecycle_state,effective_from,effective_to,
                            created_by,updated_by)
                        VALUES(:tenant,:scope,:id,:keyValue,:name,:description,:lifecycle,
                            :effectiveFrom,:effectiveTo,:actor,:actor)
                        """, parameters);
            } else {
                changed = jdbc.update("""
                        UPDATE apr_routing_groups
                           SET group_key=:keyValue,display_name=:name,description=:description,
                               lifecycle_state=:lifecycle,effective_from=:effectiveFrom,
                               effective_to=:effectiveTo,version=version+1,
                               updated_by=:actor,updated_at=clock_timestamp()
                         WHERE tenant_id=:tenant AND resource_set_key=:scope
                           AND group_id=:id AND version=:expected
                           AND lifecycle_state<>'RETIRED'
                        """, parameters);
            }
            if (changed != 1) throw RoutingDirectoryRejected.conflict(
                    "Approver group version changed or cannot be edited.");
            jdbc.update("""
                    DELETE FROM apr_routing_group_members
                     WHERE tenant_id=:tenant AND resource_set_key=:scope AND group_id=:id
                    """, parameters);
            for (MemberDraft member : input.members()) insertMember(context, input.groupId(), member);
        } catch (DuplicateKeyException exception) {
            throw RoutingDirectoryRejected.conflict(
                    "Approver group key or member conflicts in this management scope.");
        }
        return requireGroup(context, input.groupId(), true);
    }

    GroupView activate(Context context, UUID groupId, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE apr_routing_groups
                   SET lifecycle_state='ACTIVE',version=version+1,
                       updated_by=:actor,updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND group_id=:id AND version=:expected
                   AND lifecycle_state='DRAFT'
                """, base(context).addValue("id", groupId).addValue("expected", expectedVersion));
        if (updated != 1) throw RoutingDirectoryRejected.conflict(
                "Approver group changed or is not an activatable draft.");
        return requireGroup(context, groupId, true);
    }

    Usage recordUsage(Context context, Usage usage) {
        if (usage == null || usage.groupId() == null || usage.usageOwnerId() == null
                || usage.observedAt() == null || blank(usage.usageRevision())
                || !digest(usage.usageSha256())
                || usage.expectedGroupVersion() < 1
                || !List.of("WORKFLOW", "POLICY", "ESCALATION", "DELEGATION")
                .contains(usage.usageKind())) {
            throw RoutingDirectoryRejected.invalid("Approver group usage is invalid.");
        }
        GroupView group = requireGroup(context, usage.groupId(), true);
        if (group.version() != usage.expectedGroupVersion()) {
            throw RoutingDirectoryRejected.conflict(
                    "Approver group changed before its usage was recorded.");
        }
        int changed = jdbc.update("""
                INSERT INTO apr_routing_group_usages(
                    tenant_id,resource_set_key,group_id,usage_kind,usage_owner_id,
                    usage_revision,usage_sha256,active,observed_at)
                VALUES(:tenant,:scope,:groupId,:kind,:owner,:revision,:sha,:active,:observed)
                ON CONFLICT (tenant_id,resource_set_key,group_id,usage_kind,usage_owner_id)
                DO UPDATE SET usage_revision=EXCLUDED.usage_revision,
                    usage_sha256=EXCLUDED.usage_sha256,active=EXCLUDED.active,
                    observed_at=EXCLUDED.observed_at
                """, base(context).addValue("groupId", usage.groupId())
                .addValue("kind", usage.usageKind()).addValue("owner", usage.usageOwnerId())
                .addValue("revision", usage.usageRevision()).addValue("sha", usage.usageSha256())
                .addValue("active", usage.active())
                .addValue("observed", Timestamp.from(usage.observedAt())));
        if (changed != 1) throw RoutingDirectoryRejected.unavailable(
                "Approver group usage could not be recorded.");
        return usage;
    }

    RetireImpact impact(Context context, UUID groupId) {
        GroupView group = requireGroup(context, groupId, false);
        MapSqlParameterSource parameters = base(context).addValue("id", groupId);
        long usage = jdbc.queryForObject("""
                SELECT count(*) FROM apr_routing_group_usages
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND group_id=:id AND active
                """, parameters, Long.class);
        long parents = jdbc.queryForObject("""
                SELECT count(*) FROM apr_routing_group_members member
                  JOIN apr_routing_groups parent
                    ON parent.tenant_id=member.tenant_id
                   AND parent.resource_set_key=member.resource_set_key
                   AND parent.group_id=member.group_id
                 WHERE member.tenant_id=:tenant AND member.resource_set_key=:scope
                   AND member.member_kind='GROUP' AND member.nested_group_id=:id
                   AND parent.lifecycle_state<>'RETIRED'
                """, parameters, Long.class);
        return new RetireImpact(groupId, group.version(), usage, parents, usage + parents);
    }

    GroupView retire(
            Context context, UUID groupId, long expectedVersion, long acknowledgedImpact) {
        RetireImpact impact = impact(context, groupId);
        if (impact.groupVersion() != expectedVersion
                || impact.totalImpactCount() != acknowledgedImpact) {
            throw RoutingDirectoryRejected.conflict(
                    "Approver group retirement impact changed and must be reviewed again.");
        }
        if (impact.totalImpactCount() > 0) {
            throw RoutingDirectoryRejected.conflict(
                    "Approver group still has active usage or parent-group references.");
        }
        int updated = jdbc.update("""
                UPDATE apr_routing_groups
                   SET lifecycle_state='RETIRED',version=version+1,
                       updated_by=:actor,updated_at=clock_timestamp()
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND group_id=:id AND version=:expected
                   AND lifecycle_state<>'RETIRED'
                """, base(context).addValue("id", groupId).addValue("expected", expectedVersion));
        if (updated != 1) throw RoutingDirectoryRejected.conflict(
                "Approver group changed before retirement.");
        return requireGroup(context, groupId, true);
    }

    Map<UUID, GroupView> groups(Context context) {
        requireActiveTenant(context);
        Map<UUID, GroupView> result = new HashMap<>();
        List<GroupHead> heads = jdbc.query("""
                SELECT group_id,group_key,display_name,description,lifecycle_state,
                       effective_from,effective_to,version
                  FROM apr_routing_groups
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY group_id
                """, base(context), (row, number) -> new GroupHead(
                row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                row.getString(4), Lifecycle.valueOf(row.getString(5)),
                instant(row.getTimestamp(6)), instant(row.getTimestamp(7)), row.getLong(8)));
        Map<UUID, List<MemberDraft>> members = members(context, null);
        for (GroupHead head : heads) {
            result.put(head.id(), new GroupView(head.id(), head.key(), head.name(),
                    head.description(), head.lifecycle(), head.from(), head.to(), head.version(),
                    members.getOrDefault(head.id(), List.of())));
        }
        return Map.copyOf(result);
    }

    Map<UUID, ResolverView> resolvers(Context context) {
        requireActiveTenant(context);
        Map<UUID, ResolverView> result = new HashMap<>();
        jdbc.query("""
                SELECT resolver_id,resolver_key,display_name,resolver_kind,definition::text,
                       lifecycle_state,effective_from,effective_to,source_state,
                       source_revision,source_evidence_sha256,source_observed_at,
                       source_valid_until,version
                  FROM apr_routing_resolvers
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                 ORDER BY resolver_id
                """, base(context), row -> {
            ResolverView resolver = mapResolver(row);
            result.put(resolver.resolverId(), resolver);
        });
        return Map.copyOf(result);
    }

    GroupView group(Context context, UUID groupId) {
        return requireGroup(context, groupId, false);
    }

    ResolverView resolver(Context context, UUID resolverId) {
        return requireResolver(context, resolverId, false);
    }

    private ResolverView requireResolver(Context context, UUID resolverId, boolean lock) {
        List<ResolverView> result = jdbc.query("""
                SELECT resolver_id,resolver_key,display_name,resolver_kind,definition::text,
                       lifecycle_state,effective_from,effective_to,source_state,
                       source_revision,source_evidence_sha256,source_observed_at,
                       source_valid_until,version
                  FROM apr_routing_resolvers
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND resolver_id=:id
                """ + (lock ? " FOR SHARE" : ""),
                base(context).addValue("id", resolverId),
                (row, number) -> mapResolver(row));
        if (result.size() != 1) throw RoutingDirectoryRejected.unavailable(
                "Approver resolver is unavailable in the selected scope.");
        return result.getFirst();
    }

    private GroupView requireGroup(Context context, UUID groupId, boolean lock) {
        List<GroupView> result = jdbc.query("""
                SELECT group_id,group_key,display_name,description,lifecycle_state,
                       effective_from,effective_to,version
                  FROM apr_routing_groups
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND group_id=:id
                """ + (lock ? " FOR SHARE" : ""), base(context).addValue("id", groupId),
                (row, number) -> new GroupView(row.getObject(1, UUID.class), row.getString(2),
                        row.getString(3), row.getString(4), Lifecycle.valueOf(row.getString(5)),
                        instant(row.getTimestamp(6)), instant(row.getTimestamp(7)), row.getLong(8),
                        members(context, groupId).getOrDefault(groupId, List.of())));
        if (result.size() != 1) throw RoutingDirectoryRejected.unavailable(
                "Approver group is unavailable in the selected scope.");
        return result.getFirst();
    }

    private Map<UUID, List<MemberDraft>> members(Context context, UUID selectedGroup) {
        String selected = selectedGroup == null ? "" : " AND group_id=:selected";
        MapSqlParameterSource parameters = base(context).addValue("selected", selectedGroup);
        Map<UUID, List<MemberDraft>> result = new HashMap<>();
        jdbc.query("""
                SELECT group_id,member_id,member_kind,member_user_id,
                       member_person_public_id,nested_group_id,resolver_id,priority,required
                  FROM apr_routing_group_members
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                """ + selected + " ORDER BY group_id,priority,member_id", parameters, row -> {
            UUID groupId = row.getObject(1, UUID.class);
            result.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(new MemberDraft(
                    row.getObject(2, UUID.class), MemberKind.valueOf(row.getString(3)),
                    row.getObject(4, Long.class), row.getObject(5, UUID.class),
                    row.getObject(6, UUID.class), row.getObject(7, UUID.class),
                    row.getInt(8), row.getBoolean(9)));
        });
        return result;
    }

    private void insertMember(Context context, UUID groupId, MemberDraft member) {
        validateMember(member);
        jdbc.update("""
                INSERT INTO apr_routing_group_members(
                    tenant_id,resource_set_key,group_id,member_id,member_kind,
                    member_user_id,member_person_public_id,nested_group_id,resolver_id,
                    priority,required)
                VALUES(:tenant,:scope,:groupId,:memberId,:kind,:userId,:personId,
                    :nestedGroupId,:resolverId,:priority,:required)
                """, base(context).addValue("groupId", groupId)
                .addValue("memberId", member.memberId()).addValue("kind", member.kind().name())
                .addValue("userId", member.userId()).addValue("personId", member.personPublicId())
                .addValue("nestedGroupId", member.nestedGroupId())
                .addValue("resolverId", member.resolverId())
                .addValue("priority", member.priority()).addValue("required", member.required()));
    }

    private ResolverView mapResolver(java.sql.ResultSet row) throws java.sql.SQLException {
        @SuppressWarnings("unchecked")
        Map<String, Object> definition = canonical.read(row.getString(5), Map.class);
        return new ResolverView(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                ResolverKind.valueOf(row.getString(4)), Map.copyOf(definition),
                Lifecycle.valueOf(row.getString(6)), instant(row.getTimestamp(7)),
                instant(row.getTimestamp(8)), SourceState.valueOf(row.getString(9)),
                row.getString(10), row.getString(11), instant(row.getTimestamp(12)),
                instant(row.getTimestamp(13)), row.getLong(14));
    }

    private void validateResolver(ResolverDraft input) {
        if (input == null || input.resolverId() == null || input.resolverKind() == null
                || input.lifecycle() == null || blank(input.resolverKey())
                || !input.resolverKey().trim().toUpperCase().matches("[A-Z][A-Z0-9_.-]{2,99}")
                || blank(input.displayName()) || input.displayName().trim().length() > 200
                || input.definition() == null || input.definition().isEmpty()
                || input.effectiveFrom() == null
                || input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())
                || input.expectedVersion() < 0) {
            throw RoutingDirectoryRejected.invalid("Approver resolver definition is invalid.");
        }
    }

    private void validateGroup(GroupDraft input) {
        if (input == null || input.groupId() == null || input.lifecycle() == null
                || blank(input.groupKey())
                || !input.groupKey().trim().toUpperCase().matches("[A-Z][A-Z0-9_.-]{2,99}")
                || blank(input.displayName()) || input.displayName().trim().length() > 200
                || input.description() != null && input.description().trim().length() > 1000
                || input.effectiveFrom() == null
                || input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom())
                || input.members() == null || input.members().isEmpty() || input.members().size() > 200
                || input.expectedVersion() < 0) {
            throw RoutingDirectoryRejected.invalid("Approver group definition is invalid.");
        }
        if (input.members().stream().map(MemberDraft::memberId).distinct().count()
                != input.members().size()) {
            throw RoutingDirectoryRejected.invalid("Approver group member identifiers must be unique.");
        }
        input.members().forEach(this::validateMember);
    }

    private void validateMember(MemberDraft member) {
        if (member == null || member.memberId() == null || member.kind() == null
                || member.priority() < 1 || member.priority() > 1000) {
            throw RoutingDirectoryRejected.invalid("Approver group member is invalid.");
        }
        boolean valid = switch (member.kind()) {
            case SUBJECT -> member.userId() != null && member.userId() > 0
                    && member.personPublicId() != null && member.nestedGroupId() == null
                    && member.resolverId() == null;
            case GROUP -> member.userId() == null && member.personPublicId() == null
                    && member.nestedGroupId() != null && member.resolverId() == null;
            case RESOLVER -> member.userId() == null && member.personPublicId() == null
                    && member.nestedGroupId() == null && member.resolverId() != null;
        };
        if (!valid) throw RoutingDirectoryRejected.invalid("Approver group member target is invalid.");
    }

    private void requireActiveTenant(Context context) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM apr_tenants
                 WHERE tenant_id=:tenant AND lifecycle_state='ACTIVE'
                """, base(context), Integer.class);
        if (count == null || count != 1) {
            throw RoutingDirectoryRejected.forbidden("Approval tenant is not active.");
        }
    }

    private void lock(Context context, String operation) {
        jdbc.queryForObject("""
                SELECT pg_advisory_xact_lock(hashtextextended(:material,0))
                """, Map.of("material", context.tenantId() + "|" + context.resourceSetKey()
                + "|" + context.actorUserId() + "|" + operation + "|"
                + context.idempotencyKey()), Object.class);
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource().addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey())
                .addValue("actor", context.actorUserId())
                .addValue("key", context.idempotencyKey());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private record Command(
            UUID targetId, String digest, String status, String resultType, String payload) {
    }

    private record GroupHead(
            UUID id, String key, String name, String description,
            Lifecycle lifecycle, Instant from, Instant to, long version) {
    }
}
