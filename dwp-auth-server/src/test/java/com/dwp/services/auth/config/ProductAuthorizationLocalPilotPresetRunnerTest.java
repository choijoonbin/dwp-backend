package com.dwp.services.auth.config;

import com.dwp.services.auth.repository.AppAdminPresetRepository;
import com.dwp.services.auth.service.AppAdminPresetRequestService;
import com.dwp.services.auth.service.AppAdminPresetService;
import com.dwp.services.auth.service.AppGovernanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationLocalPilotPresetRunnerTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private AppGovernanceService governance;
    @Mock private AppAdminPresetRepository repository;
    @Mock private AppAdminPresetRequestService requests;
    @Mock private AppAdminPresetService presets;

    @Test
    void remainsOptInWithoutReadingPresetOrIdentityState() {
        runner(false, "production").run(null);

        verifyNoInteractions(jdbc, governance, repository, requests, presets);
    }

    @Test
    void rejectsEnabledPresetBootstrapOutsideTheExactLocalEnvironment() {
        assertThatIllegalStateException()
                .isThrownBy(() -> runner(true, "staging"))
                .withMessageContaining("forbidden outside");

        verifyNoInteractions(jdbc, governance, repository, requests, presets);
    }

    @Test
    void rejectsEnabledPresetBootstrapWhenTheEnvironmentMarkerIsMissing() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ProductAuthorizationLocalPilotPresetRunner(
                        true, new MockEnvironment(), jdbc, governance, repository,
                        requests, presets, Clock.systemUTC()))
                .withMessageContaining("forbidden outside");

        verifyNoInteractions(jdbc, governance, repository, requests, presets);
    }

    @Test
    void doesNotTrustTheCanonicalApplicationDefaultAsTheLocalMarker() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new ProductAuthorizationLocalPilotPresetRunner(
                        true,
                        new MockEnvironment().withProperty("dwp.environment", "local"),
                        jdbc, governance, repository, requests, presets,
                        Clock.systemUTC()))
                .withMessageContaining("forbidden outside");

        verifyNoInteractions(jdbc, governance, repository, requests, presets);
    }

    private ProductAuthorizationLocalPilotPresetRunner runner(
            boolean enabled,
            String environment) {
        return new ProductAuthorizationLocalPilotPresetRunner(
                enabled,
                new MockEnvironment().withProperty("DWP_ENVIRONMENT", environment),
                jdbc, governance, repository, requests, presets, Clock.systemUTC());
    }
}
