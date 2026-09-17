package com.dwp.services.platform.workplace.connectorops;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;

/** Provider-owned port. Implementations must not infer success from a transport timeout. */
public interface WorkplaceConnectorReplayAdapter {
    boolean supports(String provider, ConnectorKind kind);

    /**
     * Fail-closed readiness check. Production adapters must verify that the provider, endpoint,
     * opaque credential reference and secret-owner integration are all configured before a replay
     * can be accepted. Test and in-process adapters can keep the legacy support-only behavior.
     */
    default boolean ready(ProviderContext context) {
        return supports(context.provider(), context.kind());
    }

    default PreviewEstimate preview(
            long tenantId,
            ConnectorKind kind,
            String provider,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean failedOnly,
            int maximumRecords) {
        throw new UnsupportedOperationException("Provider replay preview is not implemented.");
    }

    default PreviewEstimate preview(
            ProviderContext context,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean failedOnly,
            int maximumRecords) {
        return preview(context.tenantId(), context.kind(), context.provider(), from, to,
                failedOnly, maximumRecords);
    }

    default DispatchResult dispatch(
            long tenantId,
            ConnectorKind kind,
            String provider,
            UUID jobId,
            UUID previewId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean failedOnly,
            int maximumRecords) {
        throw new UnsupportedOperationException("Provider replay dispatch is not implemented.");
    }

    default DispatchResult dispatch(
            ProviderContext context,
            UUID jobId,
            UUID previewId,
            OffsetDateTime from,
            OffsetDateTime to,
            boolean failedOnly,
            int maximumRecords) {
        return dispatch(context.tenantId(), context.kind(), context.provider(), jobId, previewId,
                from, to, failedOnly, maximumRecords);
    }

    /** Query by the stable DWP job id. Implementations must never redispatch from this method. */
    default LookupResult lookup(
            ProviderContext context,
            UUID jobId,
            String providerOperationReference) {
        throw new UnsupportedOperationException("Provider replay lookup is not implemented.");
    }

    /** Produce provider-owned runtime truth without accepting administrator supplied state. */
    default RuntimeObservation observe(ProviderContext context) {
        throw new UnsupportedOperationException("Provider runtime observation is not implemented.");
    }

    record ProviderContext(
            long tenantId,
            ConnectorKind kind,
            String provider,
            String credentialReference) { }

    record PreviewEstimate(long estimatedRecords, List<String> limitations) {
        public PreviewEstimate {
            if (estimatedRecords < 0) throw new IllegalArgumentException("estimatedRecords");
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    record DispatchResult(
            ReplayState state,
            String providerOperationReference,
            String resultCode) {
        public DispatchResult {
            if (state != ReplayState.RUNNING
                    && state != ReplayState.SUCCEEDED
                    && state != ReplayState.FAILED
                    && state != ReplayState.RESULT_UNKNOWN) {
                throw new IllegalArgumentException("Provider dispatch returned an unsupported state.");
            }
        }
    }

    record LookupResult(
            ReplayState state,
            String providerOperationReference,
            String resultCode) {
        public LookupResult {
            if (state != ReplayState.DISPATCHING
                    && state != ReplayState.RUNNING
                    && state != ReplayState.SUCCEEDED
                    && state != ReplayState.FAILED
                    && state != ReplayState.RESULT_UNKNOWN) {
                throw new IllegalArgumentException("Provider lookup returned an unsupported state.");
            }
        }
    }

    record RuntimeObservation(
            ProviderReportedState state,
            List<Capability> capabilities,
            OffsetDateTime sourceObservedAt,
            OffsetDateTime lastSuccessAt,
            Long lagSeconds,
            String checkpointReference,
            Long retryQueueDepth,
            Long deadLetterQueueDepth,
            String errorCode,
            String adapterId,
            String adapterVersion,
            String payloadFingerprint) {
        public RuntimeObservation {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }
    }
}
