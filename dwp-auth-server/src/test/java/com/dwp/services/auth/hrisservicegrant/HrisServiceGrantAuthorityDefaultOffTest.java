package com.dwp.services.auth.hrisservicegrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

class HrisServiceGrantAuthorityDefaultOffTest {
    @Test
    void allThreeRuntimeSwitchesAreExplicitlyFalseByDefault() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        assertThat(properties.getProperty(
                "dwp.auth.hris-service-grant-authority.enabled"))
                .isEqualTo("${DWP_AUTH_HRIS_SERVICE_GRANT_AUTHORITY_ENABLED:false}");
        assertThat(properties.getProperty("dwp.auth.hrm013-requester-authority.enabled"))
                .isEqualTo("${DWP_AUTH_HRM013_REQUESTER_AUTHORITY_ENABLED:false}");
        assertThat(properties.getProperty("dwp.auth.hrm013-identity-task-dispatch.enabled"))
                .isEqualTo("${DWP_AUTH_HRM013_IDENTITY_TASK_DISPATCH_ENABLED:false}");
    }

    @Test
    void runtimeControllersRequireAnExplicitTrueProperty() {
        assertTrueGate(HrisServiceGrantAuthorityController.class,
                "dwp.auth.hris-service-grant-authority.enabled");
        assertTrueGate(Hrm013RequesterIdentityAuthorityController.class,
                "dwp.auth.hrm013-requester-authority.enabled");
    }

    @Test
    void disabledContextInstantiatesNoRuntimeBoundaryOrCredentialReader() {
        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(HrisServiceGrantAuthorityController.class);
            assertThat(context).doesNotHaveBean(HrisServiceGrantAuthorityIngressFilter.class);
            assertThat(context).doesNotHaveBean(HrisServiceGrantAuthorityProofVerifier.class);
            assertThat(context).doesNotHaveBean(
                    HrisServiceGrantAuthorityOwnerCredentialProvider.class);
            assertThat(context).doesNotHaveBean(
                    Hrm013RequesterIdentityAuthorityController.class);
            assertThat(context).doesNotHaveBean(Hrm013RequesterIdentityIngressFilter.class);
            assertThat(context).doesNotHaveBean(Hrm013RequesterIdentityProofVerifier.class);
            assertThat(context).doesNotHaveBean(
                    HrisServiceGrantAuthorityIngressFilter.ClientCertificateValidator.class);
        });
    }

    @Test
    void enabledGrantEndpointFailsStartupWithoutExplicitMtlsTrustAndRevocation() {
        contextRunner()
                .withPropertyValues(
                        "dwp.auth.hris-service-grant-authority.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "missing HRM-013 mTLS setting: client-auth");
                });
    }

    @Test
    void enabledRequesterEndpointFailsStartupWithoutExplicitMtlsTrustAndRevocation() {
        contextRunner()
                .withPropertyValues("dwp.auth.hrm013-requester-authority.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "missing HRM-013 mTLS setting: client-auth");
                });
    }

    @Test
    void enabledEndpointStillFailsWhenClientAuthIsNeedButTrustStoreIsMissing() {
        contextRunner()
                .withPropertyValues(
                        "dwp.auth.hris-service-grant-authority.enabled=true",
                        "dwp.auth.hrm013-mtls.client-auth=NEED")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "missing HRM-013 mTLS setting: trust-store-location");
                });
    }

    @Test
    void enabledEndpointFailsWithoutExplicitRevocationMode(@TempDir Path directory)
            throws Exception {
        Path trustStore = writeTrustStore(directory);

        contextRunner()
                .withPropertyValues(enabledWithTrustStore(trustStore))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "missing HRM-013 mTLS setting: revocation-mode");
                });
    }

    @Test
    void enabledCrlModeFailsWithoutAnExplicitCrl(@TempDir Path directory)
            throws Exception {
        Path trustStore = writeTrustStore(directory);
        contextRunner()
                .withPropertyValues(enabledWithTrustStore(trustStore))
                .withPropertyValues("dwp.auth.hrm013-mtls.revocation-mode=CRL")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "missing HRM-013 mTLS setting: crl-location");
                });
    }

    @Test
    void enabledOcspModeFailsWithoutAnExplicitResponder(@TempDir Path directory)
            throws Exception {
        Path trustStore = writeTrustStore(directory);
        contextRunner()
                .withPropertyValues(enabledWithTrustStore(trustStore))
                .withPropertyValues("dwp.auth.hrm013-mtls.revocation-mode=OCSP")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "missing HRM-013 mTLS setting: ocsp-responder");
                });
    }

    private static WebApplicationContextRunner contextRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class,
                        SecurityAutoConfiguration.class))
                .withBean(ObjectMapper.class,
                        () -> new ObjectMapper().findAndRegisterModules())
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(PlatformTransactionManager.class,
                        () -> mock(PlatformTransactionManager.class))
                .withBean(HrisServiceGrantAuthorityOwnerCredentialProvider.LeaseSource.class,
                        () -> mock(HrisServiceGrantAuthorityOwnerCredentialProvider
                                .LeaseSource.class))
                .withUserConfiguration(
                        HrisServiceGrantAuthoritySecurityConfig.class,
                        HrisServiceGrantAuthorityController.class,
                        Hrm013RequesterIdentityAuthorityController.class);
    }

    private static String[] enabledWithTrustStore(Path trustStore) {
        return new String[] {
                "dwp.auth.hris-service-grant-authority.enabled=true",
                "dwp.auth.hrm013-mtls.client-auth=NEED",
                "dwp.auth.hrm013-mtls.trust-store-location=" + trustStore.toUri(),
                "dwp.auth.hrm013-mtls.trust-store-password=test-only-password",
                "dwp.auth.hrm013-mtls.trust-store-type=PKCS12"
        };
    }

    private static Path writeTrustStore(Path directory) throws Exception {
        Path trustStore = directory.resolve("hrm013-client-ca.p12");
        char[] password = "test-only-password".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setCertificateEntry("hrm013-test-ca",
                HrisServiceGrantAuthorityIngressFilterTest.certificate(
                        HrisServiceGrantAuthorityIngressFilterTest.TRUSTED_CA_PEM));
        try (var output = Files.newOutputStream(trustStore)) {
            keyStore.store(output, password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        return trustStore;
    }

    private static void assertTrueGate(Class<?> type, String name) {
        ConditionalOnProperty condition = type.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).isNotNull();
        assertThat(condition.name()).containsExactly(name);
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }
}
