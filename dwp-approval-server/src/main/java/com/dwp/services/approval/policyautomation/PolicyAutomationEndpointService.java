package com.dwp.services.approval.policyautomation;

import com.dwp.services.approval.security.ApprovalStepUpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.approval.policyautomation.PolicyAutomationModels.*;

@Service
public class PolicyAutomationEndpointService {
    private static final String BASE = "/v1/admin/policies/automation";

    private final PolicyAutomationService service;
    private final DelegationGovernanceService delegations;
    private final PolicyAutomationEndpointAuthority authority;

    public PolicyAutomationEndpointService(
            PolicyAutomationService service,
            DelegationGovernanceService delegations,
            PolicyAutomationEndpointAuthority authority) {
        this.service = service;
        this.delegations = delegations;
        this.authority = authority;
    }

    @Transactional(readOnly = true)
    public List<CalendarView> calendars() {
        authority.read();
        return service.calendars();
    }

    @Transactional(readOnly = true)
    public CalendarView calendar(UUID calendarId) {
        authority.read();
        return service.calendar(calendarId);
    }

    @Transactional(readOnly = true)
    public List<ChannelView> channels() {
        authority.read();
        return service.channels();
    }

    @Transactional(readOnly = true)
    public ChannelView channel(UUID channelId) {
        authority.read();
        return service.channel(channelId);
    }

    @Transactional(readOnly = true)
    public List<PolicyView> policies() {
        authority.read();
        return service.policies();
    }

    @Transactional(readOnly = true)
    public PolicyView policy(UUID policyId) {
        authority.read();
        return service.policy(policyId);
    }

    @Transactional(readOnly = true)
    public List<DelegationView> delegations() {
        authority.read();
        return delegations.delegations();
    }

    @Transactional(readOnly = true)
    public DelegationView delegation(UUID delegationId) {
        authority.read();
        return delegations.delegation(delegationId);
    }

    @Transactional(readOnly = true)
    public List<DelegationReviewView> delegationReviews(UUID delegationId) {
        authority.read();
        return delegations.reviews(delegationId);
    }

    @Transactional(readOnly = true)
    public List<DelegationAuditEvent> delegationAudit(UUID delegationId, int limit) {
        authority.read();
        return delegations.audit(delegationId, limit);
    }

    @Transactional(readOnly = true)
    public BusinessDeadline deadline(
            UUID calendarId, Instant start, long businessMinutes) {
        authority.read();
        return new BusinessDeadline(start, businessMinutes,
                service.addBusinessMinutes(calendarId, start, businessMinutes));
    }

    @Transactional
    public CalendarView saveCalendar(
            CalendarDraft input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/calendars/" + input.calendarId();
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "BUSINESS_CALENDAR", input.calendarId(), input.expectedVersion(),
                "PUT", path, input, headers);
        CalendarView result = service.saveCalendar(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ChannelView saveChannel(ChannelDraft input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/channels/" + input.channelId();
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "NOTIFICATION_CHANNEL", input.channelId(), input.expectedVersion(),
                "PUT", path, input, headers);
        ChannelView result = service.saveChannel(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public ChannelView observeChannel(
            UUID channelId, ChannelObservation input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/channels/" + channelId + "/observations";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "NOTIFICATION_CHANNEL", channelId, input.expectedVersion(),
                "POST", path, input, headers);
        ChannelView result = service.observeChannel(
                headers.idempotencyKey(), channelId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PolicyView savePolicy(PolicyDraft input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/rules/" + input.policyId() + "/draft";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "AUTOMATION_POLICY", input.policyId(), input.expectedVersion(),
                "PUT", path, input, headers);
        PolicyView result = service.savePolicyDraft(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public PolicyView publish(
            UUID policyId, PublishCommand input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/rules/" + policyId + "/publish";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY,
                "AUTOMATION_POLICY", policyId, input.expectedVersion(),
                "POST", path, input, headers);
        PolicyView result = service.publishPolicy(headers.idempotencyKey(), policyId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public DelegationReviewView reviewDelegation(
            UUID delegationId,
            DelegationReviewCommand input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/delegations/" + delegationId + "/reviews";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "DELEGATION_GOVERNANCE", delegationId,
                input.expectedDelegationVersion(), "POST", path, input, headers);
        DelegationReviewView result = delegations.review(
                headers.idempotencyKey(), delegationId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public DelegationView createDelegation(DelegationDraft input, ApprovalStepUpHeaders headers) {
        String path = BASE + "/delegations";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "DELEGATION_GOVERNANCE", input.delegationId(), input.expectedVersion(),
                "POST", path, input, headers);
        DelegationView result = delegations.create(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public DelegationView updateDelegation(UUID delegationId, DelegationDraft input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/delegations/" + delegationId;
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "DELEGATION_GOVERNANCE", delegationId, input.expectedVersion(),
                "PUT", path, input, headers);
        DelegationView result = delegations.update(headers.idempotencyKey(), delegationId, input);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public DelegationView changeDelegationState(UUID delegationId, DelegationStateCommand input,
            boolean scheduledOnly, ApprovalStepUpHeaders headers) {
        String suffix = scheduledOnly ? "/scheduled-cancel" : "/revoke";
        String path = BASE + "/delegations/" + delegationId + suffix;
        var permit = authority.begin(PolicyAutomationEndpointAuthority.UPDATE_CAPABILITY,
                "DELEGATION_GOVERNANCE", delegationId, input.expectedVersion(),
                "POST", path, input, headers);
        DelegationView result = delegations.revoke(headers.idempotencyKey(), delegationId, input, scheduledOnly);
        authority.complete(permit);
        return result;
    }

    @Transactional
    public DelegationKillSwitchView killSwitch(DelegationKillSwitchCommand input,
            ApprovalStepUpHeaders headers) {
        String path = BASE + "/delegations/kill-switch";
        var permit = authority.begin(PolicyAutomationEndpointAuthority.PUBLISH_CAPABILITY,
                "DELEGATION_CONTROL", input.killSwitchId(), input.expectedControlVersion(),
                "POST", path, input, headers);
        DelegationKillSwitchView result = delegations.killSwitch(headers.idempotencyKey(), input);
        authority.complete(permit);
        return result;
    }

}
