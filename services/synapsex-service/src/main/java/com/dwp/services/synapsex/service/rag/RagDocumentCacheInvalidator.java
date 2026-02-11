package com.dwp.services.synapsex.service.rag;

/**
 * RAG 문서 벡터화 완료 시 해당 문서를 참조하는 에이전트 캐시 무효화 확장 포인트 (선택 사항).
 * 구현 빈을 등록하면 COMPLETED 콜백 시 {@link #invalidateCachesForDocument}가 호출됨.
 * 예: Redis Pub/Sub으로 Aura/에이전트에 무효화 신호 전달.
 */
public interface RagDocumentCacheInvalidator {

    /**
     * 지정 문서가 COMPLETED로 갱신되었을 때, 해당 문서를 참조하는 모든 에이전트 캐시를 무효화.
     *
     * @param docId    문서 ID
     * @param tenantId 테넌트 ID
     */
    void invalidateCachesForDocument(Long docId, Long tenantId);
}
