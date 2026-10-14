package com.dwp.services.platform.hrisconfiguration.explorer;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.dwp.services.platform.hrisconfiguration.explorer.HrisPlatformReceiptAuthoritySource.*;

@Component
final class HrisPlatformReceiptAuthorityAdapter {

    static final String VERIFIED_ATTRIBUTE =
            HrisPlatformReceiptAuthorityAdapter.class.getName() + ".verified";
    private static final Set<String> EXPLORER_ACTIONS = Set.of(
            JdbcHrisExplorerRepository.PREFERENCES_ACTION,
            JdbcHrisExplorerRepository.RECENT_ACTION);

    private final ObjectProvider<HrisPlatformReceiptAuthoritySource> sources;
    private final HrisExplorerRepository repository;
    private final Clock clock;

    @Autowired
    HrisPlatformReceiptAuthorityAdapter(
            ObjectProvider<HrisPlatformReceiptAuthoritySource> sources,
            HrisExplorerRepository repository) {
        this(sources, repository, Clock.systemUTC());
    }

    HrisPlatformReceiptAuthorityAdapter(
            ObjectProvider<HrisPlatformReceiptAuthoritySource> sources,
            HrisExplorerRepository repository,
            Clock clock) {
        this.sources = sources;
        this.repository = repository;
        this.clock = clock;
    }

    Optional<VerifiedReceiptAuthority> resolve(
            HrisExplorerAuthoritySource.AuthorityRequest request, UUID receiptId) {
        return resolve(request, receiptId, null);
    }

    Optional<VerifiedReceiptAuthority> resolve(
            HrisExplorerAuthoritySource.AuthorityRequest request,
            UUID receiptId,
            HrisExplorerAuthoritySource.AuthoritySnapshot explorerForwardedAuthority) {
        List<HrisPlatformReceiptAuthoritySource> available =
                sources.orderedStream().toList();
        if (available.size() != 1) return Optional.empty();
        HrisPlatformReceiptAuthoritySource source = available.getFirst();
        Optional<ForwardedReceiptAuthority> platformForwarded =
                source.resolveForwardedReceipt(request);
        boolean explorerForwarded = explorerForwardedAuthority != null;
        // The forwarded route must resolve to exactly one family before a row is read.
        if (platformForwarded.isPresent() == explorerForwarded
                || (platformForwarded.isPresent()
                && !validForwarded(platformForwarded.get(), request))
                || (explorerForwarded && !validExplorerForwarded(
                explorerForwardedAuthority, request))) {
            return Optional.empty();
        }
        Optional<HrisExplorerRepository.ReceiptAuthority> stored =
                repository.findReceiptAuthority(
                        request.tenantId(), request.subjectPrincipalPublicId(), receiptId);
        if (stored.isEmpty() || EXPLORER_ACTIONS.contains(
                stored.get().originatingAction())) {
            return Optional.empty();
        }
        Optional<CurrentReceiptAuthority> current = platformForwarded.isPresent()
                ? source.resolve(request, receiptId, stored.get())
                : source.resolveAfterExplorerForwarded(
                request, explorerForwardedAuthority, receiptId, stored.get());
        if (current.isEmpty() || !matches(
                current.get(), stored.get(), request.tenantId(),
                request.subjectPrincipalPublicId(), platformForwarded.orElse(null))) {
            return Optional.empty();
        }
        CurrentReceiptAuthority evidence = current.get();
        return Optional.of(new VerifiedReceiptAuthority(
                evidence.tenantId(), evidence.subjectPrincipalPublicId(),
                evidence.originatingAction(), stored.get().populationScopeDigest(),
                evidence.fieldPolicyRevision(), evidence.purposeCode(),
                evidence.authorizationRevision(), request.contextKey(),
                request.contextScopeKey(), request.currentDecisionRevision(),
                evidence.platformReceiptContextKey(),
                evidence.platformReceiptContextScopeKey(),
                evidence.platformReceiptDecisionRevision()));
    }

    private boolean validForwarded(
            ForwardedReceiptAuthority forwarded,
            HrisExplorerAuthoritySource.AuthorityRequest request) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return forwarded.tenantId() == request.tenantId()
                && request.subjectPrincipalPublicId().equals(
                forwarded.subjectPrincipalPublicId())
                && java.util.Objects.equals(request.contextKey(), forwarded.contextKey())
                && java.util.Objects.equals(
                request.contextScopeKey(), forwarded.contextScopeKey())
                && java.util.Objects.equals(
                request.currentDecisionRevision(), forwarded.decisionRevision())
                && forwarded.freshUntil() != null
                && forwarded.freshUntil().isAfter(now);
    }

    private boolean validExplorerForwarded(
            HrisExplorerAuthoritySource.AuthoritySnapshot authority,
            HrisExplorerAuthoritySource.AuthorityRequest request) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return authority.tenantId() == request.tenantId()
                && request.subjectPrincipalPublicId().equals(
                authority.subjectPrincipalPublicId())
                && java.util.Objects.equals(
                request.contextKey(), authority.contextKey())
                && java.util.Objects.equals(
                request.contextScopeKey(), authority.contextScopeKey())
                && java.util.Objects.equals(
                request.currentDecisionRevision(), authority.currentDecisionRevision())
                && authority.contextScopeKey() != null
                && authority.contextScopeKey().matches("^hcm-scope-[a-f0-9]{40}$")
                && authority.freshUntil() != null
                && authority.freshUntil().isAfter(now);
    }

    private boolean matches(
            CurrentReceiptAuthority current,
            HrisExplorerRepository.ReceiptAuthority stored,
            long tenantId, UUID subjectPrincipalPublicId,
            ForwardedReceiptAuthority platformForwarded) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return current.tenantId() == tenantId
                && subjectPrincipalPublicId.equals(current.subjectPrincipalPublicId())
                && current.originatingAction() != null
                && current.originatingAction().equals(stored.originatingAction())
                && !EXPLORER_ACTIONS.contains(current.originatingAction())
                && current.authorityPopulationScopeDigest() != null
                && current.authorityPopulationScopeDigest().matches("^[0-9a-f]{64}$")
                && current.authorityPopulationScopeDigest().equals(
                        stored.authorityPopulationScopeDigest())
                && current.fieldPolicyRevision() > 0
                && current.fieldPolicyRevision()
                == stored.authorityFieldPolicyRevision()
                && current.purposeCode() != null
                && !current.purposeCode().isBlank()
                && current.purposeCode().equals(current.purposeCode().strip())
                && current.purposeCode().length() >= 3
                && current.purposeCode().length() <= 64
                && current.purposeCode().equals(stored.purposeCode())
                && current.authorizationRevision() > 0
                && current.authorizationRevision()
                == stored.authorityAuthorizationRevision()
                && java.util.Objects.equals(current.originRouteContractKey(),
                stored.originRouteContractKey())
                && java.util.Objects.equals(current.originContextKey(),
                stored.originContextKey())
                && java.util.Objects.equals(current.originContextScopeKey(),
                stored.originContextScopeKey())
                && java.util.Objects.equals(current.originDecisionRevision(),
                stored.originDecisionRevision())
                && current.platformReceiptContextKey() != null
                && current.platformReceiptContextKey().matches("^psc-[a-f0-9]{64}$")
                && current.platformReceiptContextScopeKey() != null
                && current.platformReceiptContextScopeKey()
                .matches("^scope-[a-f0-9]{32}$")
                && current.platformReceiptDecisionRevision() != null
                && current.platformReceiptDecisionRevision()
                .matches("^psr-[a-f0-9]{64}$")
                && (platformForwarded == null || (
                java.util.Objects.equals(platformForwarded.contextKey(),
                        current.platformReceiptContextKey())
                        && java.util.Objects.equals(platformForwarded.contextScopeKey(),
                        current.platformReceiptContextScopeKey())
                        && java.util.Objects.equals(platformForwarded.decisionRevision(),
                        current.platformReceiptDecisionRevision())))
                && current.freshUntil() != null
                && current.freshUntil().isAfter(now);
    }

    record VerifiedReceiptAuthority(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String originatingAction,
            String populationScopeDigest,
            long fieldPolicyRevision,
            String purposeCode,
            long authorizationRevision,
            String requestContextKey,
            String requestContextScopeKey,
            String requestDecisionRevision,
            String platformReceiptContextKey,
            String platformReceiptContextScopeKey,
            String platformReceiptDecisionRevision) {
    }
}
