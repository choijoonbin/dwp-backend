package com.dwp.services.time.close;

import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import com.dwp.services.time.close.TimeCloseModels.StoredReceipt;
import com.dwp.services.time.commandreceipt.CommandReceiptLookup;
import com.dwp.services.time.commandreceipt.CommandReceiptProvider;
import java.util.Arrays;
import java.util.Objects;
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

    public TimeCloseCommandReceiptProvider(
            TimeCloseReceiptOwnership ownership,
            TimeCloseActivationGate activationGate) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean owns(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return lookup.providerAuthority(
                        PROVIDER_ID, VerifiedTimeCloseReceiptRequest.class).isPresent()
                && activationGate.allows(lookup.tenantId())
                && ownership.owns(lookup.tenantId(), lookup.receiptId());
    }

    @Override
    public Object receipt(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        VerifiedTimeCloseReceiptRequest authority = lookup.providerAuthority(
                        PROVIDER_ID, VerifiedTimeCloseReceiptRequest.class)
                .orElseThrow(TimeCloseCommandReceiptProvider::opaque);
        if (!activationGate.allows(lookup.tenantId())) throw opaque();
        return ownership.reauthorizedRead(
                        lookup.tenantId(), lookup.receiptId(),
                        stored -> permits(authority, stored))
                .orElseThrow(TimeCloseCommandReceiptProvider::opaque);
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
