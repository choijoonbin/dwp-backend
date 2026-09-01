package com.dwp.services.notification.domain;

import com.dwp.services.notification.common.NotificationErrorCode;
import com.dwp.services.notification.common.NotificationException;
import com.dwp.services.notification.security.NotificationRequestContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationProducerOwnershipPolicyTest {

    private final NotificationProducerOwnershipPolicy policy =
            new NotificationProducerOwnershipPolicy(
                    "dwp-messaging-server=messaging,dwp-people-server=hcm|people");

    @Test
    void permitsOnlyTheProducerThatOwnsTheContractApp() {
        policy.requireOwnership(actor("dwp-messaging-server"), contract("messaging"));
        policy.requireAppOwnership(actor("dwp-messaging-server"), "MESSAGING");

        assertThatThrownBy(() -> policy.requireOwnership(
                actor("dwp-people-server"), contract("messaging")))
                .isInstanceOf(NotificationException.class)
                .satisfies(error -> assertThat(((NotificationException) error).errorCode())
                        .isEqualTo(NotificationErrorCode.FORBIDDEN));
    }

    @Test
    void rejectsNonInternalActorsEvenWhenTheSourceNameMatches() {
        NotificationRequestContext.Actor actor = new NotificationRequestContext.Actor(
                1L, 900018L, Set.of(), Set.of(), false, "dwp-messaging-server");

        assertThatThrownBy(() -> policy.requireOwnership(actor, contract("messaging")))
                .isInstanceOf(NotificationException.class);
    }

    @Test
    void parsesAliasesAndRejectsDuplicateProducerBindings() {
        assertThat(NotificationProducerOwnershipPolicy.parse(
                "dwp-people-server=HCM|people"))
                .containsEntry("dwp-people-server", Set.of("hcm", "people"));
        assertThatThrownBy(() -> NotificationProducerOwnershipPolicy.parse(
                "dwp-people-server=hcm,dwp-people-server=people"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationProducerOwnershipPolicy(""))
                .isInstanceOf(IllegalStateException.class);
    }

    private NotificationRequestContext.Actor actor(String sourceService) {
        return new NotificationRequestContext.Actor(
                1L, null, Set.of(), Set.of(), true, sourceService);
    }

    private TemplateContract contract(String ownerAppKey) {
        return new TemplateContract(
                UUID.randomUUID(), 0L, UUID.randomUUID(), 0L, null,
                "MESSAGING.DIRECT_MESSAGE", ownerAppKey, "NORMAL", "INFORMATIONAL",
                "ko-KR", "title", "preview", "body", Map.of());
    }
}
