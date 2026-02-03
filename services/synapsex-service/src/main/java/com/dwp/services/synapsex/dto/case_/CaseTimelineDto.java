package com.dwp.services.synapsex.dto.case_;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * GET /cases/{caseId}/timeline 응답
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseTimelineDto {

    private Long eventId;
    private String eventType;  // STATUS_CHANGE, ASSIGN, COMMENT_CREATE, ACTION_*
    private Instant createdAt;
    private Long actorUserId;
    private String actorAgentId;
    private String summary;
    private Object detail;  // status before/after, comment text, etc.
}
