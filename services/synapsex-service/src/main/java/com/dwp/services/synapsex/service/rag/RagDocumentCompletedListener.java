package com.dwp.services.synapsex.service.rag;

import com.dwp.services.synapsex.event.RagDocumentStatusUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Phase 6+: RAG 문서 상태 갱신 이벤트 리스너.
 * COMPLETED 수신 시 해당 문서를 참조하는 에이전트 캐시 무효화를 위한 확장 포인트.
 * (선택) RagDocumentCacheInvalidator 빈이 있으면 호출하여 Aura/에이전트 측 캐시 무효화 신호 전달 가능.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagDocumentCompletedListener {

    private static final String STATUS_COMPLETED = "COMPLETED";

    private final ObjectProvider<RagDocumentCacheInvalidator> cacheInvalidatorProvider;

    @EventListener
    public void onRagDocumentStatusUpdated(RagDocumentStatusUpdatedEvent event) {
        if (!STATUS_COMPLETED.equals(event.getStatus())) {
            return;
        }
        Long docId = event.getDocId();
        Long tenantId = event.getTenantId();
        log.info("RAG document completed: docId={} tenantId={} — cache invalidation may be required for agents referencing this document.",
                docId, tenantId);
        cacheInvalidatorProvider.ifAvailable(invalidator -> {
            try {
                invalidator.invalidateCachesForDocument(docId, tenantId);
            } catch (Exception e) {
                log.warn("RAG cache invalidation failed for docId={}: {}", docId, e.getMessage());
            }
        });
    }
}
