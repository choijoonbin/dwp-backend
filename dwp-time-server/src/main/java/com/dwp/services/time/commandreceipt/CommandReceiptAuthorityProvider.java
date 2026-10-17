package com.dwp.services.time.commandreceipt;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/** Provider-specific authority projection for the shared command-receipt route. */
public interface CommandReceiptAuthorityProvider {

    /** Must equal the corresponding {@link CommandReceiptProvider#providerId()}. */
    String providerId();

    /** Returns sealed provider authority without probing receipt storage. */
    Optional<CommandReceiptAuthority> verify(HttpServletRequest request);
}
