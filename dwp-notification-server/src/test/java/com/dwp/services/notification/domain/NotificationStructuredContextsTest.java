package com.dwp.services.notification.domain;

import com.dwp.services.notification.domain.NotificationModels.DirectMaterializationRequest;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContext;
import com.dwp.services.notification.domain.NotificationModels.MaterializationContextKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStructuredContextsTest {

    @Test
    void canonicalizesExplicitContextsAndSynthesizesOnlySafeLegacyReferences() {
        List<MaterializationContext> explicit = NotificationStructuredContexts.explicit(List.of(
                context(MaterializationContextKind.TOPIC, "security-alert"),
                context(MaterializationContextKind.PROJECT, "project:cloud-migration")));

        List<MaterializationContext> contexts = NotificationStructuredContexts.withLegacy(
                request(explicit), explicit);

        assertThat(contexts)
                .extracting(context -> context.kind() + ":" + context.key())
                .containsExactly(
                        "CONVERSATION:messaging-conversation:11111111-1111-4111-8111-111111111111",
                        "PERSON:user:10",
                        "PROJECT:project:cloud-migration",
                        "TOPIC:security-alert");
        assertThat(contexts).allSatisfy(context -> {
            assertThat(context.matchable()).isTrue();
            assertThat(context.displayHint()).isNull();
        });
    }

    @Test
    void rejectsNonCanonicalKeysDuplicateReferencesAndPersonalHints() {
        assertThatThrownBy(() -> NotificationStructuredContexts.explicit(List.of(
                context(MaterializationContextKind.PROJECT, " project:one"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical");
        assertThatThrownBy(() -> NotificationStructuredContexts.explicit(List.of(
                context(MaterializationContextKind.PERSON, "person:alice@example.test"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not canonical");
        assertThatThrownBy(() -> NotificationStructuredContexts.explicit(List.of(
                context(MaterializationContextKind.TOPIC, "Security Alert"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not canonical");
        assertThatThrownBy(() -> NotificationStructuredContexts.explicit(List.of(
                context(MaterializationContextKind.PROJECT, "project:one"),
                context(MaterializationContextKind.PROJECT, "project:one"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> NotificationStructuredContexts.explicit(List.of(
                new MaterializationContext(
                        MaterializationContextKind.PERSON,
                        "user:10",
                        "Alice Kim",
                        true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-personal");
    }

    @Test
    void keepsTheFinalContextSetBoundedWhenLegacyReferencesAreAdded() {
        List<MaterializationContext> explicit = java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> context(
                        MaterializationContextKind.WORK_ITEM,
                        "work-item:" + index))
                .toList();

        assertThat(NotificationStructuredContexts.withLegacy(
                request(explicit), NotificationStructuredContexts.explicit(explicit)))
                .hasSize(NotificationStructuredContexts.MAXIMUM_CONTEXTS);
    }

    private MaterializationContext context(
            MaterializationContextKind kind,
            String key) {
        return new MaterializationContext(kind, key, null, true);
    }

    private DirectMaterializationRequest request(List<MaterializationContext> contexts) {
        return new DirectMaterializationRequest(
                UUID.randomUUID(),
                "messaging.message.sent.v1",
                1,
                "MESSAGING.DIRECT_MESSAGE",
                List.of(11L),
                "messaging-conversation:11111111-1111-4111-8111-111111111111",
                "ko-KR",
                "DIRECT",
                "user:10",
                "messaging-message:22222222-2222-4222-8222-222222222222",
                "/messages/direct?conversation=1",
                Instant.parse("2026-09-17T00:00:00Z"),
                null,
                false,
                contexts,
                Map.of());
    }
}
