package com.dwp.services.approval.policy;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.document.ApprovalDocumentCanonical;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.security.ApprovalDecisionRevisionContext;
import com.dwp.services.approval.security.ApprovalManagementScopeContext;
import com.dwp.services.approval.security.ApprovalRequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Repository
public class ApprovalPolicyDraftRepository {
    private static final String ROOT_SCOPE = "RS_APPROVALS";
    private static final Set<String> MODES = Set.of("BLOCK", "WARN", "MONITOR");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> STATES = Set.of("ACTIVE", "DISABLED", "RETIRED");
    private static final Set<String> TYPES = Set.of(
            "IDENTITY", "DECISION", "SLA", "DATA", "SEGREGATION_OF_DUTIES");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ApprovalDocumentCanonical canonical;

    public ApprovalPolicyDraftRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ApprovalDocumentCanonical canonical) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.canonical = canonical;
    }

    public Result create(
            ApprovalRequestContext.Actor actor,
            ApprovalPolicyDraftDtos.Create raw,
            String idempotencyKey) {
        Draft draft = normalize(raw);
        String scope = managementScope();
        String commandSha256 = canonical.fingerprint(Map.of(
                "operation", "APPROVAL_POLICY_DRAFT_CREATE",
                "resourceSetKey", scope,
                "draft", draft));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", actor.tenantId())
                .addValue("scope", scope)
                .addValue("actorUserId", actor.userId())
                .addValue("idempotencyKey", idempotencyKey)
                .addValue("commandSha256", commandSha256);

        int inserted = jdbc.update("""
                INSERT INTO apr_policy_creation_commands (
                    tenant_id, management_resource_set_key, actor_user_id,
                    idempotency_key, command_sha256)
                VALUES (:tenantId, :scope, :actorUserId, :idempotencyKey, :commandSha256)
                ON CONFLICT DO NOTHING
                """, params);
        Receipt receipt = lockReceipt(params);
        if (!commandSha256.equals(receipt.commandSha256())) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The idempotency key is bound to a different policy draft command.");
        }
        if (inserted == 0) {
            if (!"SUCCEEDED".equals(receipt.status()) || receipt.resultPayload() == null) {
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The policy draft command outcome is unknown.");
            }
            return new Result(readResult(receipt.resultPayload()), false);
        }

        UUID policyId = UUID.randomUUID();
        params.addValue("policyId", policyId)
                .addValue("policyKey", draft.policyKey())
                .addValue("nameKo", draft.nameKo())
                .addValue("nameEn", draft.nameEn())
                .addValue("policyType", draft.policyType())
                .addValue("pendingMode", draft.enforcementMode())
                .addValue("pendingSeverity", draft.severity())
                .addValue("pendingState", draft.lifecycleState())
                .addValue("rule", canonical.json(draft.rule()))
                .addValue("changeReason", draft.changeReason());
        ApprovalDtos.PolicySummary created;
        try {
            created = jdbc.query("""
                    INSERT INTO apr_policy_rules (
                        policy_id, tenant_id, policy_key, name_ko, name_en,
                        policy_type, enforcement_mode, severity, rule_payload,
                        lifecycle_state, version, management_resource_set_key,
                        pending_enforcement_mode, pending_severity,
                        pending_lifecycle_state, pending_rule_payload,
                        pending_change_reason, pending_by, pending_at,
                        created_by, updated_by)
                    VALUES (
                        :policyId, :tenantId, :policyKey, :nameKo, :nameEn,
                        :policyType, 'MONITOR', 'LOW', CAST(:rule AS jsonb),
                        'DISABLED', 0, :scope,
                        :pendingMode, :pendingSeverity, :pendingState,
                        CAST(:rule AS jsonb), :changeReason, :actorUserId,
                        CURRENT_TIMESTAMP, :actorUserId, :actorUserId)
                    RETURNING policy_id, policy_key, name_ko, name_en, policy_type,
                              enforcement_mode, severity, lifecycle_state,
                              rule_payload::text, version,
                              pending_enforcement_mode, pending_severity,
                              pending_lifecycle_state, pending_rule_payload::text,
                              pending_change_reason, pending_by, pending_at
                    """, params, result -> {
                if (!result.next()) {
                    throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                            "The policy draft result could not be established.");
                }
                return map(result);
            });
        } catch (DuplicateKeyException exception) {
            throw new BaseException(ErrorCode.RESOURCE_CONFLICT,
                    "The policy key already exists in the selected management scope.");
        }
        String resultPayload = writeResult(created);
        int completed = jdbc.update("""
                UPDATE apr_policy_creation_commands
                   SET status = 'SUCCEEDED', policy_id = :policyId,
                       result_payload = CAST(:resultPayload AS jsonb),
                       completed_at = CURRENT_TIMESTAMP
                 WHERE tenant_id = :tenantId
                   AND management_resource_set_key = :scope
                   AND actor_user_id = :actorUserId
                   AND idempotency_key = :idempotencyKey
                   AND command_sha256 = :commandSha256
                   AND status = 'UNKNOWN'
                """, params.addValue("resultPayload", resultPayload));
        if (completed != 1) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The policy draft command could not be completed exactly once.");
        }
        return new Result(created, true);
    }

    private Draft normalize(ApprovalPolicyDraftDtos.Create raw) {
        String key = normalized(raw.policyKey(), Set.of());
        String type = normalized(raw.policyType(), TYPES);
        String mode = normalized(raw.enforcementMode(), MODES);
        String severity = normalized(raw.severity(), SEVERITIES);
        String state = normalized(raw.lifecycleState(), STATES);
        Map<String, Object> rule = normalizedRule(raw.rule());
        return new Draft(key, raw.nameKo().trim(), raw.nameEn().trim(), type,
                mode, severity, state, rule, raw.changeReason().trim());
    }

    private String normalized(String value, Set<String> allowed) {
        if (value == null) throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ((!allowed.isEmpty() && !allowed.contains(normalized))
                || (allowed.isEmpty() && !normalized.matches("[A-Z][A-Z0-9_.-]{2,99}"))) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return normalized;
    }

    private Map<String, Object> normalizedRule(Map<String, Object> source) {
        if (source == null || source.isEmpty() || source.size() > 64) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> copy = objectMapper.convertValue(source, LinkedHashMap.class);
        byte[] encoded = canonical.json(copy).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 65_536 || !safe(copy, 0)) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The policy rule is too large or structurally unsafe.");
        }
        return Map.copyOf(copy);
    }

    private boolean safe(Object value, int depth) {
        if (depth > 8) return false;
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 64) return false;
            return map.entrySet().stream().allMatch(entry -> entry.getKey() instanceof String key
                    && key.matches("[A-Za-z][A-Za-z0-9_.-]{0,79}")
                    && safe(entry.getValue(), depth + 1));
        }
        if (value instanceof Iterable<?> values) {
            int count = 0;
            for (Object item : values) {
                if (++count > 100 || !safe(item, depth + 1)) return false;
            }
            return true;
        }
        return value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal;
    }

    private Receipt lockReceipt(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT command_sha256, status, result_payload::text
                  FROM apr_policy_creation_commands
                 WHERE tenant_id = :tenantId
                   AND management_resource_set_key = :scope
                   AND actor_user_id = :actorUserId
                   AND idempotency_key = :idempotencyKey
                 FOR UPDATE
                """, params, result -> {
            if (!result.next()) {
                throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                        "The policy draft command receipt is unavailable.");
            }
            return new Receipt(result.getString(1), result.getString(2), result.getString(3));
        });
    }

    private ApprovalDtos.PolicySummary map(java.sql.ResultSet result) throws java.sql.SQLException {
        return new ApprovalDtos.PolicySummary(
                result.getObject("policy_id", UUID.class), result.getString("policy_key"),
                result.getString("name_ko"), result.getString("name_en"),
                result.getString("policy_type"), result.getString("enforcement_mode"),
                result.getString("severity"), result.getString("lifecycle_state"),
                readMap(result.getString("rule_payload")), result.getLong("version"), true,
                result.getString("pending_enforcement_mode"),
                result.getString("pending_severity"),
                result.getString("pending_lifecycle_state"),
                readMap(result.getString("pending_rule_payload")),
                result.getString("pending_change_reason"),
                result.getObject("pending_by", Long.class),
                result.getObject("pending_at", java.time.OffsetDateTime.class).toInstant());
    }

    private String writeResult(ApprovalDtos.PolicySummary result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The policy draft result could not be sealed.");
        }
    }

    private ApprovalDtos.PolicySummary readResult(String value) {
        try {
            return objectMapper.readValue(value, ApprovalDtos.PolicySummary.class);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The stored policy draft result is invalid.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try {
            return value == null ? Map.of() : objectMapper.readValue(value, Map.class);
        } catch (JsonProcessingException exception) {
            throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "The stored policy rule is invalid.");
        }
    }

    private String managementScope() {
        return ApprovalManagementScopeContext.current()
                .map(ApprovalManagementScopeContext.Evidence::resourceSetKey)
                .orElseGet(() -> {
                    if (ApprovalDecisionRevisionContext.current().isPresent()) {
                        throw new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                                "Approval management scope evidence is unavailable.");
                    }
                    return ROOT_SCOPE;
                });
    }

    public record Result(ApprovalDtos.PolicySummary policy, boolean created) {
    }

    private record Receipt(String commandSha256, String status, String resultPayload) {
    }

    private record Draft(
            String policyKey, String nameKo, String nameEn, String policyType,
            String enforcementMode, String severity, String lifecycleState,
            Map<String, Object> rule, String changeReason) {
    }
}
