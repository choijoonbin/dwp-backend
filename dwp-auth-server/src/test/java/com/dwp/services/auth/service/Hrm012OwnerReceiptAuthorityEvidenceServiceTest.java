package com.dwp.services.auth.service;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class Hrm012OwnerReceiptAuthorityEvidenceServiceTest {

    @Test
    void issuesOnlyTheExactShortLivedOwnerReceiptBoundaryMarker() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        Instant now = Instant.parse("2026-10-22T01:00:00Z");
        ObjectMapper mapper = new ObjectMapper();
        var issuer = new Hrm012OwnerReceiptAuthorityEvidenceService(
                mapper, Clock.fixed(now, ZoneOffset.UTC), "hrm012-key-1", keys.getPrivate());

        String marker = issuer.issue(request(), result(now));

        var evidence = new Hrm012OwnerReceiptAuthorityVerifier(
                mapper, Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC),
                "hrm012-key-1", keys.getPublic()).verify(marker,
                new Hrm012OwnerReceiptAuthorityVerifier.Expected(
                        7L, "11", "correlation-1", "a".repeat(64),
                        Set.of("APP.HCM:VIEW")));
        assertThat(evidence.acceptedAppEntitlement()).isEqualTo("APP.HCM:VIEW");
        assertThat(evidence.featureDecisionRevision()).isEqualTo("tenant-control-57");
        assertThat(evidence.expiresAt()).isEqualTo(now.plusSeconds(30));
    }

    private ProductSurfaceAuthorityDtos.EvaluateRequest request() {
        return new ProductSurfaceAuthorityDtos.EvaluateRequest(
                7L, 11L, "hcm", "hcm.operations",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                Hrm012OwnerReceiptAuthorityContract.ROUTE, null, null, null, null,
                List.of(), "correlation-1", "a".repeat(64), null, "POST",
                Hrm012OwnerReceiptAuthorityContract.SERVICE_PATH);
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult result(Instant now) {
        return new ProductSurfaceAuthorityDtos.AuthorityResult(
                ProductSurfaceAuthorityDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED,
                Hrm012OwnerReceiptAuthorityContract.STATE,
                "auth-57", "policy-57", "tenant-control-57", null,
                "hcm", "hcm.operations", "work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL,
                ProductSurfaceAuthorityDtos.AccessSource.ENTITLEMENT,
                "APP.HCM", List.of(), List.of(), null, true, false,
                null, null, null, null,
                OffsetDateTime.ofInstant(now.plusSeconds(30), ZoneOffset.UTC), null, null);
    }
}
