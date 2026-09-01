package com.dwp.services.approval.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class ApprovalCommandRepository extends ApprovalCommandManagementRepository {
    public ApprovalCommandRepository(
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        super(jdbc, objectMapper);
    }

    record RuntimeStep(
            String key,
            String name,
            String mode,
            String candidateRole,
            int slaMinutes) {
    }


    public record DecisionResult(String decision, String requestStatus) {
    }
}
