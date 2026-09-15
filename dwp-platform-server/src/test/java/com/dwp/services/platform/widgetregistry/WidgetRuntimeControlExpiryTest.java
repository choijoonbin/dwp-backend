package com.dwp.services.platform.widgetregistry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dwp.core.exception.BaseException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WidgetRuntimeControlExpiryTest {
    private static final Instant NOW = Instant.parse("2026-09-15T04:00:00Z");

    @Test
    void mutationGuardUsesItsClockAndOnlyRepositoryQualifiedActiveControls() {
        WidgetRuntimeControlRepository controls = mock(WidgetRuntimeControlRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OffsetDateTime expectedNow = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(controls.findActiveDisabled(expectedNow)).thenReturn(List.of());

        new WidgetRegistryMutationGuard(controls, clock)
                .requireAllowed(null, "core.work", UUID.randomUUID(), null);

        verify(controls).findActiveDisabled(expectedNow);
    }

    @Test
    void repositoryQualifiedActiveControlStillFailsClosed() {
        WidgetRuntimeControlRepository controls = mock(WidgetRuntimeControlRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OffsetDateTime expectedNow = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        when(controls.findActiveDisabled(expectedNow)).thenReturn(List.of(
                WidgetRuntimeControl.builder()
                        .controlScope("CATALOG_MUTATIONS")
                        .targetType("GLOBAL")
                        .controlState("DISABLED")
                        .build()));

        assertThatThrownBy(() -> new WidgetRegistryMutationGuard(controls, clock)
                .requireAllowed(null, "core.work", UUID.randomUUID(), null))
                .isInstanceOf(BaseException.class);
    }
}
