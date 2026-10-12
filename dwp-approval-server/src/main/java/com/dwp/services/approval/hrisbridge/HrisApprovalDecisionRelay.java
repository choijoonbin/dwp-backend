package com.dwp.services.approval.hrisbridge;

import com.dwp.platform.contracts.hris.approval.v2.HrisApprovalBridgeV2;
import com.dwp.platform.contracts.hris.command.v1.CommandReceiptV1;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Dedicated durable callback relay. It never publishes through the generic Kafka outbox. */
@Component
@ConditionalOnProperty(prefix = "dwp.approval.hris-bridge", name = {"enabled", "relay-enabled"},
        havingValue = "true")
final class HrisApprovalDecisionRelay {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RestClient people;
    private final ObjectMapper mapper;
    private final HrisApprovalTrustProfile.CallbackSigner signer;
    private final String workerId;
    private final int batchSize;
    private final int maximumAttempts;
    private final int leaseSeconds;

    HrisApprovalDecisionRelay(NamedParameterJdbcTemplate jdbc,
            PlatformTransactionManager manager, RestClient.Builder builder,
            ObjectMapper mapper, Environment environment) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        String peopleUrl = required(environment,
                "dwp.approval.hris-bridge.people-url");
        this.people = builder.clone()
                .defaultHeaders(org.springframework.http.HttpHeaders::clear)
                .baseUrl(peopleUrl).build();
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.signer = new HrisApprovalTrustProfile.CallbackSigner(
                this.mapper, Clock.systemUTC(), required(environment,
                "dwp.approval.hris-bridge.approval-private-jwk"));
        this.workerId = bounded(environment.getProperty(
                "dwp.approval.hris-bridge.worker-id", ""), 160, "worker-id");
        this.batchSize = boundedInt(environment, "batch-size", 25, 1, 100);
        this.maximumAttempts = boundedInt(environment, "maximum-attempts", 10, 1, 100);
        this.leaseSeconds = boundedInt(environment, "lease-seconds", 30, 5, 300);
    }

    @Scheduled(fixedDelayString = "${dwp.approval.hris-bridge.poll-delay-ms:2000}",
            initialDelayString = "${dwp.approval.hris-bridge.initial-delay-ms:5000}")
    void relay() {
        for (Delivery delivery : claim()) deliver(delivery);
    }

    private List<Delivery> claim() {
        List<Delivery> result = transactions.execute(status -> {
            jdbc.update("""
                    UPDATE apr_hris_employee_change_decision_outbox
                       SET delivery_state='RESULT_UNKNOWN',lease_token=NULL,
                           lease_owner=NULL,lease_until=NULL,last_failure_code='LEASE_EXPIRED_AT_LIMIT'
                     WHERE delivery_state='CLAIMED' AND lease_until<=clock_timestamp()
                       AND attempt_count>=:maximumAttempts
                    """, new MapSqlParameterSource("maximumAttempts", maximumAttempts));
            return jdbc.query("""
                WITH candidates AS (
                    SELECT outbox_id
                      FROM apr_hris_employee_change_decision_outbox
                     WHERE delivery_state IN ('PENDING','RETRY','CLAIMED')
                       AND available_at<=clock_timestamp()
                       AND (delivery_state<>'CLAIMED' OR lease_until<=clock_timestamp())
                       AND attempt_count<:maximumAttempts
                     ORDER BY available_at,created_at,outbox_id
                     FOR UPDATE SKIP LOCKED LIMIT :batchSize
                )
                UPDATE apr_hris_employee_change_decision_outbox outbox
                   SET delivery_state='CLAIMED',attempt_count=attempt_count+1,
                       lease_token=gen_random_uuid(),lease_owner=:worker,
                       lease_until=clock_timestamp()+make_interval(secs=>:leaseSeconds),
                       last_failure_code=NULL
                  FROM candidates WHERE outbox.outbox_id=candidates.outbox_id
                RETURNING outbox.outbox_id,outbox.tenant_id,outbox.callback_path,
                          outbox.callback_body_sha256,outbox.command_digest,
                          outbox.exact_callback_body,outbox.original_correlation_id,
                          outbox.lease_token,outbox.attempt_count
                """, new MapSqlParameterSource()
                .addValue("maximumAttempts", maximumAttempts)
                .addValue("batchSize", batchSize).addValue("worker", workerId)
                .addValue("leaseSeconds", leaseSeconds), (rs, row) -> new Delivery(
                rs.getObject("outbox_id", UUID.class), rs.getLong("tenant_id"),
                rs.getString("callback_path"), rs.getString("callback_body_sha256"),
                rs.getString("command_digest"), rs.getBytes("exact_callback_body"),
                rs.getObject("original_correlation_id", UUID.class),
                rs.getObject("lease_token", UUID.class), rs.getInt("attempt_count")));
        });
        return result == null ? List.of() : result;
    }

    private void deliver(Delivery delivery) {
        try {
            String proof = signer.sign(delivery.path, delivery.tenantId,
                    delivery.bodySha256);
            byte[] response = people.post().uri(delivery.path).headers(headers -> {
                headers.clear();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(List.of(MediaType.APPLICATION_JSON));
                headers.set(HrisWorkloadProofHeader.SERVICE_IDENTITY,
                        HrisApprovalBridgeV2.DECISION_CALLBACK.callerWorkloadId());
                headers.set(HrisWorkloadProofHeader.PROOF, proof);
            }).body(delivery.body).retrieve().body(byte[].class);
            CommandReceiptV1 receipt = receipt(response, delivery);
            byte[] exact = response.clone();
            complete(delivery, exact);
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            boolean transientClientFailure = status == 408 || status == 425 || status == 429;
            fail(delivery, failure.getStatusCode().is4xxClientError()
                    && !transientClientFailure, "HTTP_" + status);
        } catch (RestClientException failure) {
            fail(delivery, false, "TRANSPORT_UNAVAILABLE");
        } catch (RuntimeException failure) {
            // The People command may have committed before a malformed/truncated
            // receipt was observed. Stable idempotency permits a bounded retry.
            fail(delivery, false, "INVALID_COMMAND_RECEIPT");
        }
    }

    private CommandReceiptV1 receipt(byte[] bytes, Delivery delivery) {
        if (bytes == null || bytes.length == 0 || bytes.length > 65_536) {
            throw new IllegalArgumentException("Missing bounded command receipt.");
        }
        try {
            CommandReceiptV1 value = mapper.readValue(bytes, CommandReceiptV1.class);
            if (!HrisApprovalBridgeV2.CALLBACK_COMMAND_TYPE.equals(value.commandType())
                    || !HrisApprovalBridgeV2.CALLBACK_ORIGINATING_ACTION.equals(value.originatingAction())
                    || !HrisApprovalBridgeV2.CALLBACK_PURPOSE.equals(value.purposeCode())
                    || !"SUCCEEDED".equals(value.status())
                    || !delivery.correlationId.equals(value.correlationId())
                    || !delivery.commandDigest.equals(value.requestDigest())) {
                throw new IllegalArgumentException("Callback receipt binding mismatch.");
            }
            return value;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Callback receipt is invalid.", exception);
        }
    }

    private void complete(Delivery delivery, byte[] exactReceipt) {
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update("""
                    UPDATE apr_hris_employee_change_decision_outbox
                       SET delivery_state='DELIVERED',lease_token=NULL,lease_owner=NULL,lease_until=NULL,
                           exact_receipt_body=:receipt,
                           receipt_body_sha256=encode(sha256(:receipt),'hex'),
                           delivered_at=clock_timestamp(),last_failure_code=NULL
                     WHERE outbox_id=:outbox AND delivery_state='CLAIMED'
                       AND lease_token=:lease AND lease_owner=:worker
                    """, lease(delivery).addValue("receipt", exactReceipt));
            if (updated != 1) throw new IllegalStateException("HRIS callback lease was lost.");
        });
    }

    private void fail(Delivery delivery, boolean permanent, String code) {
        transactions.executeWithoutResult(status -> {
            boolean exhausted = delivery.attemptCount >= maximumAttempts;
            String next = permanent ? "DEAD" : exhausted ? "RESULT_UNKNOWN" : "RETRY";
            int delay = Math.min(300, 1 << Math.min(8, delivery.attemptCount));
            int updated = jdbc.update("""
                    UPDATE apr_hris_employee_change_decision_outbox
                       SET delivery_state=:next,lease_token=NULL,lease_owner=NULL,lease_until=NULL,
                           available_at=CASE WHEN :next='RETRY'
                               THEN clock_timestamp()+make_interval(secs=>:delay)
                               ELSE available_at END,last_failure_code=:code
                     WHERE outbox_id=:outbox AND delivery_state='CLAIMED'
                       AND lease_token=:lease AND lease_owner=:worker
                    """, lease(delivery).addValue("next", next)
                    .addValue("delay", delay).addValue("code", code));
            if (updated != 1) throw new IllegalStateException("HRIS callback lease was lost.");
        });
    }

    private MapSqlParameterSource lease(Delivery delivery) {
        return new MapSqlParameterSource().addValue("outbox", delivery.outboxId)
                .addValue("lease", delivery.leaseToken).addValue("worker", workerId);
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key, "");
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalStateException("Missing required HRIS relay property: " + key);
        }
        return value;
    }

    private static String bounded(String value, int limit, String name) {
        if (value == null || value.isBlank() || value.length() > limit
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("Invalid HRIS relay " + name + '.');
        }
        return value;
    }

    private static int boundedInt(Environment environment, String suffix,
            int fallback, int minimum, int maximum) {
        int value = environment.getProperty(
                "dwp.approval.hris-bridge." + suffix, Integer.class, fallback);
        if (value < minimum || value > maximum) {
            throw new IllegalStateException("Invalid HRIS relay " + suffix + '.');
        }
        return value;
    }

    private record Delivery(UUID outboxId, long tenantId, String path,
            String bodySha256, String commandDigest, byte[] body,
            UUID correlationId, UUID leaseToken, int attemptCount) { }

    private static final class HrisWorkloadProofHeader {
        private static final String SERVICE_IDENTITY = "X-DWP-Service-Identity";
        private static final String PROOF = "X-DWP-Workload-Proof";
    }
}
