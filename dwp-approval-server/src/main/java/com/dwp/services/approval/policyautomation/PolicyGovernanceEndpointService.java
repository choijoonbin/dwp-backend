package com.dwp.services.approval.policyautomation;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyGovernanceModels.*;

@Service
public class PolicyGovernanceEndpointService {
    private static final String BASE = "/v1/admin/policies/automation/rules/";

    private final PolicyGovernanceService service;
    private final PolicyAutomationEndpointAuthority authority;

    public PolicyGovernanceEndpointService(
            PolicyGovernanceService service,
            PolicyAutomationEndpointAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    @Transactional(readOnly = true)
    public RevisionDiff diff(UUID policyId, UUID fromRevisionId, UUID toRevisionId) {
        authority.read();
        return service.diff(policyId, fromRevisionId, toRevisionId);
    }

    @Transactional(readOnly = true)
    public List<ReviewReceipt> reviews(UUID policyId) {
        authority.read();
        return service.reviews(policyId);
    }

    @Transactional(readOnly = true)
    public FreezeState freeze(UUID policyId) {
        authority.read();
        return service.freeze(policyId);
    }

    @Transactional
    public SimulationReceipt simulate(
            UUID policyId,
            SimulationCommand command,
            ApprovalStepUpHeaders headers) {
        String path = BASE + policyId + "/simulations";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "AUTOMATION_POLICY", policyId, command.expectedVersion(),
                "POST", path, command, headers);
        SimulationReceipt result = service.simulate(
                headers.idempotencyKey(), policyId, command);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ReviewReceipt review(
            UUID policyId,
            ReviewCommand command,
            ApprovalStepUpHeaders headers) {
        String path = BASE + policyId + "/reviews";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY,
                "AUTOMATION_POLICY", policyId, command.expectedVersion(),
                "POST", path, command, headers);
        ReviewReceipt result = service.review(headers.idempotencyKey(), policyId, command);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public FreezeState setFreeze(
            UUID policyId,
            FreezeCommand command,
            ApprovalStepUpHeaders headers) {
        String path = BASE + policyId + "/freeze";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY,
                "AUTOMATION_POLICY", policyId, command.expectedVersion(),
                "POST", path, command, headers);
        FreezeState result = service.setFreeze(headers.idempotencyKey(), policyId, command);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PolicyExport export(
            UUID policyId,
            ExportCommand command,
            ApprovalStepUpHeaders headers) {
        String path = BASE + policyId + "/exports";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY,
                "AUTOMATION_POLICY", policyId, command.expectedVersion(),
                "POST", path, command, headers);
        PolicyExport result = service.export(headers.idempotencyKey(), policyId, command);
        authority.complete(permit);
        return result;
    }
}
