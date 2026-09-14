package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.forms.ApprovalFormPublishedRoutePin;
import com.dwp.services.approval.security.ApprovalRequestContext.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** NEW initiation only: a captured workflow head revision is provenance, not a current-head CAS. */
public final class ApprovalWorkflowPublishedRouteRuntime {
    private ApprovalWorkflowPublishedRouteRuntime() { }

    @FunctionalInterface public interface CurrentWorkflowAuthorityGuard {
        AuthorityObservation require(Actor actor, ApprovalFormPublishedRoutePin pin);
    }
    public record AuthorityObservation(long tenantId, long actorId, UUID personPublicId,
            UUID workflowId, String resourceSetKey, String actionRoute, String decisionRevision,
            String sourceRevision, Instant validUntil) { }
    public record VerifiedPublishedWorkflow(UUID workflowVersionId, int workflowVersion,
            String workflowDefinitionSha256, String workflowDefinition, UUID formVersionId,
            String formSchemaSha256, String formSchema, String dataClassification,
            int slaMinutes, String managementResourceSetKey, long policyVersion, String policySha256) { }

    public static <T> T requirePublishedRoute(NamedParameterJdbcTemplate jdbc, Actor actor,
            ApprovalFormPublishedRoutePin pin, Function<VerifiedPublishedWorkflow, T> assembler,
            BiConsumer<String, Integer> legacyValidator, CurrentWorkflowAuthorityGuard guard) {
        if (guard == null || jdbc == null || assembler == null || legacyValidator == null
                || !TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
        if (pin == null || actor == null || actor.tenantId() == null || actor.userId() == null
                || actor.personPublicId() == null || pin.operation() != ApprovalFormPublishedRoutePin.Operation.NEW_INITIATION
                || pin.requestId() != null || pin.requestVersion() != null || pin.tenantId() != actor.tenantId()
                || pin.workflow() == null || pin.formId() == null || pin.formVersionId() == null) throw conflict();
        var before = guard.require(actor, pin);
        validateAuthority(before, actor, pin);
        var scope = new MapSqlParameterSource().addValue("tenant", actor.tenantId()).addValue("form", pin.formId())
                .addValue("formVersion", pin.formVersionId()).addValue("workflow", pin.workflow().workflowId())
                .addValue("workflowVersion", pin.workflow().workflowVersionId()).addValue("category", pin.categoryId())
                .addValue("resourceSet", pin.resourceSetKey());
        var rows = jdbc.query("""
                SELECT version.version_number, version.definition::text AS definition, version.definition_sha256,
                       version.effective_from, version.effective_to, form.version AS form_revision,
                       schema.schema_payload::text AS schema, schema.schema_sha256, category.version AS category_revision,
                       workspace.workspace_version, material.material_sha256, material.metadata_payload::text AS metadata,
                       material.route_payload::text AS route, material.provenance, material.captured_at
                  FROM apr_tenants tenant
                  JOIN apr_workflow_definitions workflow ON workflow.tenant_id=tenant.tenant_id
                  JOIN apr_workflow_versions version ON version.tenant_id=workflow.tenant_id AND version.workflow_id=workflow.workflow_id
                  JOIN apr_forms form ON form.tenant_id=tenant.tenant_id
                  JOIN apr_form_versions schema ON schema.tenant_id=form.tenant_id AND schema.form_id=form.form_id
                  JOIN apr_form_categories category ON category.tenant_id=form.tenant_id AND category.category_id=form.category_id
                  JOIN apr_form_workspaces workspace ON workspace.tenant_id=form.tenant_id AND workspace.form_id=form.form_id
                  JOIN apr_form_version_material material ON material.tenant_id=schema.tenant_id AND material.form_id=schema.form_id
                   AND material.form_version_id=schema.form_version_id
                 WHERE tenant.tenant_id=:tenant AND tenant.lifecycle_state='ACTIVE'
                   AND workflow.workflow_id=:workflow AND version.workflow_version_id=:workflowVersion
                   AND workflow.lifecycle_state='PUBLISHED' AND version.lifecycle_state='PUBLISHED'
                   AND workflow.management_resource_set_key=:resourceSet
                   AND (version.effective_from IS NULL OR version.effective_from<=clock_timestamp())
                   AND (version.effective_to IS NULL OR version.effective_to>clock_timestamp())
                   AND form.form_id=:form AND schema.form_version_id=:formVersion
                   AND form.lifecycle_state='PUBLISHED' AND schema.lifecycle_state='PUBLISHED'
                   AND form.current_version=schema.version_number AND form.management_resource_set_key=:resourceSet
                   AND category.category_id=:category AND category.lifecycle_state='ACTIVE'
                   AND category.management_resource_set_key=:resourceSet
                   AND workspace.published_form_version_id=schema.form_version_id AND workspace.catalog_availability='ACTIVE'
                 FOR SHARE OF tenant,workflow,version,form,schema,category,workspace,material
                """, scope, (row, index) -> {
                    if (row.getLong("form_revision") != pin.formRevision()
                            || row.getLong("workspace_version") != pin.workspaceRevision()
                            || row.getLong("category_revision") != pin.categoryRevision()
                            || row.getInt("version_number") != pin.workflow().workflowVersionNumber()
                            || !pin.workflow().definitionSha256().equals(row.getString("definition_sha256").strip())
                            || !java.util.Objects.equals(pin.workflow().effectiveFrom(), instant(row.getObject("effective_from", OffsetDateTime.class)))
                            || !java.util.Objects.equals(pin.workflow().effectiveTo(), instant(row.getObject("effective_to", OffsetDateTime.class)))) throw conflict();
                    var schema = object(row.getString("schema"));
                    var suppliedSchema = object(pin.schemaJson());
                    if (!canonical(schema).equals(canonical(suppliedSchema)) || !pin.schemaSha256().equals(row.getString("schema_sha256").strip())) throw conflict();
                    if (schema.containsKey("schemaContract")) {
                        if (!new ApprovalFormSchemaV2Compiler().compile(schema).sha256().equals(pin.schemaSha256())) throw conflict();
                    }
                    var metadata = object(row.getString("metadata"));
                    var route = object(row.getString("route"));
                    String digest = ApprovalFormSchemaV2Canonical.sha256(canonical(Map.of("schemaSha256", pin.schemaSha256(),
                            "metadata", metadata, "route", route)));
                    if (!digest.equals(pin.materialDigest()) || !digest.equals(row.getString("material_sha256").strip())
                            || !canonical(metadata).equals(canonical(pin.metadata()))
                            || !pin.metadataProvenance().equals(row.getString("provenance"))
                            || !Set.of("PUBLISH_SNAPSHOT", "LEGACY_CAPTURE_TIME").contains(pin.metadataProvenance())
                            || !pin.capturedAt().equals(instant(row.getObject("captured_at", OffsetDateTime.class)))) throw conflict();
                    verifyRoute(route, pin);
                    String definition = row.getString("definition");
                    if (object(definition).containsKey("schemaContract")) {
                        var typed = ApprovalWorkflowQuorumDefinition.compile(definition);
                        if (!typed.sha256().equals(pin.workflow().definitionSha256()) || typed.slaMinutes() != pin.workflow().slaMinutes()) throw conflict();
                    } else legacyValidator.accept(definition, pin.workflow().slaMinutes());
                    return definition;
                });
        if (rows.size() != 1) throw conflict();
        // Managed publish seals its route in immutable material; legacy adoption still needs its original effective binding.
        if ("LEGACY_CAPTURE_TIME".equals(pin.metadataProvenance())) {
            var bindings = jdbc.queryForList("SELECT form_id FROM apr_form_workflow_bindings WHERE tenant_id=:tenant "
                    + "AND form_id=:form AND workflow_id=:workflow AND lifecycle_state='ACTIVE' "
                    + "AND (effective_from IS NULL OR effective_from<=clock_timestamp()) "
                    + "AND (effective_to IS NULL OR effective_to>clock_timestamp()) FOR SHARE", scope);
            if (bindings.size() != 1) throw conflict();
        }
        var policy = policy(jdbc, scope);
        var after = guard.require(actor, pin);
        validateAuthority(after, actor, pin);
        if (!before.equals(after)) throw unavailable();
        var verified = new VerifiedPublishedWorkflow(pin.workflow().workflowVersionId(), pin.workflow().workflowVersionNumber(),
                pin.workflow().definitionSha256(), rows.getFirst(), pin.formVersionId(), pin.schemaSha256(), pin.schemaJson(),
                pin.workflow().dataClassification(), pin.workflow().slaMinutes(), pin.resourceSetKey(), policy.version(), policy.sha256());
        T result = assembler.apply(verified);
        if (result == null) throw unavailable();
        return result;
    }

    private static void validateAuthority(AuthorityObservation value, Actor actor, ApprovalFormPublishedRoutePin pin) {
        if (value == null || value.tenantId() != actor.tenantId() || value.actorId() != actor.userId()
                || !actor.personPublicId().equals(value.personPublicId()) || !pin.workflow().workflowId().equals(value.workflowId())
                || !pin.resourceSetKey().equals(value.resourceSetKey()) || value.actionRoute() == null
                || !"route.approvals.work.request-create.action".equals(value.actionRoute())
                || value.decisionRevision() == null || value.decisionRevision().isBlank()
                || value.sourceRevision() == null || value.sourceRevision().isBlank() || value.validUntil() == null
                || !value.validUntil().isAfter(Instant.now())) throw unavailable();
    }

    private static void verifyRoute(Map<String, Object> value, ApprovalFormPublishedRoutePin pin) {
        var route = pin.workflow();
        var expected = new java.util.LinkedHashMap<String, Object>();
        expected.put("workflowId", route.workflowId().toString()); expected.put("workflowVersionId", route.workflowVersionId().toString());
        expected.put("workflowRevision", route.capturedWorkflowRevision()); expected.put("workflowVersionNumber", route.workflowVersionNumber());
        expected.put("definitionSha256", route.definitionSha256()); expected.put("dataClassification", route.dataClassification());
        expected.put("slaMinutes", route.slaMinutes()); expected.put("effectiveFrom", route.effectiveFrom() == null ? null : route.effectiveFrom().toString());
        expected.put("effectiveTo", route.effectiveTo() == null ? null : route.effectiveTo().toString());
        if (!canonical(value).equals(canonical(expected))) throw conflict();
    }

    private record Policy(long version, String sha256) { }
    private static Policy policy(NamedParameterJdbcTemplate jdbc, MapSqlParameterSource scope) {
        var rows = jdbc.queryForList("SELECT policy_id,policy_key,version,enforcement_mode,lifecycle_state,rule_payload::text AS rule "
                + "FROM apr_policy_rules WHERE tenant_id=:tenant AND management_resource_set_key=:resourceSet "
                + "AND policy_key IN ('BLOCK_SELF_APPROVAL','REQUIRE_REJECT_REASON','SLA_ESCALATION') ORDER BY policy_key FOR SHARE", scope);
        if (rows.size() != 3 || rows.stream().anyMatch(row -> !"ACTIVE".equals(row.get("lifecycle_state")))) throw unavailable();
        var sla = rows.stream().filter(row -> "SLA_ESCALATION".equals(row.get("policy_key"))).findFirst().orElseThrow();
        var reject = rows.stream().filter(row -> "REQUIRE_REJECT_REASON".equals(row.get("policy_key"))).findFirst().orElseThrow();
        integer(object((String) reject.get("rule")).get("minimumLength"), 4, 1000);
        var rule = object((String) sla.get("rule"));
        int warning = integer(rule.get("warningPercent"), 1, 99); integer(rule.get("breachPercent"), warning, 100);
        List<Map<String, Object>> refs = rows.stream().map(row -> Map.<String, Object>of("policyId", row.get("policy_id").toString(),
                "key", row.get("policy_key"), "rowVersion", row.get("version"), "enforcement", row.get("enforcement_mode"),
                "rule", object((String) row.get("rule")))).toList();
        return new Policy(Math.addExact(((Number) sla.get("version")).longValue(), 1),
                ApprovalFormSchemaV2Canonical.sha256(canonical(Map.of("references", refs))));
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Integer number) || number < min || number > max) throw unavailable(); return number;
    }
    private static Map<String, Object> object(String value) {
        if (value == null || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144) throw unavailable();
        try { return new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(value, new TypeReference<>() { }); }
        catch (java.io.IOException exception) { throw unavailable(); }
    }
    private static String canonical(Map<String, ?> value) { return ApprovalFormSchemaV2Canonical.json(ApprovalFormSchemaV2Canonical.freeze(value)); }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
    private static BaseException conflict() { return new BaseException(ErrorCode.RESOURCE_CONFLICT, "The published form workflow pins changed."); }
    private static BaseException unavailable() { return new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, "Current published workflow authority cannot be verified."); }
}
