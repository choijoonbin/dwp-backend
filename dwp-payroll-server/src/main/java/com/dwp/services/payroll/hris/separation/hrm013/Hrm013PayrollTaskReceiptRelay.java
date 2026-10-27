package com.dwp.services.payroll.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable, authority-rechecked HRM-008 callback relay. */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "dwp.payroll.hrm013-task-dispatch", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public final class Hrm013PayrollTaskReceiptRelay {
    private static final String PEOPLE_SPIFFE = "spiffe://dwp/service/dwp-people-server";
    static final String RESULT_UNKNOWN = "RESULT_UNKNOWN";
    private final Hrm013PayrollTaskDispatchStore store;
    private final AuthHrm013ServiceAuthorityClient authority;
    private final Hrm013PayrollWorkloadCredentialProvider credentials;
    private final URI peopleBase;
    private final Clock clock;
    private final Duration lease;
    private final int maximumAttempts;
    private final TenantWorkSource tenants;
    private final int maximumClaimsPerPoll;
    private final String workerId;

    @FunctionalInterface
    public interface TenantWorkSource {
        List<Long> enabledTenantIds();
    }

    @Autowired
    Hrm013PayrollTaskReceiptRelay(Hrm013PayrollTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013PayrollWorkloadCredentialProvider credentials,
            @Value("${dwp.payroll.hrm013-task-dispatch.people-url:}") String peopleUrl,
            @Value("${dwp.payroll.hrm013-task-dispatch.relay-lease-seconds:30}")
                    long leaseSeconds,
            @Value("${dwp.payroll.hrm013-task-dispatch.relay-max-attempts:10}")
                    int maximumAttempts,
            ObjectProvider<TenantWorkSource> tenantSources,
            @Value("${dwp.payroll.hrm013-task-dispatch.relay-max-claims-per-poll:8}")
                    int maximumClaimsPerPoll) {
        this(store, authority, credentials, peopleUrl, Clock.systemUTC(),
                Duration.ofSeconds(leaseSeconds), maximumAttempts,
                onlyTenantSource(tenantSources), maximumClaimsPerPoll,
                "hrm013-payroll-" + UUID.randomUUID());
    }

    Hrm013PayrollTaskReceiptRelay(Hrm013PayrollTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013PayrollWorkloadCredentialProvider credentials,
            String peopleUrl, Clock clock, Duration lease, int maximumAttempts) {
        this(store, authority, credentials, peopleUrl, clock, lease, maximumAttempts,
                List::of, 1, "hrm013-payroll-test");
    }

    Hrm013PayrollTaskReceiptRelay(Hrm013PayrollTaskDispatchStore store,
            AuthHrm013ServiceAuthorityClient authority,
            Hrm013PayrollWorkloadCredentialProvider credentials,
            String peopleUrl, Clock clock, Duration lease, int maximumAttempts,
            TenantWorkSource tenants, int maximumClaimsPerPoll, String workerId) {
        this.store = Objects.requireNonNull(store);
        this.authority = Objects.requireNonNull(authority);
        this.credentials = Objects.requireNonNull(credentials);
        this.clock = Objects.requireNonNull(clock);
        this.lease = Objects.requireNonNull(lease);
        if (lease.compareTo(Duration.ofSeconds(1)) < 0
                || lease.compareTo(Duration.ofMinutes(5)) > 0
                || maximumAttempts
                        != Hrm013PayrollTaskDispatchStore.MAXIMUM_CALLBACK_ATTEMPTS) {
            throw new IllegalArgumentException("HRM-013 relay limits are invalid.");
        }
        this.maximumAttempts = maximumAttempts;
        this.tenants = Objects.requireNonNull(tenants);
        if (maximumClaimsPerPoll < 1 || maximumClaimsPerPoll > 100
                || workerId == null || workerId.isBlank() || workerId.length() > 160) {
            throw new IllegalArgumentException("HRM-013 relay polling bounds are invalid.");
        }
        this.maximumClaimsPerPoll = maximumClaimsPerPoll;
        this.workerId = workerId;
        if (peopleUrl == null || peopleUrl.isBlank()) {
            throw new IllegalStateException(
                    "Enabled HRM-013 Payroll runtime requires the People HTTPS endpoint.");
        }
        this.peopleBase = URI.create(peopleUrl.endsWith("/") ? peopleUrl : peopleUrl + "/");
        if (!"https".equals(peopleBase.getScheme()) || peopleBase.getHost() == null
                || peopleBase.getUserInfo() != null || peopleBase.getQuery() != null
                || peopleBase.getFragment() != null) {
            throw new IllegalArgumentException("People endpoint must be exact HTTPS.");
        }
    }

    @Scheduled(
            fixedDelayString =
                    "${dwp.payroll.hrm013-task-dispatch.relay-delay-ms:2000}",
            initialDelayString =
                    "${dwp.payroll.hrm013-task-dispatch.relay-initial-delay-ms:5000}")
    void poll() {
        int claimed = 0;
        List<Long> enabled = List.copyOf(tenants.enabledTenantIds());
        if (enabled.size() > 256
                || enabled.stream().anyMatch(id -> id == null || id < 1)
                || new LinkedHashSet<>(enabled).size() != enabled.size()) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        for (long tenantId : enabled) {
            while (claimed < maximumClaimsPerPoll) {
                try {
                    if (!relayTenant(tenantId, workerId)) break;
                    claimed++;
                } catch (RuntimeException unavailableTenant) {
                    break;
                }
            }
            if (claimed == maximumClaimsPerPoll) return;
        }
    }

    /** One bounded unit of work for an explicitly enrolled tenant. */
    public boolean relayTenant(long tenantId, String workerId) {
        if (tenantId < 1 || workerId == null || workerId.isBlank()
                || workerId.length() > 160) {
            throw new IllegalArgumentException("HRM-013 relay identity is invalid.");
        }
        Instant now = clock.instant();
        Optional<Hrm013PayrollTaskDispatchStore.CallbackClaim> selected =
                store.claimNext(tenantId, workerId, now, lease);
        if (selected.isEmpty()) return false;
        var claim = selected.orElseThrow();
        try {
            deliver(claim);
            store.delivered(claim);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            retry(claim, "TRANSPORT_INTERRUPTED");
        } catch (java.io.IOException failure) {
            retry(claim, "TRANSPORT_RESULT_UNKNOWN");
        } catch (RuntimeException failure) {
            retry(claim, "AUTHORITY_OR_RECEIPT_UNAVAILABLE");
        }
        return true;
    }

    private void deliver(Hrm013PayrollTaskDispatchStore.CallbackClaim claim)
            throws java.io.IOException, InterruptedException {
        var body = Hrm013SeparationBridgeV1.decodeTaskReceipt(
                claim.exactCallbackBytes());
        requireStoredCallback(claim, body);
        String path = "/internal/hris-people/v1/separation-cases/"
                + body.casePublicId() + "/tasks/" + body.taskPublicId() + "/receipts";
        var profile = Hrm013WorkloadProofV1.hrm008(
                Hrm013WorkloadProofV1.TaskOwner.PAYROLL,
                body.casePublicId(), body.taskPublicId());
        Hrm013WorkloadProofV1.DrainClaims terminalDrain = terminalDrain(claim, body);
        var admission = authority.authorizeCallback(profile, claim.tenantId(),
                claim.grantUseMode(), terminalDrain, claim.correlationId());
        var admitted = admission.authority();
        Map<String, Object> profileClaims = Map.of(
                "callbackEventId", body.callbackEventId(),
                "casePublicId", body.casePublicId(),
                "taskPublicId", body.taskPublicId(),
                "ownerRevision", body.ownerRevision(),
                "originatingPayloadSha256", body.originatingPayloadSha256());
        String proof = credentials.businessProof(admitted.snapshot(), profile,
                body.correlationId(), claim.exactCallbackBytes(), profileClaims,
                admission.mode(), admission.drain(), admitted.authorityExpiresAt());
        URI endpoint = peopleBase.resolve(path.substring(1));
        Hrm013PayrollWorkloadCredentialProvider.requireEndpoint(endpoint, path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(claim.exactCallbackBytes()));
        for (var header : Hrm013WorkloadProofV1.headers(
                profile, proof, body.correlationId())) {
            builder.header(header.name(), header.value());
        }
        var rechecked = authority.resolveCallbackProfile(profile, claim.tenantId(),
                admission.mode(), admission.drain(), claim.correlationId());
        if (!admitted.sameAuthority(rechecked)
                || !clock.instant().isBefore(rechecked.authorityExpiresAt())) {
            throw new IllegalStateException("HRM-008 authority changed before send.");
        }
        HttpResponse<InputStream> response = credentials.client(rechecked.snapshot()).send(
                builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        try {
            Hrm013PayrollWorkloadCredentialProvider.requirePeer(
                    response, endpoint, PEOPLE_SPIFFE);
        } catch (RuntimeException failure) {
            close(response.body()); throw failure;
        }
        if (response.statusCode() != 200
                || !response.headers().allValues("Content-Type")
                        .equals(List.of("application/json"))) {
            close(response.body());
            throw new IllegalStateException("People rejected HRM-008 callback.");
        }
        byte[] exact;
        try (InputStream input = response.body()) {
            exact = input.readNBytes(Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES + 1);
        }
        if (exact.length > Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES) {
            throw new IllegalStateException("HRM-008 receipt exceeded its bound.");
        }
        var receipt = Hrm013SeparationBridgeV1.decodeCallbackReceipt(exact);
        String commandDigest = Hrm013SeparationBridgeV1.commandDigest(
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL, path, claim.tenantId(),
                Hrm013SeparationBridgeV1.requestBodySha256(claim.exactCallbackBytes()));
        if (!body.callbackEventId().equals(receipt.callbackEventId())
                || !"TASK_RECEIPT".equals(receipt.callbackType())
                || !body.casePublicId().equals(receipt.casePublicId())
                || !body.taskPublicId().equals(receipt.taskPublicId())
                || body.ownerRevision() != receipt.ownerRevision()
                || !"ACCEPTED".equals(receipt.outcome())
                || !body.correlationId().equals(receipt.correlationId())
                || !commandDigest.equals(receipt.commandDigest())) {
            throw new IllegalStateException("HRM-008 receipt binding mismatch.");
        }
    }

    static Hrm013WorkloadProofV1.DrainClaims terminalDrain(
            Hrm013PayrollTaskDispatchStore.CallbackClaim claim,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 body) {
        UUID root = Objects.requireNonNull(claim.drainRootWorkItemId());
        Instant rootAt = Objects.requireNonNull(claim.drainRootCreatedAt());
        String rootDigest = Objects.requireNonNull(claim.drainRootPayloadSha256());
        if (claim.grantUseMode() == Hrm013WorkloadProofV1.GrantUseMode.NEW_WORK
                && (!root.equals(claim.dispatchEventId())
                    || !rootAt.equals(claim.terminalParentCreatedAt())
                    || !rootDigest.equals(claim.originatingPayloadSha256()))) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        UUID expected = Hrm013WorkloadProofV1.terminalChildEventId(
                claim.tenantId(), root, body.ownerRevision(),
                Hrm013SeparationBridgeV1.HRM_008_PAYROLL.bindingId());
        if (!expected.equals(body.callbackEventId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return new Hrm013WorkloadProofV1.DrainClaims(
                root, rootAt, rootDigest,
                Hrm013WorkloadProofV1.DrainContinuationKind.TERMINAL_CHILD,
                body.callbackEventId(), body.ownerRevision(), body.payloadSha256());
    }

    private void retry(Hrm013PayrollTaskDispatchStore.CallbackClaim claim,
            String failureCode) {
        boolean terminal = claim.attemptCount() >= maximumAttempts;
        long delay = terminal ? 0
                : boundedFullJitterSeconds(
                        claim.callbackEventId(), claim.attemptCount());
        store.retry(claim, delay, terminal ? RESULT_UNKNOWN : failureCode, terminal);
    }

    static void requireStoredCallback(
            Hrm013PayrollTaskDispatchStore.CallbackClaim claim,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 body) {
        if (!body.callbackEventId().equals(claim.callbackEventId())
                || !body.casePublicId().equals(claim.casePublicId())
                || !body.taskPublicId().equals(claim.taskPublicId())
                || !body.taskType().equals(claim.taskType())
                || !body.status().equals(claim.taskStatus())
                || body.ownerRevision() != claim.ownerRevision()
                || !body.occurredAt().equals(claim.occurredAt())
                || !body.payloadSha256().equals(claim.callbackPayloadSha256())
                || !body.originatingPayloadSha256()
                        .equals(claim.originatingPayloadSha256())
                || !body.correlationId().equals(claim.correlationId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    static long boundedFullJitterSeconds(UUID callbackEventId, int attemptCount) {
        Objects.requireNonNull(callbackEventId);
        if (attemptCount < 1
                || attemptCount
                        >= Hrm013PayrollTaskDispatchStore.MAXIMUM_CALLBACK_ATTEMPTS) {
            throw new IllegalArgumentException("Bounded callback attempt required.");
        }
        long cap = Math.min(300L, 1L << Math.min(attemptCount, 9));
        long mixed = callbackEventId.getMostSignificantBits()
                ^ Long.rotateLeft(callbackEventId.getLeastSignificantBits(), 17)
                ^ (0x9E3779B97F4A7C15L * attemptCount);
        return Math.floorMod(mixed, cap + 1);
    }

    private static TenantWorkSource onlyTenantSource(
            ObjectProvider<TenantWorkSource> sources) {
        List<TenantWorkSource> configured = sources.orderedStream().toList();
        if (configured.size() != 1) {
            throw new IllegalStateException(
                    "Enabled HRM-013 Payroll relay requires one tenant work source.");
        }
        return configured.getFirst();
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (java.io.IOException ignored) { }
    }
}
