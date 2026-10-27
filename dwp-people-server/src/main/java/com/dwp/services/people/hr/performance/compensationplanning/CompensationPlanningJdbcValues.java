package com.dwp.services.people.hr.performance.compensationplanning;

import com.dwp.services.people.hr.performance.typedartifact.CompensationArtifactContents.Proposal;
import com.dwp.services.people.hr.performance.typedartifact.PostedMoney;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Exact JDBC conversions used by compensation planning persistence. */
final class CompensationPlanningJdbcValues {

    static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static UUID uuidNullable(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static String money(BigDecimal value) {
        if (value.signum() == 0) return "0";
        return value.stripTrailingZeros().toPlainString();
    }

    static MapSqlParameterSource proposalParameters(
            long tenantId,
            UUID subjectId,
            UUID proposalId,
            long cycleInternalId,
            Proposal content,
            UUID correlationId,
            Instant now) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("proposalId", proposalId)
                .addValue("subjectId", subjectId)
                .addValue("now", timestamp(now))
                .addValue("correlationId", correlationId)
                .addValue("cycleInternalId", cycleInternalId)
                .addValue("workerId", content.workerPublicId())
                .addValue("assignmentId", content.assignmentPublicId())
                .addValue("componentCode", content.componentCode())
                .addValue("currency", content.currency())
                .addValue("amount", PostedMoney.decimal(content.amount()))
                .addValue("effectiveFrom", Date.valueOf(content.effectiveFrom()))
                .addValue("effectiveTo", date(content.effectiveTo()))
                .addValue("supersedesProposalId", content.supersedesProposalPublicId());
    }

    static JsonNode readPayload(ObjectMapper mapper, String json) {
        if (json == null) return null;
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted outbox payload is invalid", exception);
        }
    }
}
