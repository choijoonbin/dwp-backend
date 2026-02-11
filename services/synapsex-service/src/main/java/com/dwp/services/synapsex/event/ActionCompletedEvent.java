package com.dwp.services.synapsex.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 조치 완료 이벤트 — DB 커밋 직후 Redis 발행 및 Aura 웹훅용.
 * Backend Single Source of Truth: 모든 DB 갱신은 ActionCommandService에서 완결된 뒤 발행.
 */
@Getter
public class ActionCompletedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final Long tenantId;
    private final Long caseId;
    private final Long actionId;
    private final String actionType;
    private final String caseIdString;
    private final String requestId;
    private final String executorId;
    private final boolean approved;
    private final Long historyId;
    private final int fiDocUpdated;

    public ActionCompletedEvent(Object source, Long tenantId, Long caseId, Long actionId, String actionType,
                                String caseIdString, String requestId, String executorId, boolean approved,
                                Long historyId, int fiDocUpdated) {
        super(source);
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.actionId = actionId;
        this.actionType = actionType != null ? actionType : (approved ? "APPROVE" : "REJECT");
        this.caseIdString = caseIdString != null && !caseIdString.isBlank() ? caseIdString : (caseId != null ? String.valueOf(caseId) : "");
        this.requestId = requestId != null ? requestId : (actionId != null ? "action-" + actionId : "");
        this.executorId = executorId;
        this.approved = approved;
        this.historyId = historyId != null ? historyId : 0L;
        this.fiDocUpdated = fiDocUpdated;
    }
}
