package com.dwp.services.platform.dwaion;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformDwaionHandoffTest {

    @Test
    void optionalBindingRequiresAllExactOwnerFields() {
        assertThat(PlatformDwaionHandoff.Binding.optional(
                null, null, null, null, "MAIL.DRAFT.CREATE")).isNull();

        assertThatThrownBy(() -> PlatformDwaionHandoff.Binding.optional(
                UUID.randomUUID(), null, "MAIL.DRAFT.CREATE", 2L,
                "MAIL.DRAFT.CREATE"))
                .isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> PlatformDwaionHandoff.Binding.optional(
                UUID.randomUUID(), UUID.randomUUID(), "CALENDAR.EVENT.CREATE", 2L,
                "MAIL.DRAFT.CREATE"))
                .isInstanceOf(BaseException.class);
    }

    @Test
    void effectsDeriveOnlyFromTheReviewedOwnerAction() {
        assertEffect("CALENDAR.EVENT.CREATE", "CALENDAR", "EVENT_CREATE", "CONFIRMED");
        assertEffect("MAIL.DRAFT.CREATE", "MAIL", "DRAFT_CREATE", "DRAFT");
        assertEffect("SERVICE.REQUEST.CREATE", "SERVICE", "REQUEST_CREATE", "SUBMITTED");
    }

    @Test
    void boundHandoffRequiresAVerifiedAuthenticationSession() {
        assertThatThrownBy(() -> new PlatformDwaionHandoff.Identity(
                " ", UUID.randomUUID(), "WORKSPACE_MEMBER", "APP.ASK:VIEW"))
                .isInstanceOf(BaseException.class);
    }

    private void assertEffect(
            String actionKey, String domain, String operation, String status) {
        var binding = new PlatformDwaionHandoff.Binding(
                1, UUID.randomUUID(), UUID.randomUUID(), actionKey, 3);
        UUID resourceId = UUID.randomUUID();

        var effect = PlatformDwaionHandoff.Effect.forBinding(
                binding, resourceId, 0, status);

        assertThat(effect).isEqualTo(new PlatformDwaionHandoff.Effect(
                domain, operation, resourceId, 0, status));
    }
}
