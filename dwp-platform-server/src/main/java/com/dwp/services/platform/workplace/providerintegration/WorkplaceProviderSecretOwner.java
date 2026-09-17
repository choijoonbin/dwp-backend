package com.dwp.services.platform.workplace.providerintegration;

import java.util.Arrays;

/**
 * Boundary implemented by the deployment's secret manager integration. Callers persist only an
 * opaque secret-manager reference; raw credentials are leased for a single outbound request.
 */
public interface WorkplaceProviderSecretOwner {
    boolean supports(String opaqueReference);

    SecretLease lease(String opaqueReference);

    final class SecretLease implements AutoCloseable {
        private final char[] bearerToken;

        public SecretLease(char[] bearerToken) {
            if (bearerToken == null || bearerToken.length < 16) {
                throw new IllegalArgumentException("A provider credential lease is invalid.");
            }
            this.bearerToken = bearerToken.clone();
        }

        public char[] bearerToken() {
            return bearerToken.clone();
        }

        @Override
        public void close() {
            Arrays.fill(bearerToken, '\0');
        }
    }
}
