package com.dwp.services.approval.api;

import com.dwp.core.common.ApiResponse;
import com.dwp.services.approval.domain.ApprovalDtos;
import com.dwp.services.approval.domain.ApprovalSearchService;
import com.dwp.services.approval.domain.ApprovalWorkDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ApprovalSearchController {
    private final ApprovalSearchService search;
    public ApprovalSearchController(ApprovalSearchService search) { this.search = search; }

    @GetMapping("/tasks/search")
    public ApiResponse<ApprovalWorkDtos.Page<ApprovalDtos.TaskSummary>> tasks(
            @RequestParam(defaultValue = "INBOX") ApprovalWorkDtos.TaskView view,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String priority, @RequestParam(required = false) UUID workflowId,
            @RequestParam(defaultValue = "ALL") ApprovalWorkDtos.DueFilter due,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "PRIORITY") ApprovalWorkDtos.Sort sort,
            @RequestParam(required = false) Integer minRiskScore) {
        return ApiResponse.success(search.tasks(view, new ApprovalWorkDtos.SearchFilter(query, status, priority, workflowId, due, page, size, sort, minRiskScore)));
    }

    @GetMapping("/requests/search")
    public ApiResponse<ApprovalWorkDtos.Page<ApprovalDtos.RequestSummary>> requests(
            @RequestParam(defaultValue = "SUBMITTED") ApprovalWorkDtos.RequestView view,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String priority, @RequestParam(required = false) UUID workflowId,
            @RequestParam(defaultValue = "ALL") ApprovalWorkDtos.DueFilter due,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "NEWEST") ApprovalWorkDtos.Sort sort) {
        return ApiResponse.success(search.requests(view, new ApprovalWorkDtos.SearchFilter(query, status, priority, workflowId, due, page, size, sort)));
    }
}
