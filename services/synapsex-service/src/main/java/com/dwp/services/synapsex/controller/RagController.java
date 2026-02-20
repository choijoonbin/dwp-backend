package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.rag.RagChunksCallbackRequest;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RagDocumentListDto;
import com.dwp.services.synapsex.dto.rag.RagSearchResultDto;
import com.dwp.services.synapsex.dto.rag.RagStatusCallbackRequest;
import com.dwp.services.synapsex.dto.rag.RagHybridSearchRequest;
import com.dwp.services.synapsex.dto.rag.RagHybridSearchResponse;
import com.dwp.services.synapsex.dto.rag.RechunkRequest;
import com.dwp.services.synapsex.dto.rag.RechunkResponse;
import com.dwp.services.synapsex.dto.rag.ChunkingStatusResponse;
import com.dwp.services.synapsex.dto.rag.RegisterRagDocumentRequest;
import com.dwp.services.synapsex.service.rag.RagCommandService;
import com.dwp.services.synapsex.service.rag.RagQueryService;
import com.dwp.services.synapsex.service.rag.RagSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Phase 3 RAG API
 */
@Slf4j
@RestController
@RequestMapping("/synapse/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagQueryService ragQueryService;
    private final RagCommandService ragCommandService;
    private final RagSearchService ragSearchService;

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
     * Aura RAG 청크 콜백 (전용).
     * POST /api/synapse/rag/chunks — { "rag_document_id", "chunks", "batch_index", "total_batches" } 수신 시 rag_chunk에 INSERT.
     * rag_document_id 유효성 검사 후 200 OK 반환.
     */
    @PostMapping("/chunks")
    public ApiResponse<Void> ragChunksCallback(@Valid @RequestBody RagChunksCallbackRequest request) {
        log.info("RAG chunks callback: rag_document_id={} batchIndex={} totalBatches={} chunksSize={}",
                request.getRagDocumentId(), request.getBatchIndex(), request.getTotalBatches(),
                request.getChunks() != null ? request.getChunks().size() : 0);
        ragCommandService.processChunksCallback(request);
        return ApiResponse.success(null);
    }

    /**
     * Phase 6: Aura RAG 상태 콜백.
     * POST /api/synapse/rag/status — { docId|rag_document_id, status, message, chunks } 수신 시 rag_document 갱신, chunks 있으면 rag_chunk 저장.
     */
    @PostMapping("/status")
    public ApiResponse<Void> ragStatusCallback(@Valid @RequestBody RagStatusCallbackRequest request) {
        log.info("RAG status callback endpoint hit: docId={} ragDocumentId={} status={} chunksPresent={} chunksSize={}",
                request.getDocId(), request.getRagDocumentId(), request.getStatus(),
                request.getChunks() != null, request.getChunks() != null ? request.getChunks().size() : 0);
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

    /**
     * Enterprise RAG Hybrid Search
     * POST /api/synapse/rag/search
     * RRF(Reciprocal Rank Fusion) 알고리즘 적용: Vector(7) : Keyword(3)
     */
    @PostMapping("/search")
    public ApiResponse<RagHybridSearchResponse> hybridSearch(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @Valid @RequestBody RagHybridSearchRequest request) {
        log.debug("RAG hybrid search: tenantId={} query={} strategy={}", 
                tenantId, request.getQuery(), request.getStrategy());
        RagHybridSearchResponse result = ragSearchService.search(tenantId, request);
        return ApiResponse.success(result);
    }

    /**
     * 재청킹 요청
     * POST /api/synapse/rag/documents/{docId}/rechunk
     */
    @PostMapping("/documents/{docId}/rechunk")
    public ApiResponse<RechunkResponse> rechunk(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long docId,
            @Valid @RequestBody RechunkRequest request) {
        log.info("Rechunk request: tenantId={} docId={} strategy={}", tenantId, docId, request.getStrategy());
        RechunkResponse result = ragCommandService.rechunk(tenantId, docId, request);
        return ApiResponse.success(result);
    }

    /**
     * 청킹 상태 조회
     * GET /api/synapse/rag/documents/{docId}/chunking-status
     */
    @GetMapping("/documents/{docId}/chunking-status")
    public ApiResponse<ChunkingStatusResponse> getChunkingStatus(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long docId) {
        ChunkingStatusResponse result = ragQueryService.getChunkingStatus(tenantId, docId)
                .orElseThrow(() -> new BaseException(ErrorCode.ENTITY_NOT_FOUND, "문서를 찾을 수 없습니다."));
        return ApiResponse.success(result);
    }
}
