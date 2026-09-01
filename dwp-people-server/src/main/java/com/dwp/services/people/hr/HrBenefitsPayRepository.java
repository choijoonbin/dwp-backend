package com.dwp.services.people.hr;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class HrBenefitsPayRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    HrBenefitsPayRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    List<HrDtos.BenefitPlan> benefitPlans(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT plan.public_id, plan.plan_type, plan.name, plan.provider_name,
                       enrollment.coverage_level, enrollment.status,
                       enrollment.effective_start_date, enrollment.effective_end_date
                  FROM bnf_enrollments enrollment
                  JOIN bnf_benefit_plans plan
                    ON plan.tenant_id = enrollment.tenant_id
                   AND plan.benefit_plan_id = enrollment.benefit_plan_id
                 WHERE enrollment.tenant_id = ? AND enrollment.worker_id = ?
                   AND enrollment.status IN ('ELECTED', 'ACTIVE', 'WAIVED')
                 ORDER BY plan.plan_type, plan.name
                """, (result, ignored) -> new HrDtos.BenefitPlan(
                result.getObject("public_id", UUID.class), result.getString("plan_type"),
                result.getString("name"), result.getString("provider_name"),
                result.getString("coverage_level"), result.getString("status"),
                result.getObject("effective_start_date", LocalDate.class),
                result.getObject("effective_end_date", LocalDate.class)), tenantId, workerId);
    }

    List<HrDtos.EnrollmentWindow> enrollmentWindows(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT enrollment_window.public_id, enrollment_window.name,
                       enrollment_window.window_type, enrollment_window.opens_at,
                       enrollment_window.closes_at, enrollment_window.lifecycle_state
                  FROM bnf_enrollment_windows enrollment_window
                 WHERE enrollment_window.tenant_id = ?
                   AND enrollment_window.lifecycle_state IN ('SCHEDULED', 'OPEN')
                   AND (
                       (enrollment_window.window_type = 'OPEN_ENROLLMENT' AND EXISTS (
                           SELECT 1
                             FROM ppl_workers worker
                            WHERE worker.tenant_id = enrollment_window.tenant_id
                              AND worker.worker_id = ?
                              AND worker.worker_type = 'EMPLOYEE'
                              AND worker.worker_status IN ('ACTIVE', 'LEAVE')
                       ))
                       OR EXISTS (
                           SELECT 1
                             FROM bnf_enrollments enrollment
                             JOIN bnf_benefit_plans plan
                               ON plan.tenant_id = enrollment.tenant_id
                              AND plan.benefit_plan_id = enrollment.benefit_plan_id
                            WHERE enrollment.tenant_id = enrollment_window.tenant_id
                              AND enrollment.worker_id = ?
                              AND plan.benefit_program_id = enrollment_window.benefit_program_id
                              AND enrollment.status IN ('DRAFT', 'ELECTED', 'ACTIVE')
                       )
                   )
                 ORDER BY enrollment_window.opens_at
                """, (result, ignored) -> new HrDtos.EnrollmentWindow(
                result.getObject("public_id", UUID.class), result.getString("name"),
                result.getString("window_type"), instant(result.getTimestamp("opens_at")),
                instant(result.getTimestamp("closes_at")), result.getString("lifecycle_state")),
                tenantId, workerId, workerId);
    }

    HrDtos.PayCycle nextPayCycle(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT cycle.public_id, cycle.name, cycle.period_start_date,
                       cycle.period_end_date, cycle.pay_date, cycle.status, cycle.readiness
                  FROM pay_pay_cycles cycle
                  JOIN pay_statement_references statement
                    ON statement.tenant_id = cycle.tenant_id
                   AND statement.pay_cycle_id = cycle.pay_cycle_id
                   AND statement.worker_id = ?
                 WHERE cycle.tenant_id = ? AND cycle.status NOT IN ('PAID', 'CANCELLED')
                 ORDER BY cycle.pay_date
                 LIMIT 1
                """, (result, ignored) -> {
            Map<String, Object> readiness = json(result.getString("readiness"));
            return new HrDtos.PayCycle(
                    result.getObject("public_id", UUID.class), result.getString("name"),
                    result.getObject("period_start_date", LocalDate.class),
                    result.getObject("period_end_date", LocalDate.class),
                    result.getObject("pay_date", LocalDate.class), result.getString("status"),
                    Boolean.TRUE.equals(readiness.get("timeValidated")),
                    Boolean.TRUE.equals(readiness.get("absenceValidated")),
                    Boolean.TRUE.equals(readiness.get("sourceConfirmed")),
                    String.valueOf(readiness.getOrDefault("dataOrigin", "UNKNOWN")));
        }, workerId, tenantId).stream().findFirst().orElse(null);
    }

    List<HrDtos.PayStatement> payStatements(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT public_id, statement_period_label, availability_state,
                       published_at, document_reference
                  FROM pay_statement_references
                 WHERE tenant_id = ? AND worker_id = ?
                 ORDER BY created_at DESC
                 LIMIT 24
                """, (result, ignored) -> new HrDtos.PayStatement(
                result.getObject("public_id", UUID.class),
                result.getString("statement_period_label"),
                result.getString("availability_state"),
                instant(result.getTimestamp("published_at")),
                "AVAILABLE".equals(result.getString("availability_state"))
                        && !result.getString("document_reference").startsWith("reference://")),
                tenantId, workerId);
    }

    long activeBenefits(Long tenantId, long workerId) {
        return count("SELECT COUNT(*) FROM bnf_enrollments WHERE tenant_id = ? AND worker_id = ? AND status = 'ACTIVE'", tenantId, workerId);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private Map<String, Object> json(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
