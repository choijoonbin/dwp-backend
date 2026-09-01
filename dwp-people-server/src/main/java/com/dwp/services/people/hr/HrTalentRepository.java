package com.dwp.services.people.hr;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

final class HrTalentRepository {

    private final JdbcTemplate jdbc;

    HrTalentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<HrDtos.Journey> activeJourneys(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT instance.public_id, template.name, template.journey_type,
                       instance.progress_percent, instance.target_date, instance.status
                  FROM tal_journey_instances instance
                  JOIN tal_journey_templates template
                    ON template.tenant_id = instance.tenant_id
                   AND template.journey_template_id = instance.journey_template_id
                 WHERE instance.tenant_id = ? AND instance.worker_id = ?
                   AND instance.status NOT IN ('COMPLETED', 'CANCELLED')
                 ORDER BY instance.target_date NULLS LAST, instance.created_at DESC
                 LIMIT 5
                """, (result, ignored) -> new HrDtos.Journey(
                result.getObject("public_id", UUID.class), result.getString("name"),
                result.getString("journey_type"), result.getInt("progress_percent"),
                result.getObject("target_date", LocalDate.class), result.getString("status")),
                tenantId, workerId);
    }

    List<HrDtos.Journey> journeys(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT instance.public_id, template.name, template.journey_type,
                       instance.progress_percent, instance.target_date, instance.status
                  FROM tal_journey_instances instance
                  JOIN tal_journey_templates template
                    ON template.tenant_id = instance.tenant_id
                   AND template.journey_template_id = instance.journey_template_id
                 WHERE instance.tenant_id = ? AND instance.worker_id = ?
                 ORDER BY instance.status, instance.target_date NULLS LAST
                """, (result, ignored) -> new HrDtos.Journey(
                result.getObject("public_id", UUID.class), result.getString("name"),
                result.getString("journey_type"), result.getInt("progress_percent"),
                result.getObject("target_date", LocalDate.class), result.getString("status")),
                tenantId, workerId);
    }

    List<HrDtos.Goal> goals(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT public_id, title, goal_type, progress_percent,
                       due_date, status, version
                  FROM tal_goals
                 WHERE tenant_id = ? AND worker_id = ?
                   AND status <> 'CANCELLED'
                 ORDER BY due_date NULLS LAST, created_at DESC
                """, (result, ignored) -> new HrDtos.Goal(
                result.getObject("public_id", UUID.class), result.getString("title"),
                result.getString("goal_type"), result.getInt("progress_percent"),
                result.getObject("due_date", LocalDate.class), result.getString("status"),
                result.getLong("version")), tenantId, workerId);
    }

    List<HrDtos.Learning> learning(Long tenantId, long workerId) {
        return jdbc.query("""
                SELECT public_id, title, provider_name, required,
                       progress_percent, due_date, status
                  FROM tal_learning_assignments
                 WHERE tenant_id = ? AND worker_id = ?
                   AND status NOT IN ('COMPLETED', 'WAIVED', 'EXPIRED')
                 ORDER BY required DESC, due_date NULLS LAST
                """, (result, ignored) -> new HrDtos.Learning(
                result.getObject("public_id", UUID.class), result.getString("title"),
                result.getString("provider_name"), result.getBoolean("required"),
                result.getInt("progress_percent"),
                result.getObject("due_date", LocalDate.class), result.getString("status")),
                tenantId, workerId);
    }

    boolean updateGoal(
            Long tenantId, long workerId, UUID goalId,
            HrDtos.UpdateGoalRequest request, Long actorId) {
        return jdbc.update("""
                UPDATE tal_goals
                   SET progress_percent = ?, status = ?, version = version + 1,
                       updated_at = CURRENT_TIMESTAMP, updated_by = ?
                 WHERE tenant_id = ? AND worker_id = ? AND public_id = ?
                   AND version = ? AND status NOT IN ('COMPLETED', 'CANCELLED')
                """, request.progressPercent(), request.status(), actorId,
                tenantId, workerId, goalId, request.version()) == 1;
    }

    long activeGoals(Long tenantId, long workerId) {
        return count("SELECT COUNT(*) FROM tal_goals WHERE tenant_id = ? AND worker_id = ? AND status IN ('ACTIVE','AT_RISK')", tenantId, workerId);
    }

    long requiredLearning(Long tenantId, long workerId) {
        return count("SELECT COUNT(*) FROM tal_learning_assignments WHERE tenant_id = ? AND worker_id = ? AND required AND status IN ('ASSIGNED','IN_PROGRESS')", tenantId, workerId);
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }
}
