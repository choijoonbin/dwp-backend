package com.dwp.services.synapsex.service.rag;

import com.dwp.services.synapsex.event.RagDocumentReadyForVectorizeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * RAG 문서 등록 트랜잭션 커밋 후 Aura 벡터화 트리거.
 * 커밋 후에 트리거해, Aura 콜백이 올 때 findById(docId)로 문서가 보이도록 함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagDocumentVectorizeTriggerListener {

    private final RagCommandService ragCommandService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRagDocumentReady(RagDocumentReadyForVectorizeEvent event) {
        Long tenantId = event.getTenantId();
        Long docId = event.getDocId();
        log.debug("RAG document committed, triggering vectorize: docId={} tenantId={}", docId, tenantId);
        ragCommandService.triggerVectorizeForDocId(tenantId, docId);
    }
}
