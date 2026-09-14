package com.dwp.services.approval.workflowplanning;

import com.dwp.core.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/workflows")
public final class WorkflowPlanningSelectionController {
    private final WorkflowPlanningSelectionFacade facade;
    public WorkflowPlanningSelectionController(WorkflowPlanningSelectionFacade facade) {this.facade=facade;}
    @GetMapping(value="/{workflowId}/planning-selection",produces="application/json")
    public ApiResponse<WorkflowPlanningSelection> selection(@PathVariable UUID workflowId,@RequestParam(required=false) UUID formId,HttpServletRequest request) {
        return ApiResponse.success(facade.select(request,workflowId,formId));
    }
}
