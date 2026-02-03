package com.dwp.services.synapsex.controller;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.constant.HeaderConstants;
import com.dwp.services.synapsex.dto.common.PageResponse;
import com.dwp.services.synapsex.dto.dictionary.DictionaryTermDto;
import com.dwp.services.synapsex.dto.dictionary.DictionaryTermUpsertRequest;
import com.dwp.services.synapsex.service.dictionary.DictionaryCommandService;
import com.dwp.services.synapsex.service.dictionary.DictionaryQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Phase 3 Dictionary API
 */
@RestController
@RequestMapping("/synapse/dictionary")
@RequiredArgsConstructor
public class DictionaryController {

    private final DictionaryQueryService dictionaryQueryService;
    private final DictionaryCommandService dictionaryCommandService;

    @GetMapping
    public ApiResponse<PageResponse<DictionaryTermDto>> listTerms(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        PageResponse<DictionaryTermDto> result = dictionaryQueryService.listTerms(tenantId, category, page, size, sort);
        return ApiResponse.success(result);
    }

    @PostMapping
    public ApiResponse<DictionaryTermDto> createTerm(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @Valid @RequestBody DictionaryTermUpsertRequest request) {
        DictionaryTermDto dto = dictionaryCommandService.create(tenantId, request);
        return ApiResponse.success(dto);
    }

    @PutMapping("/{termId}")
    public ApiResponse<DictionaryTermDto> updateTerm(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long termId,
            @Valid @RequestBody DictionaryTermUpsertRequest request) {
        DictionaryTermDto dto = dictionaryCommandService.update(tenantId, termId, request);
        return ApiResponse.success(dto);
    }

    @DeleteMapping("/{termId}")
    public ApiResponse<Void> deleteTerm(
            @RequestHeader(HeaderConstants.X_TENANT_ID) Long tenantId,
            @PathVariable Long termId) {
        dictionaryCommandService.delete(tenantId, termId);
        return ApiResponse.success(null);
    }
}
