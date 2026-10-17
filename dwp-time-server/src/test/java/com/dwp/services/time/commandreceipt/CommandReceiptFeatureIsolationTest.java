package com.dwp.services.time.commandreceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.services.time.api.HrisTimeProblemWriter;
import com.dwp.services.time.close.TimeCloseActivationGate;
import com.dwp.services.time.close.TimeCloseCommandReceiptProvider;
import com.dwp.services.time.close.TimeCloseReceiptOwnership;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CommandReceiptFeatureIsolationTest {

    @Test
    void sharedHttpBoundaryIsAbsentWhenBothOwningFeaturesAreOff() {
        baseRunner()
                .withPropertyValues(
                        "dwp.hris.time.timecard.enabled=false",
                        "dwp.hris.time.close.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CommandReceiptController.class);
                    assertThat(context).doesNotHaveBean(CommandReceiptAccessFilter.class);
                });
    }

    @Test
    void closeOnlyBootRegistersSharedBoundaryAndCloseProviderWithoutTimecard() {
        CommandReceiptAuthorityProvider closeAuthority =
                mock(CommandReceiptAuthorityProvider.class);
        when(closeAuthority.providerId()).thenReturn("close");
        TimeCloseReceiptOwnership ownership = mock(TimeCloseReceiptOwnership.class);
        TimeCloseActivationGate activation = ignored -> false;
        TimeCloseCommandReceiptProvider closeProvider =
                new TimeCloseCommandReceiptProvider(ownership, activation);
        CommandReceiptProviderRegistry registry = new CommandReceiptProviderRegistry(
                List.of(closeProvider), ignored -> { }, Duration.ofSeconds(1));

        baseRunner()
                .withBean(CommandReceiptAuthorityProvider.class, () -> closeAuthority)
                .withBean(TimeCloseReceiptOwnership.class, () -> ownership)
                .withBean(TimeCloseActivationGate.class, () -> activation)
                .withBean(TimeCloseCommandReceiptProvider.class, () -> closeProvider)
                .withBean(CommandReceiptProviderRegistry.class, () -> registry)
                .withPropertyValues(
                        "dwp.hris.time.timecard.enabled=false",
                        "dwp.hris.time.close.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CommandReceiptController.class);
                    assertThat(context).hasSingleBean(CommandReceiptAccessFilter.class);
                    assertThat(context).hasSingleBean(TimeCloseCommandReceiptProvider.class);
                    assertThat(context.getBeanNamesForType(
                            com.dwp.services.time.timecard.TimecardCommandReceiptProvider.class))
                            .isEmpty();
                });
    }

    private static ApplicationContextRunner baseRunner() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> mapper)
                .withBean(HrisTimeProblemWriter.class,
                        () -> new HrisTimeProblemWriter(mapper))
                .withBean(CommandReceiptAuditSink.class, () -> ignored -> { })
                .withUserConfiguration(
                        CommandReceiptController.class,
                        CommandReceiptAccessFilter.class);
    }
}
