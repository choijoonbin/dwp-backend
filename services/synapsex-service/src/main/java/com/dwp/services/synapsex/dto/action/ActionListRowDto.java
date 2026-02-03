package com.dwp.services.synapsex.dto.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * C1) GET /actions 응답 row
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionListRowDto {

    private Long actionId;
    private Long caseId;
    private String actionType;
    private String status;
    private Instant createdAt;
    private Instant executedAt;
    private String outcome;  // SUCCESS, FAILED, etc
    private String failureReason;
}
