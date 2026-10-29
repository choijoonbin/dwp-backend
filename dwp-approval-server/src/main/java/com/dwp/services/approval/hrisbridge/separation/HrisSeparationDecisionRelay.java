package com.dwp.services.approval.hrisbridge.separation;

import com.dwp.core.http.OutboundHttpHeaders;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SeparationBridgeV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013WorkloadProofV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded exact-byte HRM-010 relay. No generic Kafka or legacy HRIS transport is used. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hrm013-bridge",
        name = {"enabled", "relay-enabled"}, havingValue = "true")
final class HrisSeparationDecisionRelay {
    private static final String PEOPLE_SPIFFE =
            "spiffe://dwp/service/dwp-people-server";
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final HrisSeparationRlsScope scope;
    private final HrisSeparationDecisionOutboxStore outbox;
    private final HrisSeparationWorkloadCredentialProvider credentials;
    private final AuthHrm013ServiceAuthorityClient authority;
    private final URI peopleBase;
    private final String workerId;
    private final int batchSize;
    private final int leaseSeconds;
    private final Clock clock;

    @Autowired
    HrisSeparationDecisionRelay(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            HrisSeparationWorkloadCredentialProvider credentials,
            AuthHrm013ServiceAuthorityClient authority,
            ObjectMapper mapper, Environment environment) {
        this(jdbc, manager, credentials, authority,
                requiredUri(environment, "dwp.approval.hrm013-bridge.people-url"),
                required(environment, "dwp.approval.hrm013-bridge.worker-id"),
                bounded(environment, "batch-size", 25, 1, 100),
                bounded(environment, "lease-seconds", 30, 5, 300),
                Clock.systemUTC());
    }

    HrisSeparationDecisionRelay(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            HrisSeparationWorkloadCredentialProvider credentials, URI peopleBase,
            String workerId, int batchSize, int leaseSeconds, Clock clock) {
        this(jdbc, manager, credentials, null, peopleBase, workerId,
                batchSize, leaseSeconds, clock);
    }

    HrisSeparationDecisionRelay(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager,
            HrisSeparationWorkloadCredentialProvider credentials,
            AuthHrm013ServiceAuthorityClient authority, URI peopleBase,
            String workerId, int batchSize, int leaseSeconds, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        this.scope = new HrisSeparationRlsScope(jdbc);
        var grants = new HrisSeparationGrantStore(jdbc, manager);
        this.outbox = new HrisSeparationDecisionOutboxStore(jdbc, manager, grants);
        this.credentials = java.util.Objects.requireNonNull(credentials);
        this.authority = authority;
        this.peopleBase = requireBase(peopleBase);
        this.workerId = boundedText(workerId, 160);
        if (batchSize < 1 || batchSize > 100 || leaseSeconds < 5
                || leaseSeconds > 300) throw new IllegalArgumentException();
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    @Scheduled(fixedDelayString =
            "${dwp.approval.hrm013-bridge.poll-delay-ms:2000}",
            initialDelayString =
                    "${dwp.approval.hrm013-bridge.initial-delay-ms:5000}")
    void relay() {
        int remaining = batchSize;
        for (long tenantId : activeTenants()) {
            while (remaining > 0) {
                var claim = outbox.claim(tenantId, workerId,
                        clock.instant().plusSeconds(leaseSeconds));
                if (claim.isEmpty()) break;
                remaining--;
                deliver(claim.get());
            }
            if (remaining <= 0) break;
        }
    }

    private List<Long> activeTenants() {
        return jdbc.queryForList("""
                SELECT tenant_id FROM apr_tenants
                 WHERE lifecycle_state='ACTIVE' ORDER BY tenant_id
                """, Map.of(), Long.class);
    }

    private void deliver(HrisSeparationPersistenceModels.DeliveryClaim claim) {
        try {
            var body = Hrm013SeparationBridgeV1.decodeWaiverDecision(
                    claim.exactCallbackBody());
            requireBody(claim, body);
            var grant = outbox.revalidateForSend(claim);
            var authorized = credentials(claim, body, grant);
            var selected = authorized.admission();
            var profile = Hrm013WorkloadProofV1.hrm010(
                    body.casePublicId(), body.taskPublicId(),
                    "APPROVED".equals(body.decision())
                            ? Hrm013WorkloadProofV1.WaiverEvidenceMode.APPROVED
                            : Hrm013WorkloadProofV1.WaiverEvidenceMode.REJECTED);
            String proof = credentials.businessProof(selected.snapshot(), profile,
                    body.correlationId(), claim.exactCallbackBody(),
                    claims(body), selected.mode(), selected.drain(),
                    selected.authorityExpiresAt());
            URI endpoint = peopleBase.resolve(claim.callbackPath().substring(1));
            HrisSeparationWorkloadCredentialProvider.requireEndpoint(
                    endpoint, claim.callbackPath());
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5)).POST(
                            HttpRequest.BodyPublishers.ofByteArray(
                                    claim.exactCallbackBody()));
            for (var header : Hrm013WorkloadProofV1.headers(
                    profile, proof, body.correlationId())) {
                builder.header(header.name(), header.value());
            }
            OutboundHttpHeaders.propagateObservability(builder);
            DrainRoot root = authorized.root();
            authority.recheckTerminalChild(selected, claim.tenantId(),
                    claim.grantId(), claim.tenantGrantRevision(),
                    claim.authorizationRevision(), grant.peopleSigningKeyId(),
                    claim.approvalSigningKeyId(), grant.supersedesGrantId(),
                    body.approvalRequestId(), root.requestedAt(),
                    body.originatingPayloadSha256(), body.callbackEventId(),
                    body.payloadSha256(), body.correlationId());
            var finalGrant = outbox.revalidateForSend(claim);
            if (!grant.equals(finalGrant)) {
                throw HrisSeparationProtocolException.unavailable();
            }
            credentials.requireLive(selected.authorityExpiresAt());
            HttpResponse<InputStream> response = credentials.client(
                    selected.snapshot()).send(builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            requireTrustedPeerAfterSend(response, endpoint, PEOPLE_SPIFFE);
            if (response.statusCode() != 200
                    || !response.headers().allValues("Content-Type")
                    .equals(List.of("application/json"))) {
                failHttp(claim, response.statusCode());
                close(response.body());
                return;
            }
            byte[] exact;
            try (InputStream input = response.body()) {
                exact = input.readNBytes(
                        Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES + 1);
            }
            if (exact.length > Hrm013SeparationBridgeV1.MAXIMUM_RESPONSE_BYTES) {
                throw new AmbiguousDelivery("RESPONSE_TOO_LARGE");
            }
            var receipt = Hrm013SeparationBridgeV1.decodeCallbackReceipt(exact);
            if (!body.callbackEventId().equals(receipt.callbackEventId())
                    || !"APPROVAL_DECISION".equals(receipt.callbackType())
                    || !body.casePublicId().equals(receipt.casePublicId())
                    || !body.taskPublicId().equals(receipt.taskPublicId())
                    || receipt.ownerRevision() != 1
                    || !"ACCEPTED".equals(receipt.outcome())
                    || !body.correlationId().equals(receipt.correlationId())
                    || !claim.commandDigest().equals(receipt.commandDigest())) {
                throw new AmbiguousDelivery("RECEIPT_BINDING_MISMATCH");
            }
            outbox.delivered(claim, workerId, exact, clock.instant());
        } catch (PermanentDelivery failure) {
            outbox.failed(claim, workerId, true, false,
                    failure.code, clock.instant());
        } catch (AmbiguousDelivery failure) {
            outbox.failed(claim, workerId, false, true, failure.code,
                    retryAt(claim));
        } catch (java.io.IOException failure) {
            outbox.failed(claim, workerId, false, true,
                    "TRANSPORT_RESULT_UNKNOWN", retryAt(claim));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            outbox.failed(claim, workerId, false, true,
                    "TRANSPORT_INTERRUPTED", retryAt(claim));
        } catch (HrisSeparationProtocolException | IllegalStateException failure) {
            outbox.failed(claim, workerId, false, false,
                    "AUTHORITY_UNAVAILABLE", retryAt(claim));
        } catch (RuntimeException failure) {
            outbox.failed(claim, workerId, false, true,
                    "INVALID_OR_AMBIGUOUS_RECEIPT", retryAt(claim));
        }
    }

    private AuthorizedDelivery credentials(
            HrisSeparationPersistenceModels.DeliveryClaim claim,
            Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 body,
            HrisSeparationPersistenceModels.TenantGrant grant) {
        if (authority == null) throw HrisSeparationProtocolException.unavailable();
        DrainRoot root = drainRoot(claim);
        if (!body.originatingPayloadSha256().equals(root.payloadSha256())) {
            throw HrisSeparationProtocolException.unavailable();
        }
        var admission = authority.authorizeTerminalChild(
                claim.tenantId(), claim.grantId(), claim.tenantGrantRevision(),
                claim.authorizationRevision(), grant.peopleSigningKeyId(),
                claim.approvalSigningKeyId(), grant.supersedesGrantId(),
                body.approvalRequestId(), root.requestedAt(),
                body.originatingPayloadSha256(), body.callbackEventId(),
                body.payloadSha256(), body.correlationId());
        return new AuthorizedDelivery(admission, root);
    }

    private DrainRoot drainRoot(HrisSeparationPersistenceModels.DeliveryClaim claim) {
        DrainRoot value = transactions.execute(status -> {
            scope.bind(claim.tenantId());
            var rows = jdbc.query("""
                    SELECT binding.requested_at,binding.approval_request_payload_sha256
                      FROM apr_hrm013_waiver_request_bindings binding
                      JOIN apr_hrm013_waiver_decision_outbox outbox
                        ON outbox.tenant_id=binding.tenant_id
                       AND outbox.approval_request_id=binding.approval_request_id
                     WHERE outbox.tenant_id=:tenant AND outbox.outbox_id=:outbox
                       AND outbox.grant_id=:grant
                       AND outbox.tenant_grant_revision=:tenantRevision
                       AND outbox.authorization_revision=:authorizationRevision
                     FOR SHARE OF binding,outbox
                    """, new MapSqlParameterSource("tenant", claim.tenantId())
                    .addValue("outbox", claim.outboxId())
                    .addValue("grant", claim.grantId())
                    .addValue("tenantRevision", claim.tenantGrantRevision())
                    .addValue("authorizationRevision",
                            claim.authorizationRevision()),
                    (row, number) -> new DrainRoot(
                            row.getTimestamp("requested_at").toInstant(),
                            row.getString("approval_request_payload_sha256")));
            if (rows.size() != 1) throw HrisSeparationProtocolException.unavailable();
            return rows.getFirst();
        });
        if (value == null) throw HrisSeparationProtocolException.unavailable();
        return value;
    }

    private static Map<String, Object> claims(
            Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 body) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("callbackEventId", body.callbackEventId());
        values.put("casePublicId", body.casePublicId());
        values.put("taskPublicId", body.taskPublicId());
        values.put("approvalRequestId", body.approvalRequestId());
        values.put("ownerRevision", body.ownerRevision());
        values.put("originatingPayloadSha256", body.originatingPayloadSha256());
        if (body.stepUpEvidenceId() != null) {
            values.put("stepUpEvidenceId", body.stepUpEvidenceId());
            values.put("stepUpEvidenceAudience", body.stepUpEvidenceAudience());
            values.put("stepUpEvidencePurpose", body.stepUpEvidencePurpose());
            values.put("stepUpEvidenceNonce", body.stepUpEvidenceNonce());
            values.put("stepUpEvidenceDigest", body.stepUpEvidenceDigest());
            values.put("stepUpVerifiedAt", body.stepUpVerifiedAt());
            values.put("stepUpConsumedAt", body.stepUpConsumedAt());
        }
        return values;
    }

    private static void requireBody(
            HrisSeparationPersistenceModels.DeliveryClaim claim,
            Hrm013SeparationBridgeV1.SeparationWaiverDecisionV2 body) {
        Hrm013SeparationBridgeV1.requireBodyBinding(
                Hrm013SeparationBridgeV1.HRM_010, body);
        Hrm013SeparationBridgeV1.requireStepUpEvidenceSha256(
                claim.tenantId(), body);
        if (!claim.originalCorrelationId().equals(body.correlationId())
                || !claim.callbackPath().equals(Hrm013SeparationBridgeV1.HRM_010
                        .path(body.casePublicId(), body.taskPublicId()))
                || !claim.callbackBodySha256().equals(
                        Hrm013SeparationBridgeV1.requestBodySha256(
                                claim.exactCallbackBody()))
                || !claim.commandDigest().equals(
                        Hrm013SeparationBridgeV1.commandDigest(
                                Hrm013SeparationBridgeV1.HRM_010,
                                claim.callbackPath(), claim.tenantId(),
                                claim.callbackBodySha256()))) {
            throw new PermanentDelivery("IMMUTABLE_CALLBACK_MISMATCH");
        }
    }

    private void failHttp(HrisSeparationPersistenceModels.DeliveryClaim claim,
            int status) {
        boolean permanent = status >= 400 && status < 500
                && status != 408 && status != 425 && status != 429;
        outbox.failed(claim, workerId, permanent, !permanent,
                "HTTP_" + status, permanent ? clock.instant() : retryAt(claim));
    }

    private Instant retryAt(HrisSeparationPersistenceModels.DeliveryClaim claim) {
        long ceiling = Math.min(300L,
                1L << Math.min(8, Math.max(1, claim.attemptCount())));
        return clock.instant().plusSeconds(
                ThreadLocalRandom.current().nextLong(ceiling + 1));
    }

    private static void close(InputStream input) {
        if (input == null) return;
        try { input.close(); } catch (java.io.IOException ignored) { }
    }

    /**
     * Once send has returned, a peer-identity failure cannot prove that People
     * did not commit the callback. Preserve that uncertainty and release the
     * response stream so bounded retries exhaust to RESULT_UNKNOWN.
     */
    static void requireTrustedPeerAfterSend(HttpResponse<InputStream> response,
            URI endpoint, String expectedSpiffe) {
        try {
            HrisSeparationWorkloadCredentialProvider.requirePeer(
                    response, endpoint, expectedSpiffe);
        } catch (RuntimeException untrustedPeer) {
            close(response.body());
            throw new AmbiguousDelivery("POST_SEND_PEER_IDENTITY_UNTRUSTED");
        }
    }

    private static URI requiredUri(Environment environment, String key) {
        return URI.create(required(environment, key));
    }

    private static URI requireBase(URI value) {
        if (value == null || !"https".equals(value.getScheme())
                || value.getHost() == null || value.getUserInfo() != null
                || value.getQuery() != null || value.getFragment() != null) {
            throw new IllegalArgumentException("HRM-013 People endpoint is invalid.");
        }
        return URI.create(value.toString().endsWith("/")
                ? value.toString() : value + "/");
    }

    private static String required(Environment environment, String key) {
        return boundedText(environment.getProperty(key, ""), 512);
    }

    private static String boundedText(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("HRM-013 relay setting is invalid.");
        }
        return value;
    }

    private static int bounded(Environment environment, String suffix,
            int fallback, int minimum, int maximum) {
        int value = environment.getProperty(
                "dwp.approval.hrm013-bridge." + suffix,
                Integer.class, fallback);
        if (value < minimum || value > maximum) throw new IllegalArgumentException();
        return value;
    }

    private record DrainRoot(Instant requestedAt, String payloadSha256) { }

    private record AuthorizedDelivery(
            AuthHrm013ServiceAuthorityClient.TerminalAdmission admission,
            DrainRoot root) { }

    private static final class PermanentDelivery extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private PermanentDelivery(String code) { this.code = code; }
    }

    private static final class AmbiguousDelivery extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private AmbiguousDelivery(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }
}
