package com.dwp.services.approval.routingdirectory;

import static com.dwp.services.approval.routingdirectory.WorkflowStudioModels.*;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class WorkflowStudioEndpointService {
    private static final String BASE = "/v1/admin/workflows/";
    private final WorkflowStudioService service;
    private final RoutingDirectoryEndpointAuthority authority;

    WorkflowStudioEndpointService(WorkflowStudioService service,
            RoutingDirectoryEndpointAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    @Transactional(readOnly = true)
    WorkflowStudio studio(UUID workflowId) {
        authority.read();
        return service.studio(workflowId);
    }

    @Transactional(readOnly = true)
    WorkflowDiff diff(UUID workflowId, int fromVersion, int toVersion) {
        authority.read();
        return service.diff(workflowId, fromVersion, toVersion);
    }

    @Transactional
    WorkflowStudio save(UUID workflowId, CanvasSave input, ApprovalStepUpHeaders headers) {
        String path = BASE + workflowId + "/studio-v3/canvas";
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY,
                "WORKFLOW", workflowId, input.expectedVersion(), "PUT", path, input, headers);
        WorkflowStudio result = service.save(workflowId, input, headers.idempotencyKey());
        authority.complete(permit);
        return result;
    }

    @Transactional
    DryRunResult dryRun(UUID workflowId, CanvasDryRun input, ApprovalStepUpHeaders headers) {
        String path = BASE + workflowId + "/studio-v3/dry-run";
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY,
                "WORKFLOW", workflowId, input.expectedVersion(), "POST", path, input, headers);
        DryRunResult result = service.dryRun(workflowId, input, headers.idempotencyKey());
        authority.complete(permit);
        return result;
    }

    @Transactional
    Retirement retire(UUID workflowId, RetireWorkflow input, ApprovalStepUpHeaders headers) {
        String path = BASE + workflowId + "/studio-v3/retire";
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY,
                "WORKFLOW", workflowId, input.expectedVersion(), "POST", path, input, headers);
        Retirement result = service.retire(workflowId, input, headers.idempotencyKey());
        authority.complete(permit);
        return result;
    }
}
