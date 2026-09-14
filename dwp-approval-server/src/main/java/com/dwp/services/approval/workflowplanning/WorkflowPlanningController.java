package com.dwp.services.approval.workflowplanning;

import com.dwp.core.common.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/admin/workflows")
public class WorkflowPlanningController {
    private final WorkflowPlanningFacade facade;
    public WorkflowPlanningController(WorkflowPlanningFacade facade) {this.facade=facade;}
    @PostMapping(value="/{workflowId}/versions/{versionId}/simulation",consumes="application/json",produces="application/json")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true,content=@Content(schema=@Schema(implementation=WorkflowPlanningBody.class)))
    public ApiResponse<WorkflowPlanningResult> simulate(@PathVariable UUID workflowId,@PathVariable UUID versionId,HttpServletRequest request) throws IOException {
        return ApiResponse.success(facade.simulate(request,workflowId,versionId,request.getInputStream().readNBytes(WorkflowPlanningProtocol.LOOKUP_MAX+1)));
    }
}
