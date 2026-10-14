package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerAuthoritySource.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HrisExplorerOwnerEvidenceFilterTest {

    private static final long PUBLIC_FIELD_REVISION = 7;
    private static final long AUTHORITY_FIELD_REVISION =
            (1L << 53) + PUBLIC_FIELD_REVISION;
    private static final long PUBLIC_AUTHORIZATION_REVISION = 11;
    private static final long AUTHORITY_AUTHORIZATION_REVISION =
            (1L << 53) + PUBLIC_AUTHORIZATION_REVISION;

    @Test
    void injectedServerAuthorityPopulatesTypedAttributesBeforePep() throws Exception {
        AuthoritySnapshot snapshot = snapshot();
        AtomicLong resolvedActor = new AtomicLong();
        AtomicReference<List<Object>> resolvedIdentity = new AtomicReference<>();
        HrisExplorerAuthorityEvidenceQuery query = request -> {
            resolvedActor.set(request.actorId());
            resolvedIdentity.set(List.of(
                    request.tenantId(), request.actorId(),
                    request.subjectPrincipalPublicId()));
            return Optional.of(snapshot);
        };
        HrisExplorerOwnerEvidenceFilter filter = filter(query);
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(resolvedActor).hasValue(84);
        assertThat(resolvedIdentity.get()).containsExactly(42L, 84L, SUBJECT);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(
                HrisExplorerOwnerEvidenceAdapter.SNAPSHOT_ATTRIBUTE)).isEqualTo(snapshot);
        assertThat(request.getAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE))
                .isInstanceOfSatisfying(
                        HcmPlatformPepRegistry.ExplorerOwnerEvidence.class,
                        evidence -> {
                            assertThat(evidence.subjectPrincipalPublicId())
                                    .isEqualTo(SUBJECT.toString());
                            assertThat(evidence.contextScopeKey()).isEqualTo(SCOPE);
                            assertThat(evidence.allowedDescendantPageRouteKeys())
                                    .containsExactlyInAnyOrderElementsOf(
                                            snapshot.allowedRouteDecisions().values().stream()
                                                    .map(value -> value.routeContractKey())
                                                    .toList());
                        });
    }

    @Test
    void missingAuthorityFailsClosedEvenWhenClientClaimsRoles() throws Exception {
        HrisExplorerOwnerEvidenceFilter filter = filter(
                request -> Optional.empty());
        MockHttpServletRequest request = request();
        request.addHeader("X-DWP-Roles", "HRIS_DUTY_APP_SHELL_READER,ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
        assertThat(request.getAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE)).isNull();
    }

    @Test
    void unavailableAuthorityFailsClosedBeforePep() throws Exception {
        HrisExplorerOwnerEvidenceFilter filter = filter(
                request -> {
                    throw new IllegalStateException("trusted authority unavailable");
                });
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
        assertThat(request.getAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE)).isNull();
    }

    @Test
    void mismatchedServerAuthorityFailsClosedWithoutPepAttributes() throws Exception {
        AuthoritySnapshot source = snapshot();
        AuthoritySnapshot mismatched = new AuthoritySnapshot(
                99, source.subjectPrincipalPublicId(), source.currentDecisionRevision(),
                source.contextKey(), source.contextScopeKey(), source.purposeCode(),
                source.populationScopeDigest(), source.fieldPolicyRevision(),
                source.authorizationRevision(), source.entitlementRevision(),
                source.projectionRevision(), source.freshUntil(),
                source.allowedRouteDecisions());
        HrisExplorerOwnerEvidenceFilter filter = filter(
                request -> Optional.of(mismatched));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(request.getAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE)).isNull();
    }

    @Test
    void expiredServerAuthorityFailsClosedBeforePep() throws Exception {
        AuthoritySnapshot value = snapshot();
        AuthoritySnapshot expired = new AuthoritySnapshot(
                value.tenantId(), value.subjectPrincipalPublicId(),
                value.currentDecisionRevision(), value.contextKey(),
                value.contextScopeKey(), value.purposeCode(),
                value.populationScopeDigest(), value.fieldPolicyRevision(),
                value.authorizationRevision(), value.entitlementRevision(),
                value.projectionRevision(), NOW.minusSeconds(1),
                value.allowedRouteDecisions());
        HrisExplorerOwnerEvidenceFilter filter = filter(
                request -> Optional.of(expired));
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(request.getAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE)).isNull();
    }

    @Test
    void explorerReceiptBindsEveryStoredOriginatingContextField() throws Exception {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        java.util.UUID receiptId = java.util.UUID.fromString(
                "00000000-0000-4000-8000-000000000951");
        String originDecision = "psr-" + "e".repeat(64);
        when(repository.findReceiptAuthority(42, SUBJECT, receiptId))
                .thenReturn(Optional.of(new HrisExplorerRepository.ReceiptAuthority(
                        SUBJECT, JdbcHrisExplorerRepository.RECENT_ACTION,
                        POPULATION, AUTHORITY_POPULATION,
                        PUBLIC_FIELD_REVISION, AUTHORITY_FIELD_REVISION,
                        "HRIS_APP_DISCOVERY", PUBLIC_AUTHORIZATION_REVISION,
                        AUTHORITY_AUTHORIZATION_REVISION,
                        "route.hcm.utility.explorer-recent-create.action",
                        CONTEXT, SCOPE, originDecision)));
        HrisExplorerOwnerEvidenceFilter filter = filter(
                request -> Optional.of(snapshot()), repository,
                (request, id, stored) -> Optional.of(
                        currentOrigin(stored, originDecision)));
        MockHttpServletRequest request = request(
                "/v1/admin/hris/command-receipts/" + receiptId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE))
                .isInstanceOfSatisfying(
                        HcmPlatformPepRegistry.ExplorerOwnerEvidence.class,
                        evidence -> assertThat(evidence.receiptEvidence())
                                .isEqualTo(new HcmPlatformPepRegistry.ExplorerReceiptEvidence(
                                        SUBJECT.toString(),
                                        JdbcHrisExplorerRepository.RECENT_ACTION,
                                        CONTEXT, SCOPE)));
    }

    @Test
    void staleExplorerReceiptOriginatingContextIsOpaqueNotFound() throws Exception {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        java.util.UUID receiptId = java.util.UUID.fromString(
                "00000000-0000-4000-8000-000000000952");
        String originDecision = "psr-" + "e".repeat(64);
        when(repository.findReceiptAuthority(42, SUBJECT, receiptId))
                .thenReturn(Optional.of(new HrisExplorerRepository.ReceiptAuthority(
                        SUBJECT, JdbcHrisExplorerRepository.RECENT_ACTION,
                        POPULATION, AUTHORITY_POPULATION,
                        PUBLIC_FIELD_REVISION, AUTHORITY_FIELD_REVISION,
                        "HRIS_APP_DISCOVERY", PUBLIC_AUTHORIZATION_REVISION,
                        AUTHORITY_AUTHORIZATION_REVISION,
                        "route.hcm.utility.explorer-recent-create.action",
                        CONTEXT, SCOPE, originDecision)));
        HrisExplorerOwnerEvidenceFilter filter = filter(
                request -> Optional.of(snapshot()), repository,
                (request, id, stored) -> Optional.of(
                        currentOrigin(stored, "psr-" + "f".repeat(64))));
        MockHttpServletRequest request = request(
                "/v1/admin/hris/command-receipts/" + receiptId);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
        assertThat(request.getAttribute(
                HcmPlatformPepRegistry.EXPLORER_OWNER_EVIDENCE_ATTRIBUTE)).isNull();
    }

    private HrisExplorerOwnerEvidenceFilter filter(
            HrisExplorerAuthorityEvidenceQuery query) {
        return filter(query, mock(HrisExplorerRepository.class));
    }

    private HrisExplorerOwnerEvidenceFilter filter(
            HrisExplorerAuthorityEvidenceQuery query,
            HrisExplorerRepository repository) {
        return filter(query, repository,
                (request, receiptId, stored) -> Optional.of(
                        currentOrigin(stored, stored.originDecisionRevision())));
    }

    private HrisExplorerOwnerEvidenceFilter filter(
            HrisExplorerAuthorityEvidenceQuery query,
            HrisExplorerRepository repository,
            HrisExplorerReceiptAuthoritySource receiptAuthority) {
        StaticListableBeanFactory queryBeans = new StaticListableBeanFactory();
        queryBeans.addBean("query", query);
        HrisExplorerAuthoritySource source = new QueryBackedHrisExplorerAuthoritySource(
                queryBeans.getBeanProvider(HrisExplorerAuthorityEvidenceQuery.class));
        StaticListableBeanFactory sourceBeans = new StaticListableBeanFactory();
        sourceBeans.addBean("source", source);
        StaticListableBeanFactory receiptSources = new StaticListableBeanFactory();
        receiptSources.addBean("receiptAuthority", receiptAuthority);
        HrisExplorerOwnerEvidenceAdapter adapter = new HrisExplorerOwnerEvidenceAdapter(
                sourceBeans.getBeanProvider(HrisExplorerAuthoritySource.class),
                receiptSources.getBeanProvider(
                        HrisExplorerReceiptAuthoritySource.class),
                repository,
                Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"), ZoneOffset.UTC));
        StaticListableBeanFactory platformSources = new StaticListableBeanFactory();
        HrisPlatformReceiptAuthorityAdapter platform =
                new HrisPlatformReceiptAuthorityAdapter(
                        platformSources.getBeanProvider(
                                HrisPlatformReceiptAuthoritySource.class),
                        repository);
        return new HrisExplorerOwnerEvidenceFilter(
                "trusted-explorer-token", adapter, platform,
                new HcmPlatformPepRegistry(new ObjectMapper()));
    }

    private HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority
            currentOrigin(
            HrisExplorerRepository.ReceiptAuthority stored,
            String originDecisionRevision) {
        return new HrisExplorerReceiptAuthoritySource.CurrentExplorerReceiptAuthority(
                42, SUBJECT, stored.originatingAction(),
                stored.authorityPopulationScopeDigest(),
                stored.authorityFieldPolicyRevision(),
                stored.purposeCode(), stored.authorityAuthorizationRevision(),
                stored.originRouteContractKey(), stored.originContextKey(),
                stored.originContextScopeKey(), originDecisionRevision,
                NOW.plusMinutes(5));
    }

    private MockHttpServletRequest request() {
        return request("/v1/hris/explorer");
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", path);
        request.addHeader("X-DWP-Service-Token", "trusted-explorer-token");
        request.addHeader("X-DWP-Tenant-ID", "42");
        request.addHeader("X-DWP-User-ID", "84");
        request.addHeader("X-DWP-Person-Public-ID", SUBJECT.toString());
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Current-Decision-Revision", DECISION);
        request.addHeader("X-DWP-Route-Contract-Key",
                path.startsWith("/v1/admin/hris/command-receipts/")
                        ? "route.hcm.utility.explorer-command-receipt.data"
                        : "route.hcm.utility.explorer.page");
        request.addHeader("X-DWP-Context-Key", CONTEXT);
        request.addHeader("X-DWP-Context-Scope-Key", SCOPE);
        request.addHeader("X-DWP-Current-Revalidate-At", NOW.plusMinutes(5).toString());
        return request;
    }
}
