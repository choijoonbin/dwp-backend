package com.dwp.services.synapsex.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * RAG 문서 상태 갱신 이벤트 (Phase 6).
 * 콜백으로 COMPLETED 수신 시 발행 — WebSocket/SSE에서 "규정 벡터화 완료" 알림 전송용.
 */
@Getter
public class RagDocumentStatusUpdatedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final Long docId;
    private final Long tenantId;
    private final String status;
    private final String message;

    public RagDocumentStatusUpdatedEvent(Object source, Long docId, Long tenantId, String status, String message) {
        super(source);
        this.docId = docId;
        this.tenantId = tenantId;
        this.status = status != null ? status : "";
        this.message = message;
    }
}
