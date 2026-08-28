package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class MessagingTenantPolicyGuard {

    private static final Set<String> DIRECT_CONVERSATION_TYPES = Set.of("DIRECT", "GROUP");

    private final MessagingQueryRepository queries;

    MessagingTenantPolicyGuard(MessagingQueryRepository queries) {
        this.queries = queries;
    }

    public void requireDirectMessagingEnabled(long tenantId) {
        if (!queries.policy(tenantId).directMessagesEnabled()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Direct messaging is disabled by the tenant policy.");
        }
    }

    public void requireMessageSendingEnabled(
            long tenantId,
            MessagingDtos.ConversationSummary conversation) {
        MessagingDtos.TenantPolicy policy = queries.policy(tenantId);
        if (DIRECT_CONVERSATION_TYPES.contains(conversation.conversationType())
                && !policy.directMessagesEnabled()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Direct messaging is disabled by the tenant policy.");
        }
        if ("SPACE".equals(conversation.visibility())
                && !policy.spaceMessagingEnabled()) {
            throw new BaseException(
                    ErrorCode.FORBIDDEN,
                    "Space messaging is disabled by the tenant policy.");
        }
    }
}
