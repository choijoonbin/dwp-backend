package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


class ApprovalCommandManagementRepository extends ApprovalCommandLifecycleRepository {
    ApprovalCommandManagementRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    public ApprovalDelegationCommandSupport.Created createDelegation(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateDelegationRequest request,
            com.dwp.services.approval.integration.ApprovalIdentityDirectory.Subject delegate) {
        return delegationCommands.create(actor, request, delegate);
    }

    public void revokeDelegation(
            ApprovalRequestContext.Actor actor,
            UUID delegationId,
            long expectedVersion) {
        delegationCommands.revoke(actor, delegationId, expectedVersion);
    }

    public UUID createFormCategory(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateFormCategoryRequest request) {
        String categoryKey = request.categoryKey().trim().toUpperCase(Locale.ROOT);
        validateCategoryParent(actor.tenantId(), null, request.parentCategoryId());
        UUID categoryId = UUID.randomUUID();
        try {
            jdbc.update(ApprovalCommandSql01.CREATE_FORM_CATEGORY_INSERT_APR_FORM_CATEGORIES, actorParams(actor)
                    .addValue("categoryId", categoryId)
                    .addValue("categoryKey", categoryKey)
                    .addValue("parentCategoryId", request.parentCategoryId())
                    .addValue("nameKo", request.nameKo().trim())
                    .addValue("nameEn", request.nameEn().trim())
                    .addValue("descriptionKo", normalizedOptional(request.descriptionKo()))
                    .addValue("descriptionEn", normalizedOptional(request.descriptionEn()))
                    .addValue("iconKey", request.iconKey().trim())
                    .addValue("sortOrder", request.sortOrder()));
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        return categoryId;
    }

    public void updateFormCategory(
            ApprovalRequestContext.Actor actor,
            UUID categoryId,
            ApprovalDtos.UpdateFormCategoryRequest request) {
        validateCategoryParent(actor.tenantId(), categoryId, request.parentCategoryId());
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_FORM_CATEGORY_UPDATE_APR_FORM_CATEGORIES, actorParams(actor)
                .addValue("categoryId", categoryId)
                .addValue("parentCategoryId", request.parentCategoryId())
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", normalizedOptional(request.descriptionKo()))
                .addValue("descriptionEn", normalizedOptional(request.descriptionEn()))
                .addValue("iconKey", request.iconKey().trim())
                .addValue("sortOrder", request.sortOrder())
                .addValue("lifecycleState", request.lifecycleState().trim().toUpperCase(Locale.ROOT))
                .addValue("expectedVersion", request.expectedVersion()));
        requireUpdated(updated);
    }

    public UUID createFormDraft(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateFormDraftRequest request) {
        payloadSupport.validateFormFields(request.fields());
        requireCategory(actor.tenantId(), request.categoryId());
        requireWorkflow(actor.tenantId(), request.defaultWorkflowId());
        String formKey = request.formKey().trim().toUpperCase(Locale.ROOT);
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        String schema = payloadSupport.json(payloadSupport.formSchema(request.fields()));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("formVersionId", formVersionId)
                .addValue("formKey", formKey)
                .addValue("categoryId", request.categoryId())
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("workflowId", request.defaultWorkflowId())
                .addValue("schema", schema)
                .addValue("bindingId", UUID.randomUUID());
        try {
            jdbc.update(ApprovalCommandSql01.CREATE_FORM_DRAFT_INSERT_APR_FORMS, params);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
        jdbc.update(ApprovalCommandSql01.APR_FORMS_INSERT_APR_FORM_VERSIONS, params);
        jdbc.update(ApprovalCommandSql01.APR_FORM_VERSIONS_INSERT_APR_FORM_WORKFLOW_BINDINGS, params);
        return formId;
    }

    public UUID createWorkflowDraft(
            ApprovalRequestContext.Actor actor,
            ApprovalDtos.CreateWorkflowDraftRequest request) {
        String workflowKey = request.workflowKey().trim().toUpperCase(Locale.ROOT);
        payloadSupport.validateWorkflowInput(
                request.category(), request.dataClassification(), request.slaMinutes(), request.steps());
        Integer existing = jdbc.queryForObject(ApprovalCommandSql01.CREATE_WORKFLOW_DRAFT_SELECT_APR_WORKFLOW_DEFINITIONS, actorParams(actor).addValue("workflowKey", workflowKey), Integer.class);
        if (existing != null && existing > 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);

        jdbc.update(
                ApprovalCommandSql01.ENSURE_WORKFLOW_CATEGORY_INSERT_APR_FORM_CATEGORIES,
                actorParams(actor).addValue(
                        "category", request.category().trim().toUpperCase(Locale.ROOT)));

        UUID workflowId = UUID.randomUUID();
        UUID workflowVersionId = UUID.randomUUID();
        UUID formId = UUID.randomUUID();
        UUID formVersionId = UUID.randomUUID();
        String definition = payloadSupport.json(payloadSupport.workflowDefinition(request.steps()));
        String schema = payloadSupport.json(payloadSupport.defaultFormSchema());
        MapSqlParameterSource params = actorParams(actor)
                .addValue("workflowId", workflowId)
                .addValue("workflowVersionId", workflowVersionId)
                .addValue("workflowKey", workflowKey)
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("category", request.category().trim().toUpperCase(Locale.ROOT))
                .addValue("classification", request.dataClassification().trim().toUpperCase(Locale.ROOT))
                .addValue("slaMinutes", request.slaMinutes())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("definition", definition)
                .addValue("formId", formId)
                .addValue("formVersionId", formVersionId)
                .addValue("formKey", workflowKey + "_FORM")
                .addValue("formNameKo", request.nameKo().trim() + " 양식")
                .addValue("formNameEn", request.nameEn().trim() + " form")
                .addValue("schema", schema);
        jdbc.update(ApprovalCommandSql01.COUNT_INSERT_APR_WORKFLOW_DEFINITIONS, params);
        jdbc.update(ApprovalCommandSql01.APR_WORKFLOW_DEFINITIONS_INSERT_APR_WORKFLOW_VERSIONS, params);
        jdbc.update(ApprovalCommandSql01.APR_WORKFLOW_VERSIONS_INSERT_APR_FORMS, params);
        jdbc.update(ApprovalCommandSql01.APR_FORMS_INSERT_APR_FORM_VERSIONS_2, params);
        jdbc.update(ApprovalCommandSql01.APR_FORM_VERSIONS_INSERT_APR_FORM_WORKFLOW_BINDINGS_2, params.addValue("bindingId", UUID.randomUUID()));
        return workflowId;
    }

    public void updateWorkflowDraft(
            ApprovalRequestContext.Actor actor,
            UUID workflowId,
            ApprovalDtos.UpdateWorkflowDraftRequest request) {
        payloadSupport.validateWorkflowInput(
                request.category(), request.dataClassification(), request.slaMinutes(), request.steps());
        String definition = payloadSupport.json(payloadSupport.workflowDefinition(request.steps()));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("workflowId", workflowId)
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("category", request.category().trim().toUpperCase(Locale.ROOT))
                .addValue("classification", request.dataClassification().trim().toUpperCase(Locale.ROOT))
                .addValue("slaMinutes", request.slaMinutes())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("definition", definition)
                .addValue("expectedVersion", request.expectedVersion());
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_WORKFLOW_DRAFT_UPDATE_APR_WORKFLOW_DEFINITIONS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.UPDATE_WORKFLOW_DRAFT_UPDATE_APR_WORKFLOW_VERSIONS, params);
    }

    public void updateFormDraft(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            ApprovalDtos.UpdateFormDraftRequest request) {
        payloadSupport.validateFormFields(request.fields());
        requireCategory(actor.tenantId(), request.categoryId());
        requireWorkflow(actor.tenantId(), request.defaultWorkflowId());
        String schema = payloadSupport.json(payloadSupport.formSchema(request.fields()));
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("categoryId", request.categoryId())
                .addValue("nameKo", request.nameKo().trim())
                .addValue("nameEn", request.nameEn().trim())
                .addValue("descriptionKo", request.descriptionKo().trim())
                .addValue("descriptionEn", request.descriptionEn().trim())
                .addValue("ownerGroupRef", request.ownerGroupRef().trim())
                .addValue("workflowId", request.defaultWorkflowId())
                .addValue("schema", schema)
                .addValue("expectedVersion", request.expectedVersion());
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_FORM_DRAFT_UPDATE_APR_FORMS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.UPDATE_FORM_DRAFT_UPDATE_APR_FORM_VERSIONS, params);
        replaceDefaultFormRoute(actor, formId, request.defaultWorkflowId());
    }

    public void publishForm(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            long expectedVersion) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update(ApprovalCommandSql01.PUBLISH_FORM_UPDATE_APR_FORMS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql01.EXISTS_UPDATE_APR_FORM_VERSIONS, params);
    }

    public void updatePolicy(
            ApprovalRequestContext.Actor actor,
            UUID policyId,
            ApprovalDtos.UpdatePolicyRequest request) {
        String mode = normalized(request.enforcementMode(),
                Set.of("BLOCK", "WARN", "MONITOR"));
        String severity = normalized(request.severity(),
                Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL"));
        String state = normalized(request.lifecycleState(),
                Set.of("ACTIVE", "DISABLED", "RETIRED"));
        String policyKey = jdbc.query(ApprovalCommandSql01.UPDATE_POLICY_SELECT_APR_POLICY_RULES, actorParams(actor).addValue("policyId", policyId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            return result.getString("policy_key");
        });
        validatePolicyRule(policyKey, request.rule());
        int updated = jdbc.update(ApprovalCommandSql01.UPDATE_POLICY_UPDATE_APR_POLICY_RULES, actorParams(actor)
                .addValue("policyId", policyId)
                .addValue("mode", mode)
                .addValue("severity", severity)
                .addValue("state", state)
                .addValue("rule", payloadSupport.json(request.rule()))
                .addValue("changeReason", request.changeReason().trim())
                .addValue("expectedVersion", request.expectedVersion()));
        requireUpdated(updated);
    }

    public void publishPolicy(
            ApprovalRequestContext.Actor actor,
            UUID policyId,
            ApprovalDtos.PublishPolicyRequest request) {
        int updated = jdbc.update(ApprovalCommandSql01.PUBLISH_POLICY_WITH_APR_POLICY_RULES, actorParams(actor)
                .addValue("policyId", policyId)
                .addValue("policyVersionId", UUID.randomUUID())
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("reviewComment", request.reviewComment().trim()));
        requireUpdated(updated);
    }

    public void retryIntegrationDelivery(
            ApprovalRequestContext.Actor actor,
            UUID outboxId,
            long expectedVersion) {
        int updated = jdbc.update(
                ApprovalCommandSql01.RETRY_INTEGRATION_DELIVERY_UPDATE_APR_INTEGRATION_OUTBOX,
                actorParams(actor)
                        .addValue("outboxId", outboxId)
                        .addValue("expectedVersion", expectedVersion));
        requireUpdated(updated);
    }

    public void retryIntegrationDelivery(
            ApprovalRequestContext.Actor actor,
            UUID outboxId) {
        int updated = jdbc.update(
                ApprovalCommandSql01.LEGACY_RETRY_INTEGRATION_DELIVERY_UPDATE_APR_INTEGRATION_OUTBOX,
                actorParams(actor).addValue("outboxId", outboxId));
        requireUpdated(updated);
    }

}
