package com.dwp.services.time.timecard;

import com.dwp.services.time.commandreceipt.CommandReceiptAuthority;
import com.dwp.services.time.commandreceipt.CommandReceiptAuthorityProvider;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Adapts the existing sealed Timecard receipt authority into the shared boundary. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.timecard.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimecardCommandReceiptAuthorityProvider
        implements CommandReceiptAuthorityProvider {

    private final TimecardOwnerAuthoritySource authoritySource;

    TimecardCommandReceiptAuthorityProvider(
            TimecardOwnerAuthoritySource authoritySource) {
        this.authoritySource = authoritySource;
    }

    @Override
    public String providerId() {
        return TimecardCommandReceiptProvider.PROVIDER_ID;
    }

    @Override
    public Optional<CommandReceiptAuthority> verify(HttpServletRequest request) {
        return authoritySource.verify(request, TimecardOwnerRoute.RECEIPT)
                .map(TimecardCommandReceiptAuthorityProvider::wrap);
    }

    private static CommandReceiptAuthority wrap(VerifiedTimecardRequest verified) {
        return new CommandReceiptAuthority(
                verified.tenantId(), verified.correlationId(),
                verified.decisionRevision(), verified.revalidateAt(), verified);
    }
}
