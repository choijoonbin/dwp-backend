package com.dwp.services.approval.policyautomation;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.Context;
import static com.dwp.services.approval.policyautomation.PolicyGovernanceModels.*;

@Repository
class PolicyGovernanceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;

    PolicyGovernanceRepository(
            NamedParameterJdbcTemplate jdbc,
            ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    Head requireHead(Context context, UUID policyId, boolean lock) {
        List<Head> values = jdbc.query("""
                SELECT policy_id,version,draft_revision_id,published_revision_id,
                       lifecycle_state,created_by,updated_by
                  FROM apr_policy_automation_heads
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND policy_id=:policy
                """ + (lock ? " FOR UPDATE" : ""), params(context, policyId),
                (result, row) -> new Head(
                        result.getObject("policy_id", UUID.class), result.getLong("version"),
                        result.getObject("draft_revision_id", UUID.class),
                        result.getObject("published_revision_id", UUID.class),
                        result.getString("lifecycle_state"), result.getLong("created_by"),
                        result.getLong("updated_by")));
        if (values.size() != 1) {
            throw PolicyAutomationRejected.invalid("Policy was not found in this scope.");
        }
        return values.getFirst();
    }

    Revision requireRevision(Context context, UUID policyId, UUID revisionId) {
        List<Revision> values = jdbc.query("""
                SELECT revision_id,revision_number,calendar_id,reminders::text,
                       escalations::text,channel_ids::text,effective_from,effective_to,
                       definition_sha256,maker_user_id,maker_person_public_id,
                       editor_user_id,editor_person_public_id
                  FROM apr_policy_automation_revisions
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND policy_id=:policy AND revision_id=:revision
                """, params(context, policyId).addValue("revision", revisionId),
                (result, row) -> revision(result));
        if (values.size() != 1) {
            throw PolicyAutomationRejected.invalid("Policy revision was not found in this scope.");
        }
        return values.getFirst();
    }

    SimulationReceipt insertSimulation(
            Context context,
            UUID policyId,
            SimulationCommand command,
            SimulationOutcome outcome,
            List<String> blockers,
            List<String> warnings,
            Map<String, Object> result,
            String definitionSha256,
            Instant now) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("blockers", blockers);
        stored.put("warnings", warnings);
        stored.put("result", result);
        int inserted = jdbc.update("""
                INSERT INTO apr_policy_automation_simulations(
                    simulation_id,tenant_id,resource_set_key,policy_id,revision_id,
                    policy_version,input_payload,result_payload,outcome,
                    definition_sha256,requested_by,created_at)
                SELECT :simulation,:tenant,:scope,:policy,:revision,:version,
                       CAST(:input AS jsonb),CAST(:result AS jsonb),:outcome,
                       :definition,:actor,:now
                 WHERE EXISTS (
                    SELECT 1 FROM apr_policy_automation_heads
                     WHERE tenant_id=:tenant AND resource_set_key=:scope
                       AND policy_id=:policy AND version=:version)
                """, params(context, policyId)
                .addValue("simulation", command.simulationId())
                .addValue("revision", command.revisionId())
                .addValue("version", command.expectedVersion())
                .addValue("input", canonical.json(command.scenario()))
                .addValue("result", canonical.json(stored))
                .addValue("outcome", outcome.name())
                .addValue("definition", definitionSha256)
                .addValue("now", Timestamp.from(now)));
        if (inserted != 1) {
            throw PolicyAutomationRejected.conflict("Policy changed before simulation was recorded.");
        }
        return new SimulationReceipt(command.simulationId(), policyId,
                command.revisionId(), command.expectedVersion(), outcome,
                List.copyOf(blockers), List.copyOf(warnings), Map.copyOf(result),
                definitionSha256, now);
    }

    ReviewReceipt review(
            Context context,
            UUID policyId,
            ReviewCommand command,
            Revision revision,
            Instant now) {
        if (context.actorPersonPublicId().equals(revision.makerPersonPublicId())
                || context.actorPersonPublicId().equals(revision.editorPersonPublicId())) {
            throw PolicyAutomationRejected.forbidden(
                    "Policy review requires an independent checker.");
        }
        MapSqlParameterSource values = params(context, policyId)
                .addValue("review", command.reviewId())
                .addValue("revision", command.revisionId())
                .addValue("expected", command.expectedVersion())
                .addValue("disposition", command.disposition().name())
                .addValue("comment", command.comment().strip())
                .addValue("evidence", command.evidenceSha256())
                .addValue("maker", revision.makerUserId())
                .addValue("makerPerson", revision.makerPersonPublicId())
                .addValue("editor", revision.editorUserId())
                .addValue("editorPerson", revision.editorPersonPublicId())
                .addValue("checkerPerson", context.actorPersonPublicId())
                .addValue("now", Timestamp.from(now));
        try {
            int updated = jdbc.update("""
                    UPDATE apr_policy_automation_heads
                       SET version=version+1,updated_by=:actor,updated_at=:now
                     WHERE tenant_id=:tenant AND resource_set_key=:scope
                       AND policy_id=:policy AND version=:expected
                       AND draft_revision_id=:revision AND lifecycle_state<>'RETIRED'
                       AND NOT EXISTS (
                           SELECT 1 FROM apr_policy_automation_freezes policy_freeze
                            WHERE policy_freeze.tenant_id=:tenant
                              AND policy_freeze.resource_set_key=:scope
                              AND policy_freeze.policy_id=:policy AND policy_freeze.active)
                    """, values);
            if (updated != 1) {
                throw PolicyAutomationRejected.conflict(
                        "Policy changed, is frozen, or the reviewed revision is no longer current.");
            }
            jdbc.update("""
                    INSERT INTO apr_policy_automation_reviews(
                        review_id,tenant_id,resource_set_key,policy_id,revision_id,
                        reviewed_policy_version,disposition,review_comment,
                        review_evidence_sha256,maker_user_id,maker_person_public_id,
                        editor_user_id,editor_person_public_id,checker_user_id,
                        checker_person_public_id,created_at)
                    VALUES(:review,:tenant,:scope,:policy,:revision,:expected,
                        :disposition,:comment,:evidence,:maker,:makerPerson,
                        :editor,:editorPerson,:actor,:checkerPerson,:now)
                    """, values);
        } catch (DuplicateKeyException exception) {
            throw PolicyAutomationRejected.conflict(
                    "This policy revision already has an approved review or review identity.");
        }
        return new ReviewReceipt(command.reviewId(), policyId, command.revisionId(),
                command.expectedVersion(), command.expectedVersion() + 1,
                command.disposition(), command.comment().strip(), command.evidenceSha256(),
                context.actorUserId(), context.actorPersonPublicId(), now);
    }

    FreezeState setFreeze(
            Context context,
            UUID policyId,
            FreezeCommand command,
            Instant now) {
        MapSqlParameterSource values = params(context, policyId)
                .addValue("active", command.active())
                .addValue("reason", command.reason().strip())
                .addValue("expected", command.expectedVersion())
                .addValue("event", UUID.randomUUID())
                .addValue("now", Timestamp.from(now));
        int updated = jdbc.update("""
                UPDATE apr_policy_automation_heads
                   SET version=version+1,updated_by=:actor,updated_at=:now
                 WHERE tenant_id=:tenant AND resource_set_key=:scope
                   AND policy_id=:policy AND version=:expected
                   AND lifecycle_state<>'RETIRED'
                """, values);
        if (updated != 1) {
            throw PolicyAutomationRejected.conflict("Policy changed before freeze was applied.");
        }
        jdbc.update("""
                INSERT INTO apr_policy_automation_freezes(
                    tenant_id,resource_set_key,policy_id,active,reason,
                    changed_by,changed_at,version)
                VALUES(:tenant,:scope,:policy,:active,:reason,:actor,:now,1)
                ON CONFLICT (tenant_id,resource_set_key,policy_id) DO UPDATE
                   SET active=EXCLUDED.active,reason=EXCLUDED.reason,
                       changed_by=EXCLUDED.changed_by,changed_at=EXCLUDED.changed_at,
                       version=apr_policy_automation_freezes.version+1
                """, values);
        FreezeState state = freeze(context, policyId, command.expectedVersion() + 1);
        jdbc.update("""
                INSERT INTO apr_policy_automation_freeze_journal(
                    freeze_event_id,tenant_id,resource_set_key,policy_id,active,
                    policy_version,freeze_version,reason,actor_user_id,occurred_at)
                VALUES(:event,:tenant,:scope,:policy,:active,:policyVersion,
                       :freezeVersion,:reason,:actor,:now)
                """, values.addValue("policyVersion", state.policyVersion())
                .addValue("freezeVersion", state.version()));
        return state;
    }

    FreezeState freeze(Context context, UUID policyId, long policyVersion) {
        List<FreezeState> values = jdbc.query("""
                SELECT active,reason,changed_by,changed_at,version
                  FROM apr_policy_automation_freezes
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND policy_id=:policy
                """, params(context, policyId), (result, row) -> new FreezeState(
                policyId, result.getBoolean("active"), result.getString("reason"),
                result.getLong("changed_by"), result.getTimestamp("changed_at").toInstant(),
                result.getLong("version"), policyVersion));
        return values.isEmpty() ? new FreezeState(policyId, false, "NOT_FROZEN", 0,
                null, 0, policyVersion) : values.getFirst();
    }

    PolicyExport export(
            Context context,
            UUID policyId,
            ExportCommand command,
            Revision revision,
            Map<String, Object> manifest,
            Instant now) {
        String digest = canonical.fingerprint(manifest);
        int inserted = jdbc.update("""
                INSERT INTO apr_policy_automation_exports(
                    export_id,tenant_id,resource_set_key,policy_id,revision_id,
                    policy_version,manifest_payload,manifest_sha256,requested_by,created_at)
                SELECT :export,:tenant,:scope,:policy,:revision,:version,
                       CAST(:manifest AS jsonb),:digest,:actor,:now
                 WHERE EXISTS (
                    SELECT 1 FROM apr_policy_automation_heads
                     WHERE tenant_id=:tenant AND resource_set_key=:scope
                       AND policy_id=:policy AND version=:version)
                """, params(context, policyId)
                .addValue("export", command.exportId())
                .addValue("revision", revision.revisionId())
                .addValue("version", command.expectedVersion())
                .addValue("manifest", canonical.json(manifest))
                .addValue("digest", digest)
                .addValue("now", Timestamp.from(now)));
        if (inserted != 1) {
            throw PolicyAutomationRejected.conflict("Policy changed before export was sealed.");
        }
        return new PolicyExport(command.exportId(), policyId, revision.revisionId(),
                command.expectedVersion(), Map.copyOf(manifest), digest,
                context.actorUserId(), now);
    }

    List<ReviewReceipt> reviews(Context context, UUID policyId) {
        return jdbc.query("""
                SELECT review_id,revision_id,reviewed_policy_version,disposition,
                       review_comment,review_evidence_sha256,checker_user_id,
                       checker_person_public_id,created_at
                  FROM apr_policy_automation_reviews
                 WHERE tenant_id=:tenant AND resource_set_key=:scope AND policy_id=:policy
                 ORDER BY created_at DESC,review_id DESC
                """, params(context, policyId), (result, row) -> new ReviewReceipt(
                result.getObject("review_id", UUID.class), policyId,
                result.getObject("revision_id", UUID.class),
                result.getLong("reviewed_policy_version"),
                result.getLong("reviewed_policy_version") + 1,
                ReviewDisposition.valueOf(result.getString("disposition")),
                result.getString("review_comment"),
                result.getString("review_evidence_sha256"),
                result.getLong("checker_user_id"),
                result.getObject("checker_person_public_id", UUID.class),
                result.getTimestamp("created_at").toInstant()));
    }

    Map<String, Object> definition(Revision revision) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("revisionNumber", revision.revisionNumber());
        value.put("calendarId", revision.calendarId());
        value.put("reminders", revision.reminders());
        value.put("escalations", revision.escalations());
        value.put("channelIds", revision.channelIds());
        value.put("effectiveFrom", revision.effectiveFrom());
        value.put("effectiveTo", revision.effectiveTo());
        return value;
    }

    private Revision revision(ResultSet result) throws SQLException {
        return new Revision(result.getObject("revision_id", UUID.class),
                result.getLong("revision_number"),
                result.getObject("calendar_id", UUID.class),
                list(result.getString("reminders")), list(result.getString("escalations")),
                uuidList(result.getString("channel_ids")),
                result.getTimestamp("effective_from").toInstant(),
                result.getTimestamp("effective_to") == null ? null
                        : result.getTimestamp("effective_to").toInstant(),
                result.getString("definition_sha256"), result.getLong("maker_user_id"),
                result.getObject("maker_person_public_id", UUID.class),
                result.getLong("editor_user_id"),
                result.getObject("editor_person_public_id", UUID.class));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String json) {
        return new ArrayList<>((List<Map<String, Object>>) (List<?>)
                canonical.read(json, List.class));
    }

    private List<UUID> uuidList(String json) {
        List<?> values = canonical.read(json, List.class);
        return values.stream().map(Object::toString).map(UUID::fromString).toList();
    }

    private MapSqlParameterSource params(Context context, UUID policyId) {
        return new MapSqlParameterSource()
                .addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey())
                .addValue("actor", context.actorUserId())
                .addValue("policy", policyId);
    }

    record Head(
            UUID policyId,
            long version,
            UUID draftRevisionId,
            UUID publishedRevisionId,
            String lifecycle,
            long createdBy,
            long updatedBy) {
    }

    record Revision(
            UUID revisionId,
            long revisionNumber,
            UUID calendarId,
            List<Map<String, Object>> reminders,
            List<Map<String, Object>> escalations,
            List<UUID> channelIds,
            Instant effectiveFrom,
            Instant effectiveTo,
            String definitionSha256,
            long makerUserId,
            UUID makerPersonPublicId,
            long editorUserId,
            UUID editorPersonPublicId) {
    }
}
