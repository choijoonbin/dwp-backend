package com.dwp.services.platform.workplace.bookingorchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.TeamPlacementConstraint;
import static com.dwp.services.platform.workplace.bookingorchestration.WorkplaceBookingOrchestrationDtos.TeamPlacementConstraintEvidence;

abstract class WorkplaceBookingPersistenceSupport {
    protected final JdbcTemplate jdbc;
    protected final ObjectMapper objectMapper;

    protected WorkplaceBookingPersistenceSupport(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    protected String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Booking orchestration JSON could not be serialized.", exception);
        }
    }

    protected List<String> strings(String value) {
        if (value == null) return List.of();
        try {
            return Arrays.asList(objectMapper.readValue(value, String[].class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Persisted booking orchestration JSON is invalid.", exception);
        }
    }

    protected List<UUID> uuids(String value) {
        return strings(value).stream().map(UUID::fromString).toList();
    }

    protected List<TeamPlacementConstraint> placementConstraints(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Persisted team placement constraints are invalid.", exception);
        }
    }

    protected List<TeamPlacementConstraintEvidence> placementEvidence(String value) {
        if (value == null) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Persisted placement evidence is invalid.", exception);
        }
    }

    protected <E extends Enum<E>> List<E> enums(String value, Class<E> type) {
        return strings(value).stream().map(item -> Enum.valueOf(type, item)).toList();
    }

    protected static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    protected static Integer nullableInteger(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    protected static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) return value;
        return value.substring(0, maximum);
    }

    protected static String groupCsv(String verifiedGroupRefs) {
        return WorkplaceBookingOrchestrationGroupRefs.csv(verifiedGroupRefs);
    }
}
