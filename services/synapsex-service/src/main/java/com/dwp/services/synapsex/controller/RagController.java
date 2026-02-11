package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RagDocumentListDto;
import com.dwp.services.synapsex.dto.rag.RagSearchResultDto;
import com.dwp.services.synapsex.dto.rag.RagStatusCallbackRequest;
import com.dwp.services.synapsex.dto.rag.RegisterRagDocumentRequest;
import com.dwp.services.synapsex.service.rag.RagCommandService;
import com.dwp.services.synapsex.service.rag.RagQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Phase 3 RAG API
 */
@RestController
@RequestMapping("/synapse/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagQueryService ragQueryService;
    private final RagCommandService ragCommandService;

    @GetMapping("/documents")
    public ApiResponse<PageResponse<RagDocumentListDto>> listDocuments(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<RagDocumentListDto> result = ragQueryService.listDocuments(tenantId, status, page, size);
        return ApiResponse.success(result);
    }

    /**
     * 규정집 등록 (로컬 파일 업로드). MultipartFile 수신 → 로컬 저장 → DB 기록 → Aura 벡터화 트리거.
     * 파일명 중복 방지: UUID_원본파일명. 저장 경로: storage.local.path (서버 기동 시 자동 생성).
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<RagDocumentDetailDto> uploadDocument(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "docType", required = false) String docType) {
        RagDocumentDetailDto dto = ragCommandService.registerDocumentFromFile(tenantId, file, title, docType);
        return ApiResponse.success(dto);
    }

    /** S3/URL 등 메타데이터만 등록 시 (기존 JSON body). 로컬 파일은 POST /documents multipart 사용 */
    @PostMapping(value = "/documents/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<RagDocumentDetailDto> registerDocument(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @Valid @RequestBody RegisterRagDocumentRequest request) {
        RagDocumentDetailDto dto = ragCommandService.registerDocument(tenantId, request);
        return ApiResponse.success(dto);
    }

    @GetMapping("/documents/{docId}")
    public ApiResponse<RagDocumentDetailDto> getDocumentDetail(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long docId) {
        RagDocumentDetailDto dto = ragQueryService.getDocumentDetail(tenantId, docId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없습니다."));
        return ApiResponse.success(dto);
    }

    /**
     * Phase 6: Aura RAG 상태 콜백.
     * POST /api/synapse/rag/status — { docId, status, message } 수신 시 rag_document 갱신, 규정 벡터화 완료 시 WebSocket/SSE 알림 준비.
     */
    @PostMapping("/status")
    public ApiResponse<Void> ragStatusCallback(@Valid @RequestBody RagStatusCallbackRequest request) {
        ragCommandService.handleStatusCallback(request);
        return ApiResponse.success(null);
    }

    @GetMapping("/search")
    public ApiResponse<PageResponse<RagSearchResultDto>> search(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PageResponse<RagSearchResultDto> result = ragQueryService.searchChunks(tenantId, q, page, size, sort);
        return ApiResponse.success(result);
    }
}
