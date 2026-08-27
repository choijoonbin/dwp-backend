package com.dwp.services.messaging.appearance;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.messaging.security.MessagingRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingDisplayPreferenceServiceTest {

    @Mock
    private MessagingDisplayPreferenceRepository repository;

    @AfterEach
    void clearContext() {
        MessagingRequestContext.clear();
    }

    @Test
    void directConversationUsesConversationalDefaults() {
        UUID conversationId = UUID.randomUUID();
        subject();
        when(repository.conversationContext(7, 42, conversationId))
                .thenReturn(Optional.of(new MessagingDisplayPreferenceRepository.ConversationContext(
                        "DIRECT", "INTERNAL")));
        when(repository.global(7, 42)).thenReturn(Optional.empty());
        when(repository.conversation(7, 42, conversationId)).thenReturn(Optional.empty());

        MessagingDisplayDtos.ConversationDisplayPreference result = service()
                .conversationPreference(conversationId);

        assertThat(result.effectiveLayoutMode()).isEqualTo("CONVERSATIONAL");
        assertThat(result.effectiveDensity()).isEqualTo("COMFORTABLE");
        assertThat(result.effectiveTheme()).isEqualTo("DEFAULT");
        assertThat(result.policyLocked()).isFalse();
        assertThat(result.version()).isZero();
    }

    @Test
    void structuredRestrictedConversationOverridesPersonalPresentation() {
        UUID conversationId = UUID.randomUUID();
        subject();
        when(repository.conversationContext(7, 42, conversationId))
                .thenReturn(Optional.of(new MessagingDisplayPreferenceRepository.ConversationContext(
                        "INCIDENT", "RESTRICTED")));
        when(repository.global(7, 42)).thenReturn(Optional.of(
                new MessagingDisplayPreferenceRepository.GlobalRow(
                        "CONVERSATIONAL", "COMPACT", "SAGE", false, "ALWAYS", false, 4)));
        when(repository.conversation(7, 42, conversationId)).thenReturn(Optional.of(
                new MessagingDisplayPreferenceRepository.ConversationRow(
                        "CONVERSATIONAL", "COMFORTABLE", "ROSE", 2)));

        MessagingDisplayDtos.ConversationDisplayPreference result = service()
                .conversationPreference(conversationId);

        assertThat(result.effectiveLayoutMode()).isEqualTo("COLLABORATIVE");
        assertThat(result.effectiveTheme()).isEqualTo("DEFAULT");
        assertThat(result.policyLocked()).isTrue();
        assertThat(result.policyReason()).isEqualTo("RESTRICTED_CONVERSATION");
        assertThat(result.showAvatars()).isFalse();
        assertThat(result.timestampMode()).isEqualTo("ALWAYS");
    }

    @Test
    void tenantThemeAllowListIsEnforced() {
        subject();
        when(repository.policy(7)).thenReturn(policy(List.of("DEFAULT")));

        assertThatThrownBy(() -> service().updateDisplayPreference(
                new MessagingDisplayDtos.UpdateDisplayPreferenceRequest(
                        "AUTO", "COMFORTABLE", "ROSE", true, "SMART", true, 0)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INVALID_INPUT_VALUE));
    }

    @Test
    void globalPreferenceUsesOptimisticVersionAndWritesAuditEvidence() {
        subject();
        MessagingDisplayPreferenceRepository.GlobalRow saved =
                new MessagingDisplayPreferenceRepository.GlobalRow(
                        "AUTO", "COMPACT", "MIST", true, "SMART", false, 1);
        when(repository.policy(7)).thenReturn(policy(List.of("DEFAULT", "MIST")));
        when(repository.insertGlobal(7, 42, new MessagingDisplayPreferenceRepository.GlobalRow(
                "AUTO", "COMPACT", "MIST", true, "SMART", false, 0)))
                .thenReturn(1);
        when(repository.global(7, 42)).thenReturn(Optional.of(saved));

        MessagingDisplayDtos.DisplayPreference result = service().updateDisplayPreference(
                new MessagingDisplayDtos.UpdateDisplayPreferenceRequest(
                        "auto", "compact", "mist", true, "smart", false, 0));

        assertThat(result.version()).isEqualTo(1);
        assertThat(result.theme()).isEqualTo("MIST");
        verify(repository).auditGlobal(7, 42, 1);
    }

    @Test
    void stalePreferenceVersionFailsClosed() {
        subject();
        when(repository.policy(7)).thenReturn(policy(List.of("DEFAULT")));
        when(repository.updateGlobal(
                7,
                42,
                new MessagingDisplayPreferenceRepository.GlobalRow(
                        "AUTO", "COMFORTABLE", "DEFAULT", true, "SMART", true, 3),
                3)).thenReturn(0);

        assertThatThrownBy(() -> service().updateDisplayPreference(
                new MessagingDisplayDtos.UpdateDisplayPreferenceRequest(
                        "AUTO", "COMFORTABLE", "DEFAULT", true, "SMART", true, 3)))
                .isInstanceOfSatisfying(BaseException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RESOURCE_CONFLICT));
    }

    private MessagingDisplayPreferenceService service() {
        return new MessagingDisplayPreferenceService(repository);
    }

    private void subject() {
        MessagingRequestContext.set(new MessagingRequestContext.Subject(
                42, 7, UUID.randomUUID(), "Test User",
                Set.of("WORKSPACE_MEMBER"), Set.of("APP.MESSAGING:READ"), Set.of()));
    }

    private MessagingDisplayDtos.AppearancePolicy policy(List<String> themes) {
        return new MessagingDisplayDtos.AppearancePolicy(themes, false, false, 1);
    }
}
