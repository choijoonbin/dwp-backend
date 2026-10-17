package com.dwp.services.time.commandreceipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dwp.services.time.api.HrisTimeProblemWriter;
import com.dwp.services.time.close.TimeCloseActivationGate;
import com.dwp.services.time.close.TimeCloseCommandReceiptProvider;
import com.dwp.services.time.close.TimeCloseReceiptOwnership;
import com.dwp.services.time.timecard.TimecardCommandReceiptProvider;
import com.dwp.services.time.timecard.TimecardOwnerAuthoritySource;
import com.dwp.services.time.timecard.TimecardRepository;
import com.dwp.services.time.timecard.TimecardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Test
    void timecardOnlyBootRegistersSharedBoundaryAndTimecardProvidersWithoutClose() {
        TimecardOwnerAuthoritySource authoritySource = mock(TimecardOwnerAuthoritySource.class);
        TimecardRepository repository = mock(TimecardRepository.class);
        TimecardService service = mock(TimecardService.class);

        baseRunner()
                .withBean(TimecardOwnerAuthoritySource.class, () -> authoritySource)
                .withBean(TimecardRepository.class, () -> repository)
                .withBean(TimecardService.class, () -> service)
                .withUserConfiguration(
                        timecardAuthorityProviderType(),
                        TimecardCommandReceiptProvider.class,
                        ProviderRegistryConfiguration.class)
                .withPropertyValues(
                        "dwp.hris.time.timecard.enabled=true",
                        "dwp.hris.time.close.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CommandReceiptController.class);
                    assertThat(context).hasSingleBean(CommandReceiptAccessFilter.class);
                    assertThat(context).hasSingleBean(TimecardCommandReceiptProvider.class);
                    assertThat(context).doesNotHaveBean(TimeCloseCommandReceiptProvider.class);
                    assertThat(context).doesNotHaveBean(TimeCloseReceiptOwnership.class);
                    assertThat(context).doesNotHaveBean(TimeCloseActivationGate.class);
                    Map<String, CommandReceiptAuthorityProvider> authorities =
                            context.getBeansOfType(CommandReceiptAuthorityProvider.class);
                    assertThat(authorities.values())
                            .singleElement()
                            .extracting(CommandReceiptAuthorityProvider::providerId)
                            .isEqualTo("timecard");
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

    private static Class<?> timecardAuthorityProviderType() {
        try {
            return Class.forName(
                    "com.dwp.services.time.timecard.TimecardCommandReceiptAuthorityProvider");
        } catch (ClassNotFoundException missing) {
            throw new IllegalStateException(
                    "Timecard receipt authority provider is missing", missing);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProviderRegistryConfiguration {

        @Bean(destroyMethod = "close")
        CommandReceiptProviderRegistry commandReceiptProviderRegistry(
                List<CommandReceiptProvider> providers,
                CommandReceiptAuditSink auditSink) {
            return new CommandReceiptProviderRegistry(
                    providers, auditSink, Duration.ofSeconds(1));
        }
    }
}
