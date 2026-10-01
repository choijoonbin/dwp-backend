package com.dwp.services.time.workregime;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.dwp.services.time.workregime.WorkRegimeModels.Authority;

/**
 * Integration seam for gateway-verified TIM owner authority.
 *
 * <p>The production implementation accepts only the centrally governed Gateway route/PEP
 * evidence. Alternate implementations are test seams and must preserve this fail-closed
 * contract.</p>
 */
public interface WorkRegimeOwnerAuthoritySource {

    Optional<VerifiedRequest> verify(
            HttpServletRequest request, WorkRegimeOwnerRoute route);

    record VerifiedRequest(
            Authority authority,
            String contextScopeKey,
            String decisionRevision,
            Instant revalidateAt,
            UUID correlationId) {
        public VerifiedRequest {
            Objects.requireNonNull(authority, "authority must not be null");
            if (authority.scopeRefs().isEmpty()
                    || authority.scopeRefs().stream().anyMatch(scope ->
                    scope == null || !scope.equals(scope.trim()) || scope.isBlank()
                            || scope.length() > 500 || scope.indexOf(',') >= 0
                            || scope.indexOf('\r') >= 0 || scope.indexOf('\n') >= 0)) {
                throw new IllegalArgumentException("authority scopeRefs are not canonical");
            }
            contextScopeKey = canonical(contextScopeKey, "contextScopeKey", 500);
            decisionRevision = canonical(decisionRevision, "decisionRevision", 128);
            if (!decisionRevision.matches("psr-[0-9a-f]{64}")) {
                throw new IllegalArgumentException("decisionRevision is not canonical");
            }
            Objects.requireNonNull(revalidateAt, "revalidateAt must not be null");
            Objects.requireNonNull(correlationId, "correlationId must not be null");
        }

        private static String canonical(String value, String label, int maximum) {
            if (value == null || value.isBlank() || value.length() > maximum
                    || !value.equals(value.trim()) || value.indexOf(',') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException(label + " is not canonical");
            }
            return value;
        }
    }
}
