package com.dwp.services.messaging.domain;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingTenantPolicyGuardTest {

    @Mock
    private MessagingQueryRepository queries;

    private MessagingTenantPolicyGuard guard;

    @BeforeEach
    void setUp() {
        guard = new MessagingTenantPolicyGuard(queries);
    }

    @Test
    void rejectsDirectConversationCreationWhenTenantDisablesDirectMessaging() {
        when(queries.policy(1)).thenReturn(policy(false, true));

        assertThatThrownBy(() -> guard.requireDirectMessagingEnabled(1))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void rejectsMessagesInExistingDirectConversationsWhenPolicyIsDisabled() {
        when(queries.policy(1)).thenReturn(policy(false, true));

        assertThatThrownBy(() -> guard.requireMessageSendingEnabled(
                1, conversation("DIRECT", "PRIVATE")))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void rejectsMessagesInExistingSmallGroupConversationsWhenPolicyIsDisabled() {
        when(queries.policy(1)).thenReturn(policy(false, true));

        assertThatThrownBy(() -> guard.requireMessageSendingEnabled(
                1, conversation("GROUP", "PRIVATE")))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void rejectsMessagesInSpaceConversationsWhenPolicyIsDisabled() {
        when(queries.policy(1)).thenReturn(policy(true, false));

        assertThatThrownBy(() -> guard.requireMessageSendingEnabled(
                1, conversation("CHANNEL", "SPACE")))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.FORBIDDEN));
    }

    @Test
    void leavesOrdinaryPrivateChannelsAvailableWhenDirectAndSpaceMessagingAreDisabled() {
        when(queries.policy(1)).thenReturn(policy(false, false));

        assertThatCode(() -> guard.requireMessageSendingEnabled(
                1, conversation("CHANNEL", "PRIVATE")))
                .doesNotThrowAnyException();
    }

    private MessagingDtos.TenantPolicy policy(boolean direct, boolean space) {
        return new MessagingDtos.TenantPolicy(
                direct, space, true, true, true, false, 1095, 100, 0);
    }

    private MessagingDtos.ConversationSummary conversation(String type, String visibility) {
        return new MessagingDtos.ConversationSummary(
                UUID.randomUUID(), "policy:test", type, "Policy test", null,
                visibility, "INTERNAL", null, null, "ACTIVE", 2, 0,
                false, false, null, OffsetDateTime.now(), 0);
    }
}
