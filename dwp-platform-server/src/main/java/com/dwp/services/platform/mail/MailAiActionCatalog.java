package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static com.dwp.services.platform.mail.MailTypes.ProposalType;

final class MailAiActionCatalog {

    static final int CONTRACT_VERSION = 1;

    private static final Map<ProposalType, Policy> POLICIES = policies();

    private MailAiActionCatalog() {
    }

    static Policy validate(MailDtos.ActionProposal proposal) {
        Policy policy = POLICIES.get(proposal.type());
        if (policy == null) {
            throw new BaseException(ErrorCode.INVALID_STATE, "Unsupported AI mail action.");
        }
        if (proposal.actionContractVersion() != CONTRACT_VERSION) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The AI proposal uses an unsupported action contract version.");
        }
        if (!policy.resourceKey().equals(proposal.requiredResourceKey())
                || !policy.permissionCode().equals(proposal.requiredPermissionCode())
                || proposal.targetRoute() == null
                || !proposal.targetRoute().startsWith(policy.routePrefix())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The AI proposal target does not match the governed action policy.");
        }
        if (riskRank(proposal.riskLevel()) < riskRank(policy.minimumRisk())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The AI proposal risk is below the governed action policy floor.");
        }
        if (!proposal.proposedPayload().keySet().containsAll(policy.requiredPayloadFields())) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The AI proposal payload does not satisfy the governed action contract.");
        }
        if (!Boolean.TRUE.equals(proposal.proposedPayload().get("requiresConfirmation"))) {
            throw new BaseException(
                    ErrorCode.INVALID_STATE,
                    "The AI proposal must require explicit confirmation.");
        }
        return policy;
    }

    static Set<ProposalType> supportedActions() {
        return POLICIES.keySet();
    }

    private static Map<ProposalType, Policy> policies() {
        EnumMap<ProposalType, Policy> policies = new EnumMap<>(ProposalType.class);
        policies.put(
                ProposalType.DRAFT_REPLY,
                new Policy(
                        "APP.MAIL", "CREATE", "/mail/", "LOW", false,
                        Set.of("tone", "language", "requiresConfirmation")));
        policies.put(
                ProposalType.CREATE_CALENDAR_EVENT,
                new Policy(
                        "APP.CALENDAR", "CREATE", "/calendar/", "MEDIUM", true,
                        Set.of("durationMinutes", "timeZone", "requiresConfirmation")));
        policies.put(
                ProposalType.CREATE_LEAVE_REQUEST,
                new Policy(
                        "APP.HCM", "VIEW", "/hr/", "HIGH", true,
                        Set.of("durationDays", "requiresConfirmation")));
        policies.put(
                ProposalType.CREATE_TASK,
                new Policy(
                        "APP.WORK", "UPDATE", "/work", "MEDIUM", true,
                        Set.of("priority", "requiresConfirmation")));
        policies.put(
                ProposalType.ESCALATE_NOTIFICATION,
                new Policy(
                        "APP.MAIL", "UPDATE", "/mail/", "LOW", false,
                        Set.of("channel", "urgency", "requiresConfirmation")));
        return Collections.unmodifiableMap(policies);
    }

    private static int riskRank(String risk) {
        return switch (risk) {
            case "LOW" -> 0;
            case "MEDIUM" -> 1;
            case "HIGH" -> 2;
            default -> throw new BaseException(
                    ErrorCode.INVALID_STATE, "Unsupported AI mail action risk.");
        };
    }

    record Policy(
            String resourceKey,
            String permissionCode,
            String routePrefix,
            String minimumRisk,
            boolean crossApplication,
            Set<String> requiredPayloadFields) {

        Policy {
            requiredPayloadFields = Set.copyOf(requiredPayloadFields);
        }
    }
}
