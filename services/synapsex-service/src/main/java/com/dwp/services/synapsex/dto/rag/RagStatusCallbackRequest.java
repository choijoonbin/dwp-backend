package com.dwp.services.synapsex.dto.rag;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aura → Synapse RAG 상태 콜백 요청 (Phase 6).
 * POST /api/synapse/rag/status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagStatusCallbackRequest {

    @NotNull(message = "docId는 필수입니다.")
    private Long docId;

    /** COMPLETED, FAILED, PROCESSING 등 (sys_codes RAG_DOCUMENT_STATUS) */
    private String status;

    /** 오류 시 메시지 (선택) */
    private String message;
}
