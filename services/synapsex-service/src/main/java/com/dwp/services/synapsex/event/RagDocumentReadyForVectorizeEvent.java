package com.dwp.services.synapsex.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * RAG 문서 등록 커밋 후 벡터화 트리거용 이벤트.
 * 트랜잭션 커밋 후에 발행해, Aura 콜백이 findById(docId)로 문서를 찾을 수 있도록 함.
 */
@Getter
public class RagDocumentReadyForVectorizeEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final Long tenantId;
    private final Long docId;

    public RagDocumentReadyForVectorizeEvent(Object source, Long tenantId, Long docId) {
        super(source);
        this.tenantId = tenantId;
        this.docId = docId;
    }
}
