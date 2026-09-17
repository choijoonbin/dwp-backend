package com.dwp.services.platform.workplace.connectorops;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.dwp.services.platform.workplace.WorkplaceSpatialGovernanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsDtos.*;
import static com.dwp.services.platform.workplace.connectorops.WorkplaceConnectorOpsRepository.ReplayJobRow;

/**
 * Executes provider replay calls between two independent database transactions. The durable
 * DISPATCHING state and stable job id make a crash recoverable through lookup without redispatch.
 */
@Component
public class WorkplaceConnectorReplayCoordinator {
    private static final Pattern RESULT_CODE = Pattern.compile("[A-Z0-9_.:-]{1,120}");
    private static final Pattern PROVIDER_REFERENCE = Pattern.compile("[A-Za-z0-9._:/-]{1,320}");

    private final WorkplaceConnectorOpsRepository repository;
    private final WorkplaceSpatialGovernanceRepository audits;
    private final ObjectMapper objectMapper;
    private final List<WorkplaceConnectorReplayAdapter> adapters;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    @Autowired
    public WorkplaceConnectorReplayCoordinator(
            WorkplaceConnectorOpsRepository repository,
            WorkplaceSpatialGovernanceRepository audits,
            ObjectMapper objectMapper,
            List<WorkplaceConnectorReplayAdapter> adapters,
            PlatformTransactionManager transactionManager) {
        this(repository, audits, objectMapper, adapters, transactionManager, Clock.systemUTC());
    }

    WorkplaceConnectorReplayCoordinator(
            WorkplaceConnectorOpsRepository repository,
            WorkplaceSpatialGovernanceRepository audits,
            ObjectMapper objectMapper,
            List<WorkplaceConnectorReplayAdapter> adapters,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.adapters = List.copyOf(adapters);
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ReplayJob dispatch(long tenantId, ConnectorKind kind, UUID jobId) {
        DispatchClaim claim = requiresNew.execute(status -> claimDispatch(tenantId, kind, jobId));
        if (claim == null) return replay(tenantId, kind, jobId);
        WorkplaceConnectorReplayAdapter.DispatchResult result;
        try {
            WorkplaceConnectorReplayAdapter adapter = requireAdapter(claim.context());
            ReplayPreview preview = claim.preview();
            result = adapter.dispatch(claim.context(), claim.job().jobId(), preview.previewId(),
                    preview.from(), preview.to(), preview.failedOnly(), preview.maximumRecords());
            validate(result.state(), result.providerOperationReference(), result.resultCode());
        } catch (RuntimeException uncertain) {
            result = new WorkplaceConnectorReplayAdapter.DispatchResult(
                    ReplayState.RESULT_UNKNOWN, null, "PROVIDER_DISPATCH_OUTCOME_UNKNOWN");
        }
        WorkplaceConnectorReplayAdapter.DispatchResult outcome = result;
        return Objects.requireNonNull(requiresNew.execute(
                status -> finalizeState(claim.job(), outcome.state(),
                        outcome.providerOperationReference(), outcome.resultCode())));
    }

    public ReplayJob reconcile(long tenantId, ConnectorKind kind, UUID jobId) {
        ReplayJobRow current = repository.replay(tenantId, kind, jobId)
                .orElseThrow(() -> notFound());
        if (!reconcilable(current.state())) return toReplayJob(current);
        WorkplaceConnectorReplayAdapter.ProviderContext context = context(current);
        WorkplaceConnectorReplayAdapter.LookupResult lookup;
        try {
            lookup = requireAdapter(context)
                    .lookup(context, current.jobId(), current.providerOperationReference());
            validate(lookup.state(), lookup.providerOperationReference(), lookup.resultCode());
        } catch (RuntimeException unavailableOrInvalid) {
            return scheduleReconciliation(current, ReplayState.RESULT_UNKNOWN,
                    current.providerOperationReference(), "PROVIDER_STATUS_UNAVAILABLE");
        }
        if (reconcilable(lookup.state())) {
            return scheduleReconciliation(current, lookup.state(),
                    lookup.providerOperationReference(), lookup.resultCode() == null
                            ? "PROVIDER_STATUS_PENDING" : lookup.resultCode());
        }
        return Objects.requireNonNull(requiresNew.execute(status -> finalizeState(
                current, lookup.state(), lookup.providerOperationReference(), lookup.resultCode())));
    }

    private ReplayJob scheduleReconciliation(
            ReplayJobRow expected,
            ReplayState state,
            String providerReference,
            String resultCode) {
        OffsetDateTime now = now();
        requiresNew.execute(status -> repository.scheduleReplayReconciliation(
                expected.tenantId(), expected.kind(), expected.jobId(), expected.version(), state,
                providerReference, resultCode, now));
        return replay(expected.tenantId(), expected.kind(), expected.jobId());
    }

    private DispatchClaim claimDispatch(long tenantId, ConnectorKind kind, UUID jobId) {
        ReplayJobRow queued = repository.replay(tenantId, kind, jobId)
                .orElseThrow(() -> notFound());
        if (queued.state() != ReplayState.QUEUED) return null;
        OffsetDateTime now = now();
        if (!repository.changeReplayState(tenantId, kind, jobId, queued.version(),
                List.of(ReplayState.QUEUED), ReplayState.DISPATCHING, null, null,
                now, null, now)) {
            return null;
        }
        ReplayJobRow dispatching = repository.replay(tenantId, kind, jobId)
                .orElseThrow(() -> notFound());
        ReplayPreview preview = repository.preview(tenantId, kind, queued.previewId())
                .orElseThrow(() -> conflict("The replay preview is no longer available."));
        return new DispatchClaim(dispatching, preview, context(dispatching));
    }

    private ReplayJob finalizeState(
            ReplayJobRow expected,
            ReplayState state,
            String providerReference,
            String resultCode) {
        ReplayJobRow current = repository.replay(
                        expected.tenantId(), expected.kind(), expected.jobId())
                .orElseThrow(() -> notFound());
        if (terminal(current.state())) return toReplayJob(current);
        if (!reconcilable(current.state())) return toReplayJob(current);
        OffsetDateTime now = now();
        OffsetDateTime finished = terminal(state) ? now : null;
        boolean changed = repository.changeReplayState(current.tenantId(), current.kind(),
                current.jobId(), current.version(), List.of(current.state()), state,
                providerReference, resultCode, current.startedAt(), finished, now);
        if (!changed) {
            return replay(current.tenantId(), current.kind(), current.jobId());
        }
        if (terminal(state)) {
            audits.appendAudit(current.tenantId(), 0L,
                    "workplace.connector.replay.terminal",
                    "WORKPLACE_CONNECTOR_REPLAY", current.jobId(), current.correlationId(),
                    objectMapper.valueToTree(new TerminalAuditSnapshot(
                            current.kind(), current.provider(), state.name(), resultCode,
                            hashOrNull(providerReference))));
        }
        return replay(current.tenantId(), current.kind(), current.jobId());
    }

    private WorkplaceConnectorReplayAdapter requireAdapter(
            WorkplaceConnectorReplayAdapter.ProviderContext context) {
        return adapters.stream().filter(candidate -> candidate.ready(context)).findFirst()
                .orElseThrow(() -> conflict("An approved replay adapter is not available."));
    }

    private static WorkplaceConnectorReplayAdapter.ProviderContext context(ReplayJobRow row) {
        return new WorkplaceConnectorReplayAdapter.ProviderContext(row.tenantId(), row.kind(),
                row.provider(), row.credentialReference());
    }

    private ReplayJob replay(long tenantId, ConnectorKind kind, UUID jobId) {
        return toReplayJob(repository.replay(tenantId, kind, jobId)
                .orElseThrow(() -> notFound()));
    }

    private static ReplayJob toReplayJob(ReplayJobRow row) {
        return new ReplayJob(row.jobId(), row.previewId(), row.kind(), row.provider(), row.state(),
                row.reason(), row.providerOperationReference(), row.resultSummary(),
                row.configurationVersion(), row.runtimeVersion(), row.version(), row.requestedAt(),
                row.startedAt(), row.finishedAt(), row.updatedAt());
    }

    private static void validate(ReplayState state, String reference, String resultCode) {
        if (state == null
                || (reference != null && !PROVIDER_REFERENCE.matcher(reference).matches())
                || (resultCode != null && !RESULT_CODE.matcher(resultCode).matches())) {
            throw new IllegalStateException("Provider returned an invalid replay receipt.");
        }
        if (terminal(state) && resultCode == null) {
            throw new IllegalStateException("A terminal provider receipt requires a result code.");
        }
    }

    private static boolean reconcilable(ReplayState state) {
        return state == ReplayState.DISPATCHING || state == ReplayState.RUNNING
                || state == ReplayState.RESULT_UNKNOWN;
    }

    private static boolean terminal(ReplayState state) {
        return state == ReplayState.SUCCEEDED || state == ReplayState.FAILED;
    }

    private static String hashOrNull(String value) {
        if (value == null) return null;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.RESOURCE_CONFLICT, message);
    }

    private static BaseException notFound() {
        return new BaseException(ErrorCode.NOT_FOUND,
                "The replay job was not found for this tenant and connector.");
    }

    private record DispatchClaim(
            ReplayJobRow job,
            ReplayPreview preview,
            WorkplaceConnectorReplayAdapter.ProviderContext context) { }

    private record TerminalAuditSnapshot(
            ConnectorKind kind,
            String provider,
            String state,
            String resultCode,
            String providerReferenceSha256) { }
}
