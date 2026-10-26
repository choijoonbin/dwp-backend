package com.dwp.services.platform.hrisconfiguration.integration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.dwp.core.event.DomainEventContractRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed payload contract for SYS-EVT-005. */
@Component
public final class Sys008EventContract {

    public static final String EVENT_ID = "SYS-EVT-005";
    public static final String EVENT_TYPE =
            "dwp.hris.connector.execution.state-changed.v1";
    public static final String ENVELOPE = "DEP-003_EVENT_ENVELOPE_OUTBOX_INBOX_V1";
    public static final String SOURCE = "urn:dwp:platform:hris:connector-execution";
    public static final String AGGREGATE_TYPE = "HRIS_CONNECTOR_EXECUTION";
    public static final String SCHEMA_RESOURCE =
            "/contracts/hris/sys008-event-payload.v1.schema.json";

    private static final int MAX_COUNT = 1_000_000_000;
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> STATES = Set.of(
            "ACCEPTED", "TRANSFORMING", "DISPATCHED", "RESULT_UNKNOWN",
            "ACKNOWLEDGED", "RECONCILING", "RECONCILED", "PARTIAL",
            "FAILED", "DEAD_LETTER", "CANCELLED");
    private static final Set<String> DIGEST_REQUIRED_STATES =
            Set.of("RECONCILED", "PARTIAL");

    public Sys008EventContract(DomainEventContractRegistry contracts) {
        contracts.register(EVENT_TYPE, 1, 1);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Payload(
            UUID executionPublicId,
            String state,
            int sourceCount,
            int acceptedCount,
            int rejectedCount,
            int quarantinedCount,
            String reconciliationDigest) {

        public Payload {
            if (executionPublicId == null) {
                throw new IllegalArgumentException(
                        "executionPublicId must not be null");
            }
            if (!STATES.contains(state)) {
                throw new IllegalArgumentException(
                        "state is not a sealed SYS-EVT-005 value");
            }
            requireCount(sourceCount, "sourceCount");
            requireCount(acceptedCount, "acceptedCount");
            requireCount(rejectedCount, "rejectedCount");
            requireCount(quarantinedCount, "quarantinedCount");
            if ((long) acceptedCount + rejectedCount > sourceCount) {
                throw new IllegalArgumentException(
                        "acceptedCount + rejectedCount must not exceed sourceCount");
            }
            if (quarantinedCount > rejectedCount) {
                throw new IllegalArgumentException(
                        "quarantinedCount must not exceed rejectedCount");
            }
            if (reconciliationDigest != null
                    && !DIGEST.matcher(reconciliationDigest).matches()) {
                throw new IllegalArgumentException(
                        "reconciliationDigest must be a lowercase SHA-256 digest");
            }
            if (DIGEST_REQUIRED_STATES.contains(state)
                    && reconciliationDigest == null) {
                throw new IllegalArgumentException(
                        "reconciliationDigest is required for terminal reconciliation");
            }
        }

        private static void requireCount(int value, String name) {
            if (value < 0 || value > MAX_COUNT) {
                throw new IllegalArgumentException(
                        name + " is outside SYS-EVT-005 bounds");
            }
        }
    }
}
