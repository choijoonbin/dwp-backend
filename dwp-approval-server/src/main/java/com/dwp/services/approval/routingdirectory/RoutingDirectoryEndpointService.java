package com.dwp.services.approval.routingdirectory;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.routingdirectory.RoutingDirectoryModels.*;

@Service
public class RoutingDirectoryEndpointService {
    private static final String BASE = "/v1/admin/workflows/routing-directory";

    private final RoutingDirectoryService service;
    private final RoutingDirectoryEndpointAuthority authority;

    public RoutingDirectoryEndpointService(
            RoutingDirectoryService service,
            RoutingDirectoryEndpointAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    @Transactional(readOnly = true)
    public List<GroupView> groups() {
        authority.read();
        return service.groups();
    }

    @Transactional(readOnly = true)
    public GroupView group(UUID groupId) {
        authority.read();
        return service.group(groupId);
    }

    @Transactional(readOnly = true)
    public List<ResolverView> resolvers() {
        authority.read();
        return service.resolvers();
    }

    @Transactional(readOnly = true)
    public ResolverView resolver(UUID resolverId) {
        authority.read();
        return service.resolver(resolverId);
    }

    @Transactional(readOnly = true)
    public Resolution resolve(UUID groupId, Instant effectiveAt) {
        authority.read();
        return service.resolve(groupId, effectiveAt);
    }

    @Transactional(readOnly = true)
    public RetireImpact impact(UUID groupId) {
        authority.read();
        return service.retirementImpact(groupId);
    }

    @Transactional
    public ResolverView saveResolver(ResolverDraft input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/resolvers/" + input.resolverId();
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY,
                "ROUTING_RESOLVER", input.resolverId(), input.expectedVersion(),
                "PUT", path, input, headers);
        ResolverView result = service.saveResolver(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ResolverView observeResolver(
            UUID resolverId, SourceObservation input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/resolvers/" + resolverId + "/observations";
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY,
                "ROUTING_RESOLVER", resolverId, input.expectedVersion(),
                "POST", path, input, headers);
        ResolverView result = service.observeResolver(
                headers.idempotencyKey(), resolverId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public GroupView saveGroup(GroupDraft input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/groups/" + input.groupId();
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY,
                "ROUTING_GROUP", input.groupId(), input.expectedVersion(),
                "PUT", path, input, headers);
        GroupView result = service.saveGroup(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public GroupView activate(
            UUID groupId, long expectedVersion, ApprovalStepUpHeaders headers) {
        String path = BASE + "/groups/" + groupId + "/publish";
        ActivationCommand input = new ActivationCommand(expectedVersion);
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY,
                "ROUTING_GROUP", groupId, expectedVersion, "POST", path, input, headers);
        GroupView result = service.activateGroup(
                headers.idempotencyKey(), groupId, expectedVersion);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public Usage recordUsage(Usage input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/groups/" + input.groupId() + "/usages";
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.UPDATE_CAPABILITY,
                "ROUTING_GROUP", input.groupId(), input.expectedGroupVersion(),
                "POST", path, input, headers);
        Usage result = service.recordUsage(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public GroupView retire(
            UUID groupId, RetirementCommand input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/groups/" + groupId + "/retire";
        var permit = authority.begin(RoutingDirectoryEndpointAuthority.PUBLISH_CAPABILITY,
                "ROUTING_GROUP", groupId, input.expectedVersion(),
                "POST", path, input, headers);
        GroupView result = service.retireGroup(headers.idempotencyKey(), groupId,
                input.expectedVersion(), input.acknowledgedImpact());
        authority.complete(permit);
        return result;
    }

}
