package com.dwp.services.approval.routingdirectory;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.Context;
import static com.dwp.services.approval.routingdirectory.WorkflowStudioModels.*;

import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.domain.ApprovalWorkflowQuorumDefinition;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class WorkflowStudioRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ApprovalDocumentCanonical canonical;

    WorkflowStudioRepository(NamedParameterJdbcTemplate jdbc, ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    WorkflowStudio studio(Context context, UUID workflowId, boolean lock) {
        List<WorkflowStudio> values = jdbc.query("""
                SELECT workflow.workflow_id,workflow.workflow_key,workflow.lifecycle_state,
                       workflow.current_version,workflow.version,workflow.sla_minutes,
                       workflow.updated_at,workflow.updated_by,
                       version.workflow_version_id,version.version_number,version.definition::text,
                       version.definition_sha256,version.lifecycle_state AS version_state,
                       version.created_at,version.created_by
                  FROM apr_workflow_definitions workflow
                  JOIN apr_workflow_versions version
                    ON version.tenant_id=workflow.tenant_id
                   AND version.workflow_id=workflow.workflow_id
                   AND version.version_number=workflow.current_version
                 WHERE workflow.tenant_id=:tenant
                   AND workflow.management_resource_set_key=:scope
                   AND workflow.workflow_id=:workflowId
                """ + (lock ? " FOR UPDATE OF workflow" : ""),
                base(context).addValue("workflowId", workflowId), (row, number) -> {
                    Map<String, Object> definition = object(row.getString(11));
                    ApprovalWorkflowQuorumDefinition compiled = ApprovalWorkflowQuorumDefinition.compile(definition);
                    if (!compiled.sha256().equals(row.getString(12))) {
                        throw RoutingDirectoryRejected.unavailable("Stored workflow canvas integrity cannot be verified.");
                    }
                    UUID versionId = row.getObject(9, UUID.class);
                    return new WorkflowStudio(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                            row.getInt(4), row.getLong(5), row.getInt(6),
                            new WorkflowVersion(versionId, row.getInt(10), definition, row.getString(12),
                                    row.getString(13), instant(row.getTimestamp(14)), row.getObject(15, Long.class)),
                            instant(row.getTimestamp(7)), row.getObject(8, Long.class),
                            "/v1/admin/workflows/" + workflowId + "/publish",
                            "/v1/admin/workflows/" + workflowId + "/versions/" + versionId + "/simulation");
                });
        if (values.size() != 1) {
            throw RoutingDirectoryRejected.unavailable("Workflow is unavailable in the selected management scope.");
        }
        return values.getFirst();
    }

    WorkflowStudio save(Context context, UUID workflowId, CanvasSave input) {
        WorkflowStudio before = studio(context, workflowId, true);
        if (!"DRAFT".equals(before.lifecycleState()) || before.workflowRevision() != input.expectedVersion()) {
            throw RoutingDirectoryRejected.conflict("Workflow changed or is no longer an editable draft.");
        }
        ApprovalWorkflowQuorumDefinition compiled = ApprovalWorkflowQuorumDefinition.compile(input.definition());
        if (compiled.sha256().equals(before.current().definitionSha256())) {
            throw RoutingDirectoryRejected.conflict("Workflow canvas did not change.");
        }
        UUID versionId = UUID.randomUUID();
        int next = Math.addExact(before.currentVersion(), 1);
        MapSqlParameterSource parameters = base(context).addValue("workflowId", workflowId)
                .addValue("versionId", versionId).addValue("next", next)
                .addValue("expected", input.expectedVersion()).addValue("sla", compiled.slaMinutes())
                .addValue("definition", compiled.canonicalJson()).addValue("sha", compiled.sha256());
        if (jdbc.update("""
                INSERT INTO apr_workflow_versions(workflow_version_id,tenant_id,workflow_id,version_number,
                    definition,definition_sha256,lifecycle_state,created_by)
                VALUES(:versionId,:tenant,:workflowId,:next,CAST(:definition AS jsonb),:sha,'DRAFT',:actor)
                """, parameters) != 1) {
            throw RoutingDirectoryRejected.conflict("Workflow draft version could not be appended.");
        }
        if (jdbc.update("""
                UPDATE apr_workflow_definitions
                   SET current_version=:next,sla_minutes=:sla,version=version+1,
                       updated_at=clock_timestamp(),updated_by=:actor
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND workflow_id=:workflowId AND lifecycle_state='DRAFT'
                   AND version=:expected AND current_version=:next - 1
                """, parameters) != 1) {
            throw RoutingDirectoryRejected.conflict("Workflow canvas lost its object-version fence.");
        }
        event(context, workflowId, versionId, "SAVE_DRAFT", input.expectedVersion() + 1,
                compiled.sha256(), input, Map.of("previousVersion", before.currentVersion(),
                        "versionNumber", next, "stageCount", compiled.stages().size()));
        return studio(context, workflowId, false);
    }

    DryRunResult dryRun(Context context, UUID workflowId, CanvasDryRun input) {
        WorkflowStudio current = studio(context, workflowId, true);
        if (!"DRAFT".equals(current.lifecycleState()) || current.workflowRevision() != input.expectedVersion()) {
            throw RoutingDirectoryRejected.conflict("Workflow changed before the canvas dry-run.");
        }
        ApprovalWorkflowQuorumDefinition compiled = ApprovalWorkflowQuorumDefinition.compile(
                current.current().definition());
        DryRunResult result = new DryRunResult(workflowId, current.current().workflowVersionId(),
                current.workflowRevision(), compiled.sha256(), compiled.slaMinutes(),
                compiled.topologicalStages().stream().map(ApprovalWorkflowQuorumDefinition.Stage::key).toList(),
                true, true, current.runtimeSimulationEndpoint());
        event(context, workflowId, current.current().workflowVersionId(), "DRY_RUN",
                current.workflowRevision(), compiled.sha256(), input,
                Map.of("topologicalStageKeys", result.topologicalStageKeys(),
                        "runtimeSimulationRequired", true));
        return result;
    }

    WorkflowDiff diff(Context context, UUID workflowId, int fromVersion, int toVersion) {
        if (fromVersion < 1 || toVersion < 1 || fromVersion == toVersion) {
            throw RoutingDirectoryRejected.invalid("Two distinct positive workflow versions are required.");
        }
        studio(context, workflowId, false);
        WorkflowVersion from = version(context, workflowId, fromVersion);
        WorkflowVersion to = version(context, workflowId, toVersion);
        ApprovalWorkflowQuorumDefinition before = ApprovalWorkflowQuorumDefinition.compile(from.definition());
        ApprovalWorkflowQuorumDefinition after = ApprovalWorkflowQuorumDefinition.compile(to.definition());
        Map<String, ApprovalWorkflowQuorumDefinition.Stage> beforeByKey = stages(before);
        Map<String, ApprovalWorkflowQuorumDefinition.Stage> afterByKey = stages(after);
        Set<String> added = new LinkedHashSet<>(afterByKey.keySet()); added.removeAll(beforeByKey.keySet());
        Set<String> removed = new LinkedHashSet<>(beforeByKey.keySet()); removed.removeAll(afterByKey.keySet());
        List<String> changed = beforeByKey.keySet().stream().filter(afterByKey::containsKey)
                .filter(key -> !beforeByKey.get(key).equals(afterByKey.get(key))).sorted().toList();
        return new WorkflowDiff(workflowId, fromVersion, toVersion, from.definitionSha256(),
                to.definitionSha256(), added.stream().sorted().toList(),
                removed.stream().sorted().toList(), changed);
    }

    Retirement retire(Context context, UUID workflowId, RetireWorkflow input) {
        WorkflowStudio current = studio(context, workflowId, true);
        if (current.workflowRevision() != input.expectedVersion()
                || !Set.of("DRAFT", "PUBLISHED").contains(current.lifecycleState())) {
            throw RoutingDirectoryRejected.conflict("Workflow changed or cannot be retired.");
        }
        long activeReferences = activeReferences(context, workflowId);
        if (activeReferences != input.acknowledgedActiveReferences() || activeReferences != 0) {
            throw RoutingDirectoryRejected.conflict("Active workflow references must be reviewed and removed before retirement.");
        }
        int updated = jdbc.update("""
                UPDATE apr_workflow_definitions
                   SET lifecycle_state='RETIRED',version=version+1,
                       updated_at=clock_timestamp(),updated_by=:actor
                 WHERE tenant_id=:tenant AND management_resource_set_key=:scope
                   AND workflow_id=:workflowId AND version=:expected
                   AND lifecycle_state IN ('DRAFT','PUBLISHED')
                """, base(context).addValue("workflowId", workflowId)
                .addValue("expected", input.expectedVersion()));
        if (updated != 1) throw RoutingDirectoryRejected.conflict("Workflow retirement lost its object-version fence.");
        event(context, workflowId, current.current().workflowVersionId(), "RETIRE",
                input.expectedVersion() + 1, current.current().definitionSha256(), input,
                Map.of("activeReferences", activeReferences, "previousLifecycle", current.lifecycleState()));
        return new Retirement(workflowId, input.expectedVersion() + 1, "RETIRED", activeReferences, Instant.now());
    }

    private WorkflowVersion version(Context context, UUID workflowId, int number) {
        List<WorkflowVersion> values = jdbc.query("""
                SELECT version.workflow_version_id,version.version_number,version.definition::text,
                       version.definition_sha256,version.lifecycle_state,version.created_at,version.created_by
                  FROM apr_workflow_versions version
                  JOIN apr_workflow_definitions workflow
                    ON workflow.tenant_id=version.tenant_id AND workflow.workflow_id=version.workflow_id
                 WHERE workflow.tenant_id=:tenant AND workflow.management_resource_set_key=:scope
                   AND workflow.workflow_id=:workflowId AND version.version_number=:number
                """, base(context).addValue("workflowId", workflowId).addValue("number", number),
                (row, index) -> {
                    Map<String, Object> definition = object(row.getString(3));
                    ApprovalWorkflowQuorumDefinition compiled = ApprovalWorkflowQuorumDefinition.compile(definition);
                    if (!compiled.sha256().equals(row.getString(4))) {
                        throw RoutingDirectoryRejected.unavailable("Stored workflow version integrity cannot be verified.");
                    }
                    return new WorkflowVersion(row.getObject(1, UUID.class), row.getInt(2), definition,
                            row.getString(4), row.getString(5), instant(row.getTimestamp(6)),
                            row.getObject(7, Long.class));
                });
        if (values.size() != 1) throw RoutingDirectoryRejected.unavailable("Workflow version is unavailable.");
        return values.getFirst();
    }

    private long activeReferences(Context context, UUID workflowId) {
        Long count = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM apr_form_workflow_bindings binding
                         WHERE binding.tenant_id=:tenant AND binding.workflow_id=:workflowId
                           AND binding.lifecycle_state='ACTIVE')
                     + (SELECT count(*) FROM apr_requests request
                          JOIN apr_workflow_versions version
                            ON version.tenant_id=request.tenant_id
                           AND version.workflow_version_id=request.workflow_version_id
                         WHERE request.tenant_id=:tenant AND version.workflow_id=:workflowId
                           AND request.status IN ('DRAFT','SUBMITTED','IN_REVIEW','NEEDS_INFO'))
                """, base(context).addValue("workflowId", workflowId), Long.class);
        return count == null ? 0 : count;
    }

    private void event(Context context, UUID workflowId, UUID versionId, String action,
            long revision, String definitionSha, Object command, Map<String, Object> material) {
        if (jdbc.update("""
                INSERT INTO apr_workflow_studio_events(event_id,tenant_id,management_resource_set_key,
                    workflow_id,workflow_version_id,action,workflow_revision,definition_sha256,
                    command_sha256,material,actor_user_id)
                VALUES(:eventId,:tenant,:scope,:workflowId,:versionId,:action,:revision,:definitionSha,
                    :commandSha,CAST(:material AS jsonb),:actor)
                """, base(context).addValue("eventId", UUID.randomUUID()).addValue("workflowId", workflowId)
                .addValue("versionId", versionId).addValue("action", action).addValue("revision", revision)
                .addValue("definitionSha", definitionSha).addValue("commandSha", canonical.fingerprint(command))
                .addValue("material", canonical.json(material))) != 1) {
            throw RoutingDirectoryRejected.unavailable("Workflow Studio evidence could not be recorded.");
        }
    }

    private Map<String, ApprovalWorkflowQuorumDefinition.Stage> stages(ApprovalWorkflowQuorumDefinition value) {
        Map<String, ApprovalWorkflowQuorumDefinition.Stage> result = new LinkedHashMap<>();
        value.stages().forEach(stage -> result.put(stage.key(), stage));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> object(String value) {
        Object parsed = canonical.read(value, Object.class);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw RoutingDirectoryRejected.unavailable("Stored workflow material is not an object.");
        }
        return (Map<String, Object>) map;
    }

    private MapSqlParameterSource base(Context context) {
        return new MapSqlParameterSource().addValue("tenant", context.tenantId())
                .addValue("scope", context.resourceSetKey()).addValue("actor", context.actorUserId());
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
