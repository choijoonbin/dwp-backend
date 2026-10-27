package com.dwp.services.approval.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Value calculations and step SQL shared by the lifecycle command flow. */
final class ApprovalCommandLifecycleSupport {

    private ApprovalCommandLifecycleSupport() {
    }

    static void requireHrisDraftAuditIdentity(String message, String source) {
        boolean employeeChange = "HRIS employee-change draft created".equals(message)
                && "HRIS_EMPLOYEE_CHANGE".equals(source);
        boolean separation = "HRIS separation-task waiver draft created".equals(message)
                && "HRIS_SEPARATION_TASK_WAIVER".equals(source);
        if (!employeeChange && !separation) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE,
                    "The HRIS draft audit identity is not registered.");
        }
    }

    static Map<String, Object> amendSummary(
            Map<String, Object> amendedPayload, Map<String, Object> patch) {
        if (patch != null) amendedPayload.putAll(patch);
        if (patch != null && patch.containsKey("summary")) {
            Object summaryValue = amendedPayload.get("summary");
            if (!(summaryValue instanceof String summary)
                    || summary.strip().isEmpty()
                    || summary.strip().length() > 2000) {
                throw new BaseException(
                        ErrorCode.INVALID_INPUT_VALUE,
                        "The amended approval summary must contain at most 2000 characters.");
            }
            amendedPayload.put("summary", summary.strip());
        }
        return amendedPayload;
    }

    static List<String> amendedFields(
            Map<String, Object> previous, Map<String, Object> amendedPayload,
            Map<String, Object> patch, boolean typedForm) {
        List<String> amendedFields = new java.util.ArrayList<>();
        if (patch != null || typedForm) {
            Map<String, Object> previousValues = typedForm
                    ? ApprovalFormSchemaV2Canonical.freeze(previous) : previous;
            Map<String, Object> resultingValues = typedForm
                    ? ApprovalFormSchemaV2Canonical.freeze(amendedPayload) : amendedPayload;
            Set<String> changedKeys = new HashSet<>();
            if (typedForm) {
                changedKeys.addAll(previous.keySet());
                changedKeys.addAll(amendedPayload.keySet());
            } else changedKeys.addAll(patch.keySet());
            for (String key : changedKeys) {
                if (!java.util.Objects.equals(
                        previousValues.get(key), resultingValues.get(key))) {
                    amendedFields.add(key);
                }
            }
        }
        amendedFields.sort(String::compareTo);
        return amendedFields;
    }

    static Map<String, Object> informationEvidence(
            String message, List<String> amendedFields, boolean materialChange,
            int previousRevision, String previousDigest, int resultingRevision,
            String resultingDigest, int supersededDecisions, UUID restartedTaskId) {
        Map<String, Object> eventEvidence = new LinkedHashMap<>();
        eventEvidence.put("responseLength", message.trim().length());
        eventEvidence.put("amendedFields", amendedFields);
        eventEvidence.put("materialChange", materialChange);
        eventEvidence.put("previousPayloadRevision", previousRevision);
        eventEvidence.put("previousPayloadSha256", previousDigest);
        eventEvidence.put("payloadRevision", resultingRevision);
        eventEvidence.put("payloadSha256", resultingDigest);
        eventEvidence.put("supersededDecisionCount", supersededDecisions);
        if (restartedTaskId != null) {
            eventEvidence.put("restartedTaskId", restartedTaskId.toString());
        }
        return eventEvidence;
    }

    static void restartSteps(
            NamedParameterJdbcTemplate jdbc, UUID requestId, List<Step> steps,
            Supplier<MapSqlParameterSource> actorParams) {
        int cumulativeMinutes = 0;
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            cumulativeMinutes = Math.addExact(cumulativeMinutes, step.slaMinutes());
            int stepUpdated = jdbc.update(
                    ApprovalCommandInformationSql.RESTART_STEPS,
                    actorParams.get()
                            .addValue("requestId", requestId)
                            .addValue("sequenceNumber", index + 1)
                            .addValue("candidateRole", step.candidateRole())
                            .addValue("cumulativeMinutes", cumulativeMinutes));
            if (stepUpdated == 0) throw new BaseException(ErrorCode.RESOURCE_CONFLICT);
        }
    }

    static NextStep nextStep(
            NamedParameterJdbcTemplate jdbc, long tenantId, UUID requestId, String sql) {
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("requestId", requestId), result -> result.next()
                        ? new NextStep(
                                result.getObject("step_id", UUID.class),
                                result.getString("step_key"),
                                result.getString("step_name"),
                                result.getString("candidate_role"),
                                result.getTimestamp("due_at").toInstant())
                        : null);
    }

    record Step(String candidateRole, int slaMinutes) {
    }

    record NextStep(
            UUID stepId,
            String stepKey,
            String stepName,
            String candidateRole,
            Instant dueAt) {
    }

}
