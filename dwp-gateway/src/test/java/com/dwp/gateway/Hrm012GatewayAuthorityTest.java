package com.dwp.gateway.productsurface;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Hrm012GatewayAuthorityTest {

    @Test
    void relaysOnlyTheExactAuthIssuedOwnerReceiptMarkerByteForByte() {
        ProductSurfaceAuthorityClient authority = mock(ProductSurfaceAuthorityClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProductSurfaceCandidateCatalog> catalogs = mock(ObjectProvider.class);
        var service = new ProductSurfaceContextAggregationService(
                authority, mock(GovernedRouteAuthorityClient.class),
                mock(ProductSurfaceEligibilityClient.class),
                mock(FeatureRolloutEvaluationClient.class), catalogs, Clock.systemUTC());
        var context = new ProductSurfaceContextDtos.RequestContext(
                7L, 11L, ProductSurfaceContextDtos.AccessMode.NORMAL,
                null, null, List.of(), "correlation-1", null, null);
        String marker = "eyJ0eXAiOiJ4In0.eyJzdGF0ZSI6IngifQ.signature";
        when(authority.evaluateHrm012OwnerReceipt(
                any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(result(marker)));

        assertThat(service.evaluateHrm012OwnerReceiptAuthority(
                context, "correlation-1", "a".repeat(64), null).block())
                .isEqualTo(marker);

        when(authority.evaluateHrm012OwnerReceipt(
                any(), anyString(), anyString(), any()))
                .thenReturn(Mono.just(result(" attacker.marker.value ")));
        assertThatThrownBy(() -> service.evaluateHrm012OwnerReceiptAuthority(
                context, "correlation-1", "a".repeat(64), null).block())
                .isInstanceOf(ProductSurfaceAuthorityUnavailableException.class);
    }

    private ProductSurfaceContextDtos.AuthorityResult result(String marker) {
        return new ProductSurfaceContextDtos.AuthorityResult(
                ProductSurfaceContextDtos.Decision.OWNER_RECEIPT_AUTH_REQUIRED,
                "OWNER_RECEIPT_AUTH_REQUIRED", "auth-57", "policy-57",
                "tenant-control-57", null, "hcm", "hcm.operations", "work",
                ProductSurfaceContextDtos.AccessMode.NORMAL,
                ProductSurfaceContextDtos.AccessSource.ENTITLEMENT,
                "APP.HCM", List.of(), List.of(), null, true, false,
                null, null, null, null, null, null, marker);
    }
}
