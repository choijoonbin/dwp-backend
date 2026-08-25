package com.dwp.services.people.hr;

import com.dwp.core.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/workforce/operations")
public class WorkforceOperationsController {

    private final HrService service;

    public WorkforceOperationsController(HrService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<HrDtos.WorkforceOperationsOverview> overview() {
        return ApiResponse.success(service.operationsOverview());
    }
}
