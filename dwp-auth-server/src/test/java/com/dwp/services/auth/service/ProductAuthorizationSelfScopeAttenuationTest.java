package com.dwp.services.auth.service;

import com.dwp.services.auth.dto.ProductAuthorizationContractDtos;
import com.dwp.services.auth.dto.ProductSurfaceAuthorityDtos;
import com.dwp.services.auth.repository.ProductAuthorizationContractRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductAuthorizationSelfScopeAttenuationTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-21T09:00:00Z"), ZoneOffset.UTC);
    private static final String RECEIPT = "route.approvals.work.information-command-receipt.data";
    private static final Set<String> PERMISSIONS = Set.of(
            "APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:VIEW",
            "ACTION.APPROVAL_REQUEST:UPDATE");

    @Mock
    private ProductAuthorizationContractRepository repository;
    @Mock
    private ProductAuthorizationIdentityEvidenceService evidenceService;
    private ProductAuthorizationAuthorityAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        ProductAuthorizationContractDtos.BundleContract contract = new ObjectMapper()
                .findAndRegisterModules().readValue(getClass().getResourceAsStream(
                        "/product-authorization/product-surfaces-v1.bundle-v9.generated.json"),
                        ProductAuthorizationContractDtos.BundleContract.class);
        assertThat(contract.checksum()).isEqualTo(
                "02b19c4119e560b63d4054ec317fe7e4d694e402a5af03960c63b20db4b41ab7");
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        ProductAuthorizationContractRepository.StoredBundle stored =
                new ProductAuthorizationContractRepository.StoredBundle(
                        id, contract.bundleKey(), contract.version(), "ACTIVE",
                        contract.schemaVersion(), contract.checksumAlgorithm(), contract.checksum(),
                        contract.owner(), "security-reviewer", now, now, now);
        when(repository.findActive("product-surfaces")).thenReturn(Optional.of(stored));
        when(repository.loadContract(stored)).thenReturn(contract);
        when(repository.findActivePointer("product-surfaces")).thenReturn(Optional.of(
                new ProductAuthorizationContractRepository.ActivePointer(
                        "product-surfaces", id, contract.version(), "release", now)));
        evidence(PERMISSIONS);
        adapter = new ProductAuthorizationAuthorityAdapter(
                repository, evidenceService, CLOCK, "urn:dwp:acr:mfa");
    }

    @ParameterizedTest
    @ValueSource(strings = {RECEIPT, "route.approvals.work.requests-search.data"})
    void readonlyDataAttenuatesOnlyReturnedSelfScope(String route) {
        ProductSurfaceAuthorityDtos.AuthorityResult entry = evaluate(null, null, null);
        ProductSurfaceAuthorityDtos.EffectiveScope original = entry.scopes().getFirst();
        assertThat(original.readOnly()).isFalse();

        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                route, entry.contextKey(), original.key());

        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.effectiveReadOnly()).isTrue();
        assertThat(result.scopes()).containsExactly(new ProductSurfaceAuthorityDtos.EffectiveScope(
                original.key(), original.kind(), original.displayName(), original.isDefault(),
                true, original.validUntil()));
        assertThat(entry.scopes()).containsExactly(original);
        assertThat(original.readOnly()).isFalse();
        assertSameAuthorityIdentity(entry, result);
        assertThat(result.effectiveGrants()).isNotEmpty().allSatisfy(grant -> {
            assertThat(grant.scopeKeys()).containsExactly(original.key());
            assertThat(grant.readOnly()).isTrue();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"route.approvals.work.request-detail.data",
            "route.approvals.work.request-draft-update.action"})
    void writableDataAndActionKeepOriginalSelfScope(String route) {
        ProductSurfaceAuthorityDtos.AuthorityResult entry = evaluate(null, null, null);
        ProductSurfaceAuthorityDtos.EffectiveScope original = entry.scopes().getFirst();

        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                route, entry.contextKey(), original.key());

        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.effectiveReadOnly()).isFalse();
        assertThat(result.scopes()).containsExactly(original);
        assertThat(original.readOnly()).isFalse();
        assertSameAuthorityIdentity(entry, result);
        assertThat(result.effectiveGrants()).isNotEmpty().allSatisfy(grant -> {
            assertThat(grant.scopeKeys()).containsExactly(original.key());
            assertThat(grant.readOnly()).isFalse();
        });
    }

    @Test
    void readonlyAttenuationDoesNotAdmitWrongContextOrScope() {
        ProductSurfaceAuthorityDtos.AuthorityResult entry = evaluate(null, null, null);
        for (ProductSurfaceAuthorityDtos.AuthorityResult result : List.of(
                evaluate(RECEIPT, "wrong-context", entry.scopes().getFirst().key()),
                evaluate(RECEIPT, entry.contextKey(), "wrong-scope"))) {
            assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.SCOPE_INVALID);
            assertThat(result.scopes()).isEmpty();
            assertThat(result.effectiveGrants()).isEmpty();
        }
    }

    @Test
    void readonlyAttenuationDoesNotReplaceCurrentRequestView() {
        evidence(Set.of("APP.APPROVALS:VIEW", "ACTION.APPROVAL_REQUEST:UPDATE"));
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(RECEIPT, null, null);
        assertThat(result.decision()).isNotEqualTo(ProductSurfaceAuthorityDtos.Decision.ALLOWED);
        assertThat(result.scopes()).isEmpty();
        assertThat(result.effectiveGrants()).isEmpty();
    }

    @Test
    void unknownReceiptAliasRemainsDenied() {
        ProductSurfaceAuthorityDtos.AuthorityResult result = evaluate(
                "route.approvals.work.information-command-receipt.read", null, null);
        assertThat(result.decision()).isEqualTo(ProductSurfaceAuthorityDtos.Decision.ROUTE_DENIED);
        assertThat(result.scopes()).isEmpty();
        assertThat(result.effectiveGrants()).isEmpty();
    }

    private void assertSameAuthorityIdentity(
            ProductSurfaceAuthorityDtos.AuthorityResult entry,
            ProductSurfaceAuthorityDtos.AuthorityResult result) {
        assertThat(result.contextKey()).isEqualTo(entry.contextKey());
        assertThat(result.authRevision()).isEqualTo(entry.authRevision());
        assertThat(result.policyRevision()).isEqualTo(entry.policyRevision());
        assertThat(result.productKey()).isEqualTo(entry.productKey());
        assertThat(result.surfaceKey()).isEqualTo(entry.surfaceKey());
        assertThat(result.plane()).isEqualTo(entry.plane());
        assertThat(result.accessMode()).isEqualTo(entry.accessMode());
    }

    private void evidence(Set<String> permissions) {
        when(evidenceService.load(10L, 20L)).thenReturn(
                new ProductAuthorizationIdentityEvidenceService.IdentityEvidence(
                        permissions, Set.of(), List.of(), List.of(), "auth-test-revision"));
    }

    private ProductSurfaceAuthorityDtos.AuthorityResult evaluate(
            String route, String context, String scope) {
        return adapter.evaluate(new ProductSurfaceAuthorityDtos.EvaluateRequest(
                10L, 20L, "approvals", "approvals.work",
                ProductSurfaceAuthorityDtos.AccessMode.NORMAL, route, context, scope,
                null, null, List.of()));
    }
}
