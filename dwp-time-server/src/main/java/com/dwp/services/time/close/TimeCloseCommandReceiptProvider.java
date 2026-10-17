package com.dwp.services.time.close;

import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.commandreceipt.CommandReceiptLookup;
import com.dwp.services.time.commandreceipt.CommandReceiptProvider;
import com.dwp.services.time.commandreceipt.VerifiedCommandReceiptRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Close-domain adapter for unique shared command-receipt owner discovery. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimeCloseCommandReceiptProvider implements CommandReceiptProvider {

    public static final String PROVIDER_ID = "close";

    private final TimeCloseReceiptOwnership ownership;
    private final TimeCloseActivationGate activationGate;
    private final Clock clock;

    @Autowired
    public TimeCloseCommandReceiptProvider(
            TimeCloseReceiptOwnership ownership,
            TimeCloseActivationGate activationGate) {
        this(ownership, activationGate, Clock.systemUTC());
    }

    TimeCloseCommandReceiptProvider(
            TimeCloseReceiptOwnership ownership,
            TimeCloseActivationGate activationGate,
            Clock clock) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean owns(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return freshAuthority(lookup).isPresent()
                && activationGate.allows(lookup.tenantId())
                && ownership.owns(lookup.tenantId(), lookup.receiptId());
    }

    @Override
    public Object receipt(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        VerifiedTimeCloseReceiptRequest authority = freshAuthority(lookup)
                .orElseThrow(TimeCloseCommandReceiptProvider::opaque);
        if (!activationGate.allows(lookup.tenantId())) throw opaque();
        return ownership.reauthorizedRead(
                        lookup.tenantId(), lookup.receiptId(),
                        stored -> permits(authority, stored))
                .orElseThrow(TimeCloseCommandReceiptProvider::opaque);
    }

    private Optional<VerifiedTimeCloseReceiptRequest> freshAuthority(
            CommandReceiptLookup lookup) {
        if (!(lookup.authorityEvidence() instanceof VerifiedCommandReceiptRequest common)) {
            return Optional.empty();
        }
        VerifiedTimeCloseReceiptRequest provider = lookup.providerAuthority(
                        PROVIDER_ID, VerifiedTimeCloseReceiptRequest.class)
                .orElse(null);
        Instant now = clock.instant();
        if (!common.revalidateAt().isAfter(now)
                || provider == null
                || !provider.revalidateAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(provider);
    }

    private static boolean permits(
            VerifiedTimeCloseReceiptRequest authority, StoredReceipt stored) {
        TimeCloseOwnerRoute route = Arrays.stream(TimeCloseOwnerRoute.values())
                .filter(TimeCloseOwnerRoute::command)
                .filter(candidate -> candidate.routeContractKey()
                        .equals(stored.routeContractKey()))
                .filter(candidate -> candidate.action().name().equals(
                        actionName(stored.receipt().originatingAction())))
                .findFirst().orElse(null);
        return route != null
                && stored.receipt().subjectPrincipalPublicId()
                        .equals(authority.subjectPrincipalPublicId())
                && stored.populationPublicId().equals(authority.populationPublicId())
                && stored.populationRevision() == authority.populationRevision()
                && stored.grantRevision() == authority.grantRevision()
                && stored.receipt().populationScopeDigest()
                        .equals(authority.populationScopeDigest())
                && stored.receipt().fieldPolicyRevision()
                        == authority.fieldPolicyRevision()
                && stored.receipt().authorizationRevision()
                        == authority.authorizationRevision()
                && stored.receipt().purposeCode().equals(route.purpose())
                && stored.requiredPermission().equals(route.permission())
                && authority.permissions().contains(stored.requiredPermission())
                && (route.responsibility() == null
                    || authority.responsibilities().contains(route.responsibility()));
    }

    private static String actionName(String action) {
        return switch (action) {
            case "TIME_PERIOD_VALIDATE" -> "VALIDATE";
            case "TIME_PERIOD_CLOSE" -> "CLOSE";
            case "TIME_PERIOD_REOPEN" -> "REOPEN";
            case "TIME_PAYROLL_HANDOFF" -> "HANDOFF_CREATE";
            default -> "";
        };
    }

    private static HrisTimeApiException opaque() {
        return HrisTimeApiException.of(HrisTimeProblemCode.TARGET_NOT_DISCLOSED);
    }
}
