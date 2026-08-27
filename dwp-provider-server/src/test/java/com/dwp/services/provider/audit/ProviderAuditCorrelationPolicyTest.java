package com.dwp.services.provider.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProviderAuditCorrelationPolicyTest {

    private final ProviderAuditService service =
            new ProviderAuditService(mock(JdbcTemplate.class), new ObjectMapper());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesOnlyCanonicalTraceIdsAsRawAuditCorrelation() {
        assertThat(service.canonicalCorrelationId("ABCDEF0123456789ABCDEF0123456789"))
                .isEqualTo("abcdef0123456789abcdef0123456789");
        assertThat(service.canonicalCorrelationId("00000000000000000000000000000000"))
                .startsWith("sha256:");
    }

    @Test
    void hashesSecretLikeAndOversizedExternalCorrelationValues() {
        String secretCanary = "ZXlKaGJHY2lPaUpTVXpJMU5pSjkucredential-like-value";
        String hashedCanary = service.canonicalCorrelationId(secretCanary);
        String oversized = service.canonicalCorrelationId("customer@example.test".repeat(500));

        assertThat(hashedCanary)
                .matches("sha256:[0-9a-f]{64}")
                .doesNotContain(secretCanary);
        assertThat(oversized)
                .matches("sha256:[0-9a-f]{64}")
                .doesNotContain("customer@example.test");
    }

    @Test
    void usesTheCanonicalMdcTraceAndGeneratesAnOpaqueIdWhenNoneExists() {
        MDC.put("traceId", "1234567890abcdef1234567890abcdef");
        assertThat(service.canonicalCorrelationId("untrusted-external"))
                .isEqualTo("1234567890abcdef1234567890abcdef");
        MDC.clear();
        assertThat(service.canonicalCorrelationId(null))
                .matches("[0-9a-f]{32}")
                .isNotEqualTo("00000000000000000000000000000000");
    }
}
