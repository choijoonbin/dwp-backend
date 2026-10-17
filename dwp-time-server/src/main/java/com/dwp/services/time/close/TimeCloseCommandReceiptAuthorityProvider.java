package com.dwp.services.time.close;

import com.dwp.services.time.commandreceipt.CommandReceiptAuthority;
import com.dwp.services.time.commandreceipt.CommandReceiptAuthorityProvider;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Projects trusted shared-route evidence into the close provider digest domain. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.close.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimeCloseCommandReceiptAuthorityProvider
        implements CommandReceiptAuthorityProvider {

    private final GatewayVerifiedTimeCloseOwnerAuthoritySource authoritySource;

    TimeCloseCommandReceiptAuthorityProvider(
            GatewayVerifiedTimeCloseOwnerAuthoritySource authoritySource) {
        this.authoritySource = authoritySource;
    }

    @Override
    public String providerId() {
        return TimeCloseCommandReceiptProvider.PROVIDER_ID;
    }

    @Override
    public Optional<CommandReceiptAuthority> verify(HttpServletRequest request) {
        return authoritySource.verifyReceipt(request).map(verified ->
                new CommandReceiptAuthority(
                        verified.tenantId(), verified.correlationId(),
                        verified.decisionRevision(), verified.revalidateAt(), verified));
    }
}
