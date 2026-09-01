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


abstract class ApprovalCommandJdbcRepository {
    protected static final String ROOT_MANAGEMENT_SCOPE = "RS_APPROVALS";

    protected final NamedParameterJdbcTemplate jdbc;
    protected final ApprovalCommandPayloadSupport payloadSupport;
    protected final ApprovalDelegationCommandSupport delegationCommands;


    ApprovalCommandJdbcRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.payloadSupport = new ApprovalCommandPayloadSupport(objectMapper);
        this.delegationCommands = new ApprovalDelegationCommandSupport(jdbc, payloadSupport);
    }

    void validatePolicyRule(String policyKey, Map<String, Object> rule) {
        Set<String> expectedKeys;
        switch (policyKey) {
            case "BLOCK_SELF_APPROVAL" -> {
                expectedKeys = Set.of("requesterCannotDecide");
                if (!Boolean.TRUE.equals(rule.get("requesterCannotDecide"))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
                }
            }
            case "REQUIRE_REJECT_REASON" -> {
                expectedKeys = Set.of("minimumLength");
                boundedInteger(rule.get("minimumLength"), 4, 1000);
            }
            case "CAPTURE_DECISION_EVIDENCE" -> {
                expectedKeys = Set.of("retentionClass");
                if (!Set.of("STANDARD", "EXTENDED").contains(
                        String.valueOf(rule.get("retentionClass")))) {
                    throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
                }
            }
            case "SLA_ESCALATION" -> {
                expectedKeys = Set.of("warningPercent", "breachPercent");
                int warning = boundedInteger(rule.get("warningPercent"), 1, 99);
                int breach = boundedInteger(rule.get("breachPercent"), warning, 100);
                if (breach < warning) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
            }
            default -> throw new BaseException(ErrorCode.INVALID_STATE);
        }
        if (!rule.keySet().equals(expectedKeys)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    protected int boundedInteger(Object value, int minimum, int maximum) {
        if (!(value instanceof Number number)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        int parsed = number.intValue();
        if (parsed < minimum || parsed > maximum) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return parsed;
    }

    protected PolicyRuntime policy(
            long tenantId,
            String policyKey,
            String managementResourceSetKey) {
        return jdbc.query(ApprovalCommandSql01.POLICY_SELECT_APR_POLICY_RULES, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("policyKey", policyKey)
                .addValue("managementScope", managementResourceSetKey), result -> {
            if (!result.next()) return PolicyRuntime.disabled();
            return new PolicyRuntime(
                    result.getString("enforcement_mode"),
                    result.getString("lifecycle_state"),
                    payloadSupport.object(result.getString("rule_payload"),
                            "Stored approval policy data is invalid."));
        });
    }

    public void publishWorkflow(
            ApprovalRequestContext.Actor actor,
            UUID workflowId,
            long expectedVersion,
            String correlationId) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("workflowId", workflowId)
                .addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update(ApprovalCommandSql02.PUBLISH_WORKFLOW_UPDATE_APR_WORKFLOW_DEFINITIONS, params);
        requireUpdated(updated);
        jdbc.update(ApprovalCommandSql02.COALESCE_UPDATE_APR_WORKFLOW_VERSIONS, params);
    }

    protected WorkflowRuntime workflow(
            long tenantId,
            UUID workflowId,
            UUID formId,
            Map<String, Object> requestPayload,
            boolean requireRouteMatch) {
        return jdbc.query(ApprovalCommandSql02.WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("workflowId", workflowId)
                        .addValue("formId", formId),
                result -> {
                    if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
                    if (requireRouteMatch
                            && "CONDITIONAL".equals(result.getString("binding_type"))
                            && !matchesRouteCondition(
                                    result.getString("binding_condition"), requestPayload)) {
                        throw new BaseException(
                                ErrorCode.INVALID_STATE,
                                "The selected approval route does not match this request.");
                    }
                    return new WorkflowRuntime(
                            result.getObject("workflow_version_id", UUID.class),
                            result.getObject("form_version_id", UUID.class),
                            result.getString("data_classification"),
                            result.getString("management_resource_set_key"),
                            result.getString("form_schema"));
                });
    }

    boolean matchesRouteCondition(
            String storedCondition,
            Map<String, Object> requestPayload) {
        try {
            Map<String, Object> condition = payloadSupport.object(
                    storedCondition, "The approval route condition is invalid.");
            Object rawClauses = condition.get("all");
            if (!(rawClauses instanceof List<?> clauses) || clauses.isEmpty()) return false;
            for (Object rawClause : clauses) {
                if (!(rawClause instanceof Map<?, ?> clause)) return false;
                Object fieldValue = clause.get("field");
                String field = fieldValue == null ? "" : String.valueOf(fieldValue).trim();
                Object operatorValue = clause.get("operator");
                String operator = (operatorValue == null ? "EQ" : String.valueOf(operatorValue))
                        .trim().toUpperCase(Locale.ROOT);
                if (field.isEmpty() || !requestPayload.containsKey(field)) return false;
                Object actual = requestPayload.get(field);
                Object expected = clause.get("value");
                boolean matched = switch (operator) {
                    case "EQ" -> java.util.Objects.equals(actual, expected);
                    case "IN" -> expected instanceof List<?> values && values.contains(actual);
                    case "GTE", "GT", "LTE", "LT" -> compareNumbers(actual, expected, operator);
                    default -> false;
                };
                if (!matched) return false;
            }
            return true;
        } catch (BaseException exception) {
            throw exception;
        }
    }

    protected boolean compareNumbers(Object actual, Object expected, String operator) {
        if (!(actual instanceof Number actualNumber) || !(expected instanceof Number expectedNumber)) {
            return false;
        }
        int comparison = new BigDecimal(actualNumber.toString())
                .compareTo(new BigDecimal(expectedNumber.toString()));
        return switch (operator) {
            case "GTE" -> comparison >= 0;
            case "GT" -> comparison > 0;
            case "LTE" -> comparison <= 0;
            case "LT" -> comparison < 0;
            default -> false;
        };
    }


    protected RequestRuntime ownedRequest(ApprovalRequestContext.Actor actor, UUID requestId) {
        return jdbc.query(ApprovalCommandSql02.OWNED_REQUEST_SELECT_APR_REQUESTS, actorParams(actor).addValue("requestId", requestId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.NOT_FOUND);
            int slaMinutes = result.getInt("sla_minutes");
            return new RequestRuntime(
                    result.getString("status"),
                    result.getString("title"),
                    slaMinutes,
                    runtimeSteps(result.getString("workflow_definition"), slaMinutes),
                    result.getString("form_schema"),
                    payloadSupport.object(result.getString("request_payload"),
                            "Stored approval request data is invalid."),
                    result.getString("binding_type"),
                    result.getString("binding_condition"));
        });
    }

    protected InformationRuntime informationRuntime(
            ApprovalRequestContext.Actor actor,
            UUID requestId) {
        return jdbc.query(ApprovalCommandSql02.INFORMATION_RUNTIME_SELECT_APR_REQUESTS, actorParams(actor).addValue("requestId", requestId), result -> {
            if (!result.next()) throw new BaseException(ErrorCode.INVALID_STATE);
            return new InformationRuntime(
                    result.getString("form_schema"),
                    payloadSupport.object(result.getString("request_payload"),
                            "Stored approval request data is invalid."));
        });
    }

    protected void appendPayloadRevision(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String changeType,
            String correlationId,
            String reason) {
        jdbc.update(ApprovalCommandSql02.APPEND_PAYLOAD_REVISION_INSERT_APR_REQUEST_PAYLOAD_VERSIONS, actorParams(actor)
                .addValue("requestId", requestId)
                .addValue("changeType", changeType)
                .addValue("reason", reason)
                .addValue("correlationId", correlationId));
    }

    protected void appendEvent(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String eventType,
            String message,
            String correlationId,
            Map<String, Object> data) {
        Map<String, Object> evidence = new LinkedHashMap<>(data);
        if (actor.displayName() != null && !actor.displayName().isBlank()) {
            evidence.putIfAbsent("actorDisplayName", actor.displayName().trim());
        }
        jdbc.update(ApprovalCommandSql02.APPEND_EVENT_INSERT_APR_REQUEST_EVENTS, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("requestId", requestId)
                .addValue("eventType", eventType)
                .addValue("actorId", actor.userId().toString())
                .addValue("message", message)
                .addValue("correlationId", correlationId)
                .addValue("eventData", payloadSupport.json(evidence)));
    }

    protected void appendIntegration(
            ApprovalRequestContext.Actor actor,
            UUID requestId,
            String eventType,
            String correlationId,
            Map<String, Object> payload) {
        UUID eventId = UUID.randomUUID();
        String value = payloadSupport.json(Map.of(
                "specVersion", "1.0",
                "eventType", eventType,
                "tenantId", actor.tenantId(),
                "requestId", requestId.toString(),
                "correlationId", correlationId == null ? "" : correlationId,
                "payload", payload));
        int appended = jdbc.update(
                ApprovalCommandSql02.APPEND_INTEGRATION_INSERT_APR_INTEGRATION_OUTBOX,
                new MapSqlParameterSource()
                .addValue("outboxId", UUID.randomUUID())
                .addValue("eventId", eventId)
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("requestId", requestId)
                .addValue("eventType", eventType)
                .addValue("payload", value));
        requireUpdated(appended);
    }

    protected MapSqlParameterSource actorParams(ApprovalRequestContext.Actor actor) {
        return new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("userId", actor.userId())
                .addValue("managementScope", managementResourceSetKey());
    }

    protected String normalizedPriority(String value) {
        String normalized = value == null ? "NORMAL" : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("LOW", "NORMAL", "HIGH", "URGENT").contains(normalized)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return normalized;
    }

    protected Map<String, Object> requestPayload(
            String summary,
            Map<String, Object> rawPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (rawPayload != null) payload.putAll(rawPayload);
        payload.put("summary", summary == null ? "" : summary.trim());
        return payload;
    }

    void validateRequestPayload(String schema, Map<String, Object> payload) {
        validateRequestPayload(schema, payload, true);
    }

    void validateRequestPayload(
            String schema,
            Map<String, Object> payload,
            boolean requireRequiredFields) {
        try {
            Map<String, Object> definition = payloadSupport.object(
                    schema, "Stored approval form schema is invalid.");
            Object rawFields = definition.get("fields");
            if (!(rawFields instanceof List<?> fields) || fields.isEmpty()) {
                throw new BaseException(ErrorCode.INVALID_STATE);
            }
            Set<String> knownKeys = new HashSet<>();
            for (Object rawField : fields) {
                if (!(rawField instanceof Map<?, ?> field)) {
                    throw new BaseException(ErrorCode.INVALID_STATE);
                }
                String key = requiredRuntimeString(field, "key");
                String type = normalized(requiredRuntimeString(field, "type"),
                        Set.of("TEXT", "TEXTAREA", "NUMBER", "DATE", "SELECT", "USER"));
                boolean required = Boolean.parseBoolean(String.valueOf(field.get("required")));
                knownKeys.add(key);
                Object value = payload.get(key);
                boolean empty = value == null || (value instanceof String text && text.isBlank());
                if (requireRequiredFields && required && empty) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "Required approval field is missing: " + key);
                }
                if (empty) continue;
                validateRequestField(key, type, value, field.get("options"));
            }
            for (String key : payload.keySet()) {
                if (!knownKeys.contains(key) && !"createdFrom".equals(key)) {
                    throw new BaseException(
                            ErrorCode.INVALID_INPUT_VALUE,
                            "Unknown approval field: " + key);
                }
            }
        } catch (BaseException exception) {
            throw exception;
        }
    }

    protected void validateRequestField(
            String key,
            String type,
            Object value,
            Object rawOptions) {
        String text = String.valueOf(value).trim();
        try {
            switch (type) {
                case "NUMBER" -> new BigDecimal(text);
                case "DATE" -> LocalDate.parse(text);
                case "SELECT" -> {
                    if (!(rawOptions instanceof List<?> options)
                            || options.stream().map(String::valueOf).noneMatch(text::equals)) {
                        throw new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "Invalid option for approval field: " + key);
                    }
                }
                case "TEXT", "TEXTAREA", "USER" -> {
                    if (!(value instanceof String)) {
                        throw new BaseException(
                                ErrorCode.INVALID_INPUT_VALUE,
                                "Approval field must be text: " + key);
                    }
                }
                default -> throw new BaseException(ErrorCode.INVALID_STATE);
            }
        } catch (NumberFormatException | DateTimeParseException exception) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "Invalid value for approval field: " + key);
        }
    }

    protected String normalizeComment(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    protected void requireCategory(long tenantId, UUID categoryId) {
        Integer count = jdbc.queryForObject(ApprovalCommandSql02.REQUIRE_CATEGORY_SELECT_APR_FORM_CATEGORIES, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("managementScope", managementResourceSetKey())
                        .addValue("categoryId", categoryId), Integer.class);
        if (count == null || count == 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }

    protected void requireWorkflow(long tenantId, UUID workflowId) {
        Integer count = jdbc.queryForObject(ApprovalCommandSql02.REQUIRE_WORKFLOW_SELECT_APR_WORKFLOW_DEFINITIONS, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("managementScope", managementResourceSetKey())
                        .addValue("workflowId", workflowId), Integer.class);
        if (count == null || count == 0) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
    }

    protected void validateCategoryParent(long tenantId, UUID categoryId, UUID parentCategoryId) {
        if (parentCategoryId == null) return;
        if (parentCategoryId.equals(categoryId)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        requireCategory(tenantId, parentCategoryId);
        if (categoryId == null) return;
        Integer descendants = jdbc.queryForObject(ApprovalCommandSql02.VALIDATE_CATEGORY_PARENT_WITH_APR_FORM_CATEGORIES, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("managementScope", managementResourceSetKey())
                        .addValue("categoryId", categoryId)
                        .addValue("parentCategoryId", parentCategoryId), Integer.class);
        if (descendants != null && descendants > 0) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
    }

    protected void replaceDefaultFormRoute(
            ApprovalRequestContext.Actor actor,
            UUID formId,
            UUID workflowId) {
        MapSqlParameterSource params = actorParams(actor)
                .addValue("formId", formId)
                .addValue("workflowId", workflowId);
        jdbc.update(ApprovalCommandSql02.REPLACE_DEFAULT_FORM_ROUTE_UPDATE_APR_FORM_WORKFLOW_BINDINGS, params);
        int updated = jdbc.update(ApprovalCommandSql02.REPLACE_DEFAULT_FORM_ROUTE_UPDATE_APR_FORM_WORKFLOW_BINDINGS_2, params);
        if (updated == 0) {
            jdbc.update(ApprovalCommandSql02.REPLACE_DEFAULT_FORM_ROUTE_INSERT_APR_FORM_WORKFLOW_BINDINGS, params.addValue("bindingId", UUID.randomUUID()));
        }
    }

    List<ApprovalCommandRepository.RuntimeStep> runtimeSteps(String definition, int workflowSlaMinutes) {
        return payloadSupport.runtimeSteps(definition, workflowSlaMinutes);
    }

    protected String requiredRuntimeString(Map<?, ?> value, String key) {
        Object raw = value.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new BaseException(ErrorCode.INVALID_STATE);
        }
        return String.valueOf(raw).trim();
    }

    protected String normalizedOptional(String value) {
        return value == null ? "" : value.trim();
    }

    protected String normalized(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        return normalized;
    }

    protected void requireUpdated(int updated) {
        if (updated == 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
    }

    protected record WorkflowRuntime(
            UUID workflowVersionId,
            UUID formVersionId,
            String dataClassification,
            String managementResourceSetKey,
            String formSchema) {
    }

    protected String managementResourceSetKey() {
        return ApprovalManagementScopeContext.current()
                .map(ApprovalManagementScopeContext.Evidence::resourceSetKey)
                .orElseGet(() -> {
                    if (ApprovalDecisionRevisionContext.current().isPresent()) {
                        throw new BaseException(
                                ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                                "Approval management scope evidence is unavailable.");
                    }
                    return ROOT_MANAGEMENT_SCOPE;
                });
    }

    protected record RequestRuntime(
            String status,
            String title,
            int slaMinutes,
            List<ApprovalCommandRepository.RuntimeStep> steps,
            String formSchema,
            Map<String, Object> payload,
            String bindingType,
            String bindingCondition) {
    }

    protected record InformationRuntime(
            String formSchema,
            Map<String, Object> payload) {
    }


    protected record PolicyRuntime(
            String enforcementMode,
            String lifecycleState,
            Map<String, Object> rule) {

        static PolicyRuntime disabled() {
            return new PolicyRuntime("MONITOR", "DISABLED", Map.of());
        }

        boolean blocks() {
            return "ACTIVE".equals(lifecycleState) && "BLOCK".equals(enforcementMode);
        }

        int integer(String key, int fallback, int minimum, int maximum) {
            Object value = rule.get(key);
            if (!(value instanceof Number number)) return fallback;
            return Math.max(minimum, Math.min(maximum, number.intValue()));
        }
    }

}
