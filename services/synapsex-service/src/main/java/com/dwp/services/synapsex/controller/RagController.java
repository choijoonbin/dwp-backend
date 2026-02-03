package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.core.exception.BaseException;
import com.dwp.core.common.ErrorCode;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.rag.RagDocumentDetailDto;
import com.dwp.services.synapsex.dto.rag.RagDocumentListDto;
import com.dwp.services.synapsex.dto.rag.RagSearchResultDto;
import com.dwp.services.synapsex.dto.rag.RegisterRagDocumentRequest;
import com.dwp.services.synapsex.service.rag.RagCommandService;
import com.dwp.services.synapsex.service.rag.RagQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    @PostMapping("/documents")
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
