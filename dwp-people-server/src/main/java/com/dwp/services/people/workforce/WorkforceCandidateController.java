package com.dwp.services.people.workforce;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/workforce/organization/candidates")
public class WorkforceCandidateController {

    private final WorkforceCandidateService service;

    public WorkforceCandidateController(WorkforceCandidateService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listOrganizationCandidates")
    public ApiResponse<List<WorkforceCandidateDtos.OrganizationCandidate>> list() {
        return ApiResponse.success(service.list());
    }
}
