package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/workforce/reference-data")
public class WorkforceReferenceController {

    private static final String CORRELATION_HEADER = "X-Correlation-ID";

    private final WorkforceReferenceService service;

    public WorkforceReferenceController(WorkforceReferenceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<WorkforceReferenceDtos.ReferenceCatalog>> catalogs(
            @RequestParam(defaultValue = "en") String locale) {
        return ApiResponse.success(service.catalogs(locale));
    }

    @PutMapping("/{catalogKey}/{code}")
    public ApiResponse<WorkforceReferenceDtos.ReferenceValue> update(
            @PathVariable String catalogKey,
            @PathVariable String code,
            @RequestParam(defaultValue = "en") String locale,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @Valid @RequestBody WorkforceReferenceDtos.UpdateReferenceValueRequest request) {
        return ApiResponse.success(service.update(
                catalogKey, code, locale, request, correlationId));
    }
}
