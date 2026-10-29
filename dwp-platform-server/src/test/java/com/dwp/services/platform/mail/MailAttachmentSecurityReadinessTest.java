package com.dwp.services.platform.mail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MailAttachmentSecurityReadinessTest {

    private static final String STRONG_TOKEN =
            "N8v!MailScanner_2026-Secure#Q7mR2xLp";

    @ParameterizedTest
    @CsvSource({"production, local", "local, prod"})
    void eitherProductionEnvironmentMarkerFailsStartupWithoutOwner(
            String dwpEnvironment, String legacyEnvironment) {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(List.of(), true,
                        environment(dwpEnvironment, legacyEnvironment,
                                "https://scanner.mail.company.com/", STRONG_TOKEN));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted scanner owner");
    }

    @Test
    void localDisabledFeatureRemainsCompatible() {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(List.of(), false,
                        environment("local", "local", "", ""));

        assertThatCode(readiness::validateProduction).doesNotThrowAnyException();
    }

    @Test
    void localEnabledFeatureKeepsDevelopmentScannerCompatibility() {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(
                        List.of(mock(MailAttachmentScanner.class)), true,
                        environment("test", "local", "https://127.0.0.1/", "x"));

        assertThatCode(readiness::validateProduction).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://localhost/",
            "https://127.0.0.1/",
            "https://127.0.0.42/",
            "https://[::1]/",
            "https://scanner.mail.internal/",
            "https://scanner.mail.corp/",
            "https://scanner.mail.lan/",
            "https://scanner.mail.home/",
            "https://scanner.home.arpa/",
            "https://scanner.mail.invalid/",
            "https://scanner.mail.example/",
            "https://scanner.default.svc/",
            "https://scanner.example.a123/",
            "https://0x7f.0x0.0x0.0x1/",
            "https://example.com/",
            "https://scanner.example.com/",
            "https://example.net/",
            "https://scanner.example.net/",
            "https://example.org/",
            "https://scanner.example.org/",
            "https://scanner.xn--a-/"
    })
    void productionRejectsNonPublicOrIpLiteralScannerEndpoints(String endpoint) {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(
                        List.of(mock(MailAttachmentScanner.class)), true,
                        environment("production", "local", endpoint, STRONG_TOKEN));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-local HTTPS scanner endpoint")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(STRONG_TOKEN));
    }

    @Test
    void productionStillValidatesAnActiveScannerWhenAttachmentsAreDisabled() {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(
                        List.of(mock(MailAttachmentScanner.class)), false,
                        environment("production", "local", "https://localhost/", "x"));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-local HTTPS scanner endpoint");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "x",
            "local-scanner-token-that-is-long-enough-2026",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "replace-me-with-production-scanner-token-2026"
    })
    void productionRejectsWeakOrPlaceholderScannerTokens(String token) {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(
                        List.of(mock(MailAttachmentScanner.class)), true,
                        environment("local", "production",
                                "https://scanner.mail.company.com/", token));

        assertThatThrownBy(readiness::validateProduction)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("strong non-placeholder scanner token")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(token));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://scanner.mail.company.com/",
            "https://scanner.internal-services.company.com/",
            "https://scanner.xn--p1ai/"
    })
    void productionAcceptsPublicDnsScannerWithStrongToken(String endpoint) {
        MailAttachmentSecurityReadiness readiness =
                new MailAttachmentSecurityReadiness(
                        List.of(mock(MailAttachmentScanner.class)), true,
                        environment("production", "local", endpoint, STRONG_TOKEN));

        assertThatCode(readiness::validateProduction).doesNotThrowAnyException();
    }

    private MockEnvironment environment(
            String dwpEnvironment,
            String legacyEnvironment,
            String endpoint,
            String token) {
        return new MockEnvironment()
                .withProperty("dwp.environment", dwpEnvironment)
                .withProperty("DWP_ENVIRONMENT", legacyEnvironment)
                .withProperty(
                        "dwp.platform.mail.attachments.trusted-scanner.base-url", endpoint)
                .withProperty(
                        "dwp.platform.mail.attachments.trusted-scanner.service-token", token);
    }
}
