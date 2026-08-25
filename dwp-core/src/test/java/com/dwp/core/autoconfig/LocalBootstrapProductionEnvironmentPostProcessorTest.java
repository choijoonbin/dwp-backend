package com.dwp.core.autoconfig;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class LocalBootstrapProductionEnvironmentPostProcessorTest {

    private final LocalBootstrapProductionEnvironmentPostProcessor processor =
            new LocalBootstrapProductionEnvironmentPostProcessor();

    @Test
    void isRegisteredForPreFlywayEnvironmentProcessing() throws Exception {
        var factories = PropertiesLoaderUtils.loadProperties(new ClassPathResource(
                "META-INF/spring.factories", getClass().getClassLoader()));

        assertThat(factories.getProperty(EnvironmentPostProcessor.class.getName()))
                .contains(LocalBootstrapProductionEnvironmentPostProcessor.class.getName());
    }

    @Test
    void rejectsLocalActivationBeforeProductionStartup() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "production")
                .withProperty(
                        "DWP_PRODUCT_AUTHORIZATION_LOCAL_PILOT_ACTIVATION_ENABLED", "true");

        assertThatIllegalStateException()
                .isThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .withMessageContaining("local-pilot-activation.enabled");
    }

    @Test
    void rejectsProviderLocalSeedLocationBeforeProductionFlywayRuns() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "prod")
                .withProperty(
                        "DWP_PROVIDER_FLYWAY_LOCATIONS",
                        "classpath:db/migration,classpath:db/local-seed");

        assertThatIllegalStateException()
                .isThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .withMessageContaining("db/local-seed");
    }

    @Test
    void permitsExplicitLocalBootstrapOnlyInTheLocalEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "local")
                .withProperty(
                        "dwp.product-authorization.local-pilot-activation.enabled", "true")
                .withProperty(
                        "spring.flyway.locations",
                        "classpath:db/migration,classpath:db/local-seed");

        processor.postProcessEnvironment(environment, null);
    }

    @Test
    void rejectsLocalSeedWhenOnlyTheCanonicalLocalDefaultIsPresent() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("dwp.environment", "local")
                .withProperty(
                        "spring.flyway.locations",
                        "classpath:db/migration,classpath:db/local-seed");

        assertThatIllegalStateException()
                .isThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .withMessageContaining("Non-local bootstrap guard failed");
    }

    @Test
    void rejectsLocalActivationInStaging() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DWP_ENVIRONMENT", "staging")
                .withProperty(
                        "dwp.product-authorization.local-pilot-activation.enabled", "true");

        assertThatIllegalStateException()
                .isThrownBy(() -> processor.postProcessEnvironment(environment, null))
                .withMessageContaining("local-pilot-activation.enabled");
    }
}
