package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.approval.domain.ApprovalFormUserCandidateService;
import com.dwp.services.approval.domain.ApprovalFormUserCandidateService.Candidates;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ApprovalFormUserCandidatesController {
    private final ApprovalFormUserCandidateService service;

    public ApprovalFormUserCandidatesController(ApprovalFormUserCandidateService service) { this.service = service; }

    @GetMapping("/v1/catalog/forms/{formId}/versions/{formVersionId}/field-candidates")
    public ApiResponse<Candidates> work(@PathVariable String formId, @PathVariable String formVersionId,
            @RequestParam String schemaSha256, @RequestParam String fieldKey,
            @RequestParam(required = false) String groupKey, @RequestParam String query,
            @RequestParam(defaultValue = "10") int size, @RequestParam(required = false) String requestId, HttpServletRequest request) {
        return search(formId, formVersionId, schemaSha256, groupKey, fieldKey, query, size, false, requestId, request);
    }

    @GetMapping("/v1/admin/forms/{formId}/versions/{formVersionId}/field-candidates")
    public ApiResponse<Candidates> admin(@PathVariable String formId, @PathVariable String formVersionId,
            @RequestParam String schemaSha256, @RequestParam String fieldKey,
            @RequestParam(required = false) String groupKey, @RequestParam String query,
            @RequestParam(defaultValue = "10") int size, HttpServletRequest request) {
        return search(formId, formVersionId, schemaSha256, groupKey, fieldKey, query, size, true, null, request);
    }

    private ApiResponse<Candidates> search(String formId, String versionId, String hash, String group,
            String field, String query, int size, boolean admin, String requestId, HttpServletRequest request) {
        Set<String> allowed = admin ? Set.of("schemaSha256", "fieldKey", "groupKey", "query", "size")
                : Set.of("schemaSha256", "fieldKey", "groupKey", "query", "size", "requestId");
        if (request.getParameterMap().entrySet().stream().anyMatch(entry -> !allowed.contains(entry.getKey())
                || entry.getValue().length != 1)) throw invalid();
        return ApiResponse.success(service.search(uuid(formId), uuid(versionId), hash, group, field, query, size, admin,
                requestId == null ? null : uuid(requestId)));
    }

    private UUID uuid(String value) {
        if (value == null || !value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid();
        return UUID.fromString(value);
    }

    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "An exact form candidate request is required."); }
}
