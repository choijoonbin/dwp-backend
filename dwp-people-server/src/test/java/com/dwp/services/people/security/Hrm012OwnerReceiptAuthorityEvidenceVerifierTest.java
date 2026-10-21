package com.dwp.services.people.security;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Hrm012OwnerReceiptAuthorityEvidenceVerifierTest {

    @AfterEach
    void clear() {
        PeopleRequestContext.clear();
    }

    @Test
    void bindsMarkerToTrustedPeopleIdentityCorrelationAndRawIdempotencyDigest() {
        PeopleRequestContext.set(
                11L, 7L, Set.of("HR_ADMIN"), Set.of("APP.HCM:VIEW"));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH);
        request.addHeader("X-Correlation-ID", "correlation-1");
        request.addHeader("Idempotency-Key", "raw-idempotency-1");
        Hrm012OwnerReceiptAuthorityVerifier core =
                mock(Hrm012OwnerReceiptAuthorityVerifier.class);
        var evidence = new Hrm012OwnerReceiptAuthorityVerifier.Evidence(
                7L, "11", "jti-1", Instant.now().plusSeconds(30),
                "APP.HCM:VIEW", "feature-57", "correlation-1",
                Hrm012OwnerReceiptAuthorityContract.sha256("raw-idempotency-1"),
                null, "a".repeat(64));
        when(core.verify(eq("marker"), any())).thenReturn(evidence);
        var verifier = new Hrm012OwnerReceiptAuthorityEvidenceVerifier(core);

        assertThat(verifier.verify(request, "marker")).isSameAs(evidence);

        ArgumentCaptor<Hrm012OwnerReceiptAuthorityVerifier.Expected> expected =
                ArgumentCaptor.forClass(Hrm012OwnerReceiptAuthorityVerifier.Expected.class);
        verify(core).verify(eq("marker"), expected.capture());
        assertThat(expected.getValue().tenantId()).isEqualTo(7L);
        assertThat(expected.getValue().onBehalfOfSubject()).isEqualTo("11");
        assertThat(expected.getValue().acceptedAppEntitlements())
                .containsExactly("APP.HCM:VIEW");
        assertThat(expected.getValue().idempotencyKeySha256())
                .isEqualTo(Hrm012OwnerReceiptAuthorityContract.sha256("raw-idempotency-1"));
    }
}
