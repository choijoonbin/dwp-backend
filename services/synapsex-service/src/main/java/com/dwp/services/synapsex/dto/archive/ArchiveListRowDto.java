package com.dwp.services.synapsex.dto.archive;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * D1) GET /archive 응답 row
 * actions with status EXECUTED/FAILED/CANCELED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArchiveListRowDto {

    private Long actionId;
    private Long caseId;
    private String actionType;
    private String status;
    private String outcome;  // SUCCESS, FAILED
    private Instant executedAt;
    private String failureReason;
    private String docKey;  // for deep-link
    private Long partyId;   // for deep-link
}
