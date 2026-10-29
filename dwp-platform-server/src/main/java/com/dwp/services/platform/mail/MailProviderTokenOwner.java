package com.dwp.services.platform.mail;

import com.dwp.platform.contract.MailConnectorPort;

import java.time.Instant;
import java.util.Arrays;

/** Deployment-owned boundary that leases a short-lived provider access token. */
public interface MailProviderTokenOwner {

    boolean supports(
            MailConnectorPort.ProviderFamily provider,
            MailConnectorPort.ConnectionContext context);

    AccessTokenLease lease(
            MailConnectorPort.ProviderFamily provider,
            MailConnectorPort.ConnectionContext context);

    final class AccessTokenLease implements AutoCloseable {
        private final char[] accessToken;
        private final Instant expiresAt;

        public AccessTokenLease(char[] accessToken, Instant expiresAt) {
            if (accessToken == null || accessToken.length < 16 || accessToken.length > 16_384) {
                throw new IllegalArgumentException("A provider token lease is invalid");
            }
            if (expiresAt == null || !expiresAt.isAfter(Instant.now().plusSeconds(5))) {
                throw new IllegalArgumentException("A provider token lease is already expired");
            }
            this.accessToken = accessToken.clone();
            this.expiresAt = expiresAt;
        }

        public char[] accessToken() {
            return accessToken.clone();
        }

        public Instant expiresAt() {
            return expiresAt;
        }

        @Override
        public void close() {
            Arrays.fill(accessToken, '\0');
        }
    }
}
