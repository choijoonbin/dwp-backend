package com.dwp.services.platform.hrisconfiguration.explorer;

import com.dwp.services.platform.security.HcmPlatformPepRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.dwp.core.security.ProductSurfaceScopeKey;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisExplorerTestFixtures.*;
import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HrisPlatformReceiptAuthorityAdapterTest {

    private static final UUID RECEIPT_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000811");
    private static final String ACTION = "HRIS_CONFIGURATION_PUBLISH";
    private static final String PURPOSE = "HRIS_CONFIGURATION_CHANGE";
    private static final String AUTHORITY_POPULATION =
            HrisExplorerAuthoritySeals.populationScopeDigest(SCOPE);
    private static final String PLATFORM_SCOPE = ProductSurfaceScopeKey.key(
            42, 84, "hcm", "hcm.utility",
            "ORIGINATING_COMMAND_SCOPE", "TARGET_POPULATION");
    private static final String PLATFORM_CONTEXT = "psc-" + "7".repeat(64);
    private static final String PLATFORM_DECISION = "psr-" + "8".repeat(64);
    private static final long PUBLIC_FIELD_REVISION = 7;
    private static final long AUTHORITY_FIELD_REVISION =
            (1L << 53) + PUBLIC_FIELD_REVISION;
    private static final long PUBLIC_AUTHORIZATION_REVISION = 11;
    private static final long AUTHORITY_AUTHORIZATION_REVISION =
            (1L << 53) + PUBLIC_AUTHORIZATION_REVISION;

    @Test
    void exactCurrentPlatformReceiptAuthorityPassesAsTypedEvidence() {
        HrisExplorerRepository repository = repository();
        HrisPlatformReceiptAuthorityAdapter adapter = adapter(
                current(ACTION, AUTHORITY_POPULATION, AUTHORITY_FIELD_REVISION,
                        PURPOSE, AUTHORITY_AUTHORIZATION_REVISION), repository);

        Optional<HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority> resolved =
                adapter.resolve(authorityRequest(), RECEIPT_ID);

        assertThat(resolved).contains(new
                HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority(
                42, SUBJECT, ACTION, POPULATION, AUTHORITY_FIELD_REVISION,
                PURPOSE, AUTHORITY_AUTHORIZATION_REVISION,
                PLATFORM_CONTEXT, PLATFORM_SCOPE, PLATFORM_DECISION,
                PLATFORM_CONTEXT, PLATFORM_SCOPE, PLATFORM_DECISION));
    }

    @Test
    void anyOriginatingContextMismatchFailsClosed() {
        HrisExplorerRepository repository = repository();

        assertThat(adapter(current("OTHER_ACTION", AUTHORITY_POPULATION,
                        AUTHORITY_FIELD_REVISION, PURPOSE,
                        AUTHORITY_AUTHORIZATION_REVISION),
                repository).resolve(authorityRequest(), RECEIPT_ID)).isEmpty();
        assertThat(adapter(current(ACTION, "c".repeat(64), AUTHORITY_FIELD_REVISION,
                        PURPOSE, AUTHORITY_AUTHORIZATION_REVISION),
                repository).resolve(authorityRequest(), RECEIPT_ID)).isEmpty();
        assertThat(adapter(current(ACTION, AUTHORITY_POPULATION,
                        AUTHORITY_FIELD_REVISION + 1, PURPOSE,
                        AUTHORITY_AUTHORIZATION_REVISION),
                repository).resolve(authorityRequest(), RECEIPT_ID)).isEmpty();
        assertThat(HrisExplorerAuthoritySeals.publicRevision(
                AUTHORITY_FIELD_REVISION + (1L << 53)))
                .isEqualTo(PUBLIC_FIELD_REVISION);
        assertThat(adapter(current(ACTION, AUTHORITY_POPULATION,
                        AUTHORITY_FIELD_REVISION + (1L << 53), PURPOSE,
                        AUTHORITY_AUTHORIZATION_REVISION), repository)
                .resolve(authorityRequest(), RECEIPT_ID)).isEmpty();
        assertThat(adapter(current(ACTION, AUTHORITY_POPULATION,
                        AUTHORITY_FIELD_REVISION, "OTHER_PURPOSE",
                        AUTHORITY_AUTHORIZATION_REVISION),
                repository).resolve(authorityRequest(), RECEIPT_ID)).isEmpty();
        assertThat(adapter(current(ACTION, AUTHORITY_POPULATION,
                        AUTHORITY_FIELD_REVISION, PURPOSE,
                        AUTHORITY_AUTHORIZATION_REVISION + 1),
                repository).resolve(authorityRequest(), RECEIPT_ID)).isEmpty();
    }

    @Test
    void noReceiptRowIsReadUntilExactlyOneForwardedAuthorityFamilySucceeds() {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        HrisPlatformReceiptAuthoritySource unavailable =
                (request, receiptId, stored) -> Optional.empty();

        assertThat(adapter(unavailable, repository)
                .resolve(authorityRequest(), RECEIPT_ID)).isEmpty();

        verify(repository, never()).findReceiptAuthority(
                42, SUBJECT, RECEIPT_ID);
    }

    @Test
    void explorerForwardedProofUsesASeparatePlatformReceiptAndOriginProof() {
        HrisExplorerRepository repository = repository();
        HrisExplorerAuthoritySource.AuthorityRequest request =
                explorerAuthorityRequest();

        Optional<HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority> resolved =
                adapter(current(ACTION, AUTHORITY_POPULATION,
                                AUTHORITY_FIELD_REVISION, PURPOSE,
                                AUTHORITY_AUTHORIZATION_REVISION), repository)
                        .resolve(request, RECEIPT_ID, snapshot());

        assertThat(resolved).contains(new
                HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority(
                42, SUBJECT, ACTION, POPULATION, AUTHORITY_FIELD_REVISION,
                PURPOSE, AUTHORITY_AUTHORIZATION_REVISION,
                CONTEXT, SCOPE, DECISION,
                PLATFORM_CONTEXT, PLATFORM_SCOPE, PLATFORM_DECISION));
    }

    @Test
    void explorerForwardedProofCannotSubstituteForMissingPlatformPhaseTwo() {
        HrisExplorerRepository repository = repository();
        HrisPlatformReceiptAuthoritySource missingPlatformPhase =
                (request, receiptId, stored) -> Optional.empty();

        assertThat(adapter(missingPlatformPhase, repository).resolve(
                explorerAuthorityRequest(), RECEIPT_ID, snapshot())).isEmpty();
    }

    @Test
    void unboundOrExpiredCurrentAuthorityIsOpaqueNotFoundAtFilter() throws Exception {
        HrisExplorerRepository repository = repository();
        HrisPlatformReceiptAuthoritySource expired = current(
                ACTION, AUTHORITY_POPULATION, AUTHORITY_FIELD_REVISION,
                PURPOSE, AUTHORITY_AUTHORIZATION_REVISION,
                NOW.minusSeconds(1));
        HrisExplorerOwnerEvidenceFilter filter = filter(
                adapter(expired, repository), repository);
        MockHttpServletRequest request = receiptRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
        assertThat(request.getAttribute(
                HrisPlatformReceiptAuthorityAdapter.VERIFIED_ATTRIBUTE)).isNull();
    }

    @Test
    void exactPlatformReceiptAuthorityReachesPepWithTypedAttribute() throws Exception {
        HrisExplorerRepository repository = repository();
        AtomicLong resolvedActor = new AtomicLong();
        AtomicReference<List<Object>> resolvedIdentity = new AtomicReference<>();
        HrisPlatformReceiptAuthoritySource delegate = current(
                ACTION, AUTHORITY_POPULATION, AUTHORITY_FIELD_REVISION,
                PURPOSE, AUTHORITY_AUTHORIZATION_REVISION);
        HrisPlatformReceiptAuthoritySource source = new HrisPlatformReceiptAuthoritySource() {
            @Override
            public Optional<ForwardedReceiptAuthority> resolveForwardedReceipt(
                    HrisExplorerAuthoritySource.AuthorityRequest request) {
                return delegate.resolveForwardedReceipt(request);
            }

            @Override
            public Optional<CurrentReceiptAuthority> resolve(
                    HrisExplorerAuthoritySource.AuthorityRequest request,
                    UUID receiptId,
                    HrisExplorerRepository.ReceiptAuthority stored) {
                    resolvedActor.set(request.actorId());
                    resolvedIdentity.set(List.of(
                            request.tenantId(), request.actorId(),
                            request.subjectPrincipalPublicId(), receiptId));
                return delegate.resolve(request, receiptId, stored);
            }
        };
        HrisExplorerOwnerEvidenceFilter filter = filter(
                adapter(source, repository),
                repository);
        MockHttpServletRequest request = receiptRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(resolvedActor).hasValue(84);
        assertThat(resolvedIdentity.get())
                .containsExactly(42L, 84L, SUBJECT, RECEIPT_ID);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(
                HrisPlatformReceiptAuthorityAdapter.VERIFIED_ATTRIBUTE))
                .isEqualTo(new
                        HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority(
                        42, SUBJECT, ACTION, POPULATION,
                        AUTHORITY_FIELD_REVISION, PURPOSE,
                        AUTHORITY_AUTHORIZATION_REVISION,
                        PLATFORM_CONTEXT, PLATFORM_SCOPE, PLATFORM_DECISION,
                        PLATFORM_CONTEXT, PLATFORM_SCOPE, PLATFORM_DECISION));
    }

    @Test
    void unavailableExplorerReceiptProfileDoesNotSuppressTypedPlatformProfile()
            throws Exception {
        HrisExplorerRepository repository = repository();
        HrisExplorerOwnerEvidenceFilter filter = filter(
                adapter(current(ACTION, AUTHORITY_POPULATION,
                                AUTHORITY_FIELD_REVISION, PURPOSE,
                                AUTHORITY_AUTHORIZATION_REVISION), repository),
                repository,
                request -> {
                    throw new IllegalStateException("Explorer authority unavailable");
                });
        MockHttpServletRequest request = receiptRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(
                HrisPlatformReceiptAuthorityAdapter.VERIFIED_ATTRIBUTE))
                .isInstanceOf(
                        HrisPlatformReceiptAuthorityAdapter.VerifiedReceiptAuthority.class);
        assertThat(request.getAttribute(
                com.dwp.services.platform.security.HcmPlatformPepRegistry
                        .EXPLORER_OWNER_EVIDENCE_ATTRIBUTE)).isNull();
    }

    @Test
    void explorerOriginCannotBeReadThroughGenericReceiptAuthority() throws Exception {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        when(repository.findReceiptAuthority(42, SUBJECT, RECEIPT_ID))
                .thenReturn(Optional.of(new HrisExplorerRepository.ReceiptAuthority(
                        SUBJECT, JdbcHrisExplorerRepository.RECENT_ACTION,
                        POPULATION, AUTHORITY_POPULATION,
                        PUBLIC_FIELD_REVISION, AUTHORITY_FIELD_REVISION,
                        "HRIS_APP_DISCOVERY", PUBLIC_AUTHORIZATION_REVISION,
                        AUTHORITY_AUTHORIZATION_REVISION,
                        "route.hcm.utility.explorer-recent-create.action",
                        CONTEXT, SCOPE, DECISION)));
        HrisExplorerOwnerEvidenceFilter filter = filter(
                adapter(current(ACTION, AUTHORITY_POPULATION,
                                AUTHORITY_FIELD_REVISION, PURPOSE,
                                AUTHORITY_AUTHORIZATION_REVISION), repository),
                repository);
        MockHttpServletRequest request = receiptRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(chain.getRequest()).isNull();
        assertThat(request.getAttribute(
                HrisPlatformReceiptAuthorityAdapter.VERIFIED_ATTRIBUTE)).isNull();
    }

    @Test
    void knownPlatformReceiptWithPackageOrCapabilityDriftIsOpaqueNotFound()
            throws Exception {
        HrisExplorerRepository repository = repository();
        HrisExplorerOwnerEvidenceFilter filter = filter(
                adapter(current(ACTION, AUTHORITY_POPULATION,
                                AUTHORITY_FIELD_REVISION, PURPOSE,
                                AUTHORITY_AUTHORIZATION_REVISION), repository),
                repository);
        MockHttpServletRequest packageDrift = receiptRequest();
        packageDrift.removeHeader("X-DWP-Roles");
        packageDrift.addHeader("X-DWP-Roles", "HRIS_EMPLOYEE");
        MockHttpServletResponse packageResponse = new MockHttpServletResponse();
        MockFilterChain packageChain = new MockFilterChain();

        filter.doFilter(packageDrift, packageResponse, packageChain);

        MockHttpServletRequest capabilityDrift = receiptRequest();
        capabilityDrift.removeHeader("X-DWP-Permissions");
        capabilityDrift.addHeader("X-DWP-Permissions", "APP.HCM:VIEW");
        MockHttpServletResponse capabilityResponse = new MockHttpServletResponse();
        MockFilterChain capabilityChain = new MockFilterChain();

        filter.doFilter(capabilityDrift, capabilityResponse, capabilityChain);

        MockHttpServletRequest planeDrift = receiptRequest();
        planeDrift.removeHeader("X-DWP-Identity-Plane");
        planeDrift.addHeader("X-DWP-Identity-Plane", "PROVIDER");
        MockHttpServletResponse planeResponse = new MockHttpServletResponse();
        MockFilterChain planeChain = new MockFilterChain();
        filter.doFilter(planeDrift, planeResponse, planeChain);

        assertThat(packageResponse.getStatus()).isEqualTo(404);
        assertThat(capabilityResponse.getStatus()).isEqualTo(404);
        assertThat(planeResponse.getStatus()).isEqualTo(404);
        assertThat(packageResponse.getErrorMessage())
                .isEqualTo(capabilityResponse.getErrorMessage());
        assertThat(packageResponse.getErrorMessage())
                .isEqualTo(planeResponse.getErrorMessage());
        assertThat(packageChain.getRequest()).isNull();
        assertThat(capabilityChain.getRequest()).isNull();
        assertThat(planeChain.getRequest()).isNull();
    }

    private HrisPlatformReceiptAuthoritySource current(
            String action, String population, long fieldRevision,
            String purpose, long authorizationRevision) {
        return current(action, population, fieldRevision, purpose,
                authorizationRevision, NOW.plusMinutes(5));
    }

    private HrisPlatformReceiptAuthoritySource current(
            String action, String population, long fieldRevision,
            String purpose, long authorizationRevision,
            java.time.OffsetDateTime freshUntil) {
        return new HrisPlatformReceiptAuthoritySource() {
            @Override
            public Optional<ForwardedReceiptAuthority> resolveForwardedReceipt(
                    HrisExplorerAuthoritySource.AuthorityRequest request) {
                if (!PLATFORM_SCOPE.equals(request.contextScopeKey())) {
                    return Optional.empty();
                }
                return Optional.of(new ForwardedReceiptAuthority(
                        42, SUBJECT, request.contextKey(), request.contextScopeKey(),
                        request.currentDecisionRevision(), freshUntil));
            }

            @Override
            public Optional<CurrentReceiptAuthority> resolve(
                    HrisExplorerAuthoritySource.AuthorityRequest request,
                    UUID receiptId,
                    HrisExplorerRepository.ReceiptAuthority stored) {
                return Optional.of(authority(
                        request.contextKey(), request.contextScopeKey(),
                        request.currentDecisionRevision(), stored));
            }

            @Override
            public Optional<CurrentReceiptAuthority> resolveAfterExplorerForwarded(
                    HrisExplorerAuthoritySource.AuthorityRequest request,
                    HrisExplorerAuthoritySource.AuthoritySnapshot explorerAuthority,
                    UUID receiptId,
                    HrisExplorerRepository.ReceiptAuthority stored) {
                return Optional.of(authority(
                        PLATFORM_CONTEXT, PLATFORM_SCOPE, PLATFORM_DECISION, stored));
            }

            private CurrentReceiptAuthority authority(
                    String receiptContext, String receiptScope,
                    String receiptDecision,
                    HrisExplorerRepository.ReceiptAuthority stored) {
                return new CurrentReceiptAuthority(
                        42, SUBJECT, action, population, fieldRevision,
                        purpose, authorizationRevision,
                        stored.originRouteContractKey(), stored.originContextKey(),
                        stored.originContextScopeKey(), stored.originDecisionRevision(),
                        receiptContext, receiptScope, receiptDecision, freshUntil);
            }
        };
    }

    private HrisExplorerRepository repository() {
        HrisExplorerRepository repository = mock(HrisExplorerRepository.class);
        when(repository.findReceiptAuthority(42, SUBJECT, RECEIPT_ID))
                .thenReturn(Optional.of(new HrisExplorerRepository.ReceiptAuthority(
                        SUBJECT, ACTION, POPULATION, AUTHORITY_POPULATION,
                        PUBLIC_FIELD_REVISION, AUTHORITY_FIELD_REVISION,
                        PURPOSE, PUBLIC_AUTHORIZATION_REVISION,
                        AUTHORITY_AUTHORIZATION_REVISION,
                        "route.hcm.configuration.configuration-version-publish.action",
                        CONTEXT, SCOPE, DECISION)));
        return repository;
    }

    private HrisPlatformReceiptAuthorityAdapter adapter(
            HrisPlatformReceiptAuthoritySource source,
            HrisExplorerRepository repository) {
        StaticListableBeanFactory sources = new StaticListableBeanFactory();
        sources.addBean("source", source);
        return new HrisPlatformReceiptAuthorityAdapter(
                sources.getBeanProvider(HrisPlatformReceiptAuthoritySource.class),
                repository,
                Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"), ZoneOffset.UTC));
    }

    private HrisExplorerOwnerEvidenceFilter filter(
            HrisPlatformReceiptAuthorityAdapter platform,
            HrisExplorerRepository repository) {
        return filter(platform, repository, request -> Optional.empty());
    }

    private HrisExplorerOwnerEvidenceFilter filter(
            HrisPlatformReceiptAuthorityAdapter platform,
            HrisExplorerRepository repository,
            HrisExplorerAuthorityEvidenceQuery explorerQuery) {
        StaticListableBeanFactory authorityQueries = new StaticListableBeanFactory();
        authorityQueries.addBean("explorerAuthority", explorerQuery);
        HrisExplorerAuthoritySource authority = new QueryBackedHrisExplorerAuthoritySource(
                authorityQueries.getBeanProvider(HrisExplorerAuthorityEvidenceQuery.class));
        StaticListableBeanFactory authoritySources = new StaticListableBeanFactory();
        authoritySources.addBean("authority", authority);
        StaticListableBeanFactory explorerReceiptSources =
                new StaticListableBeanFactory();
        HrisExplorerOwnerEvidenceAdapter explorer = new HrisExplorerOwnerEvidenceAdapter(
                authoritySources.getBeanProvider(HrisExplorerAuthoritySource.class),
                explorerReceiptSources.getBeanProvider(
                        HrisExplorerReceiptAuthoritySource.class),
                repository,
                Clock.fixed(Instant.parse("2026-10-15T04:00:00Z"), ZoneOffset.UTC));
        return new HrisExplorerOwnerEvidenceFilter(
                "trusted-explorer-token", explorer, platform,
                new HcmPlatformPepRegistry(new ObjectMapper()));
    }

    private MockHttpServletRequest receiptRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/v1/admin/hris/command-receipts/" + RECEIPT_ID);
        request.addHeader("X-DWP-Service-Token", "trusted-explorer-token");
        request.addHeader("X-DWP-Tenant-ID", "42");
        request.addHeader("X-DWP-User-ID", "84");
        request.addHeader("X-DWP-Person-Public-ID", SUBJECT.toString());
        request.addHeader("X-DWP-Identity-Plane", "TENANT");
        request.addHeader("X-DWP-Permissions", "DATA.HRIS_COMMAND_RECEIPT:VIEW");
        request.addHeader("X-DWP-Roles", "HRIS_CONFIG_AUTHOR");
        request.addHeader("X-DWP-Active-Access-Mode", "NORMAL");
        request.addHeader("X-DWP-Current-Decision-Revision", DECISION);
        request.addHeader("X-DWP-Route-Contract-Key",
                "route.hcm.utility.explorer-command-receipt.data");
        request.removeHeader("X-DWP-Current-Decision-Revision");
        request.addHeader("X-DWP-Current-Decision-Revision", PLATFORM_DECISION);
        request.addHeader("X-DWP-Context-Key", PLATFORM_CONTEXT);
        request.addHeader("X-DWP-Context-Scope-Key", PLATFORM_SCOPE);
        request.addHeader("X-DWP-Current-Revalidate-At", NOW.plusMinutes(5).toString());
        return request;
    }

    private HrisExplorerAuthoritySource.AuthorityRequest authorityRequest() {
        return new HrisExplorerAuthoritySource.AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", PLATFORM_DECISION,
                "route.hcm.utility.explorer-command-receipt.data",
                PLATFORM_CONTEXT, PLATFORM_SCOPE, "COMMAND_STATUS", NOW.plusMinutes(5));
    }

    private HrisExplorerAuthoritySource.AuthorityRequest explorerAuthorityRequest() {
        return new HrisExplorerAuthoritySource.AuthorityRequest(
                42, 84, SUBJECT, "NORMAL", DECISION,
                "route.hcm.utility.explorer-command-receipt.data",
                CONTEXT, SCOPE, "COMMAND_STATUS", NOW.plusMinutes(5));
    }
}
