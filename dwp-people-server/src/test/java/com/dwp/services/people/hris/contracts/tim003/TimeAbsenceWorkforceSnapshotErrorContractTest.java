package com.dwp.services.people.hris.contracts.tim003;

import com.dwp.contracts.hris.xcon.v1.Xcon027EmploymentAbsenceSnapshotV1;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TimeAbsenceWorkforceSnapshotErrorContractTest {

    private static final UUID RELATIONSHIP =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-10-20T15:00:01.123456Z");

    @Test
    void unavailableSignerFallsBackToEmptyUncacheable503() {
        TimeAbsenceOwnerService service = service(
                new FailClosedTimeAbsenceOwnerAdapters.Signatures());
        TimeAbsenceOwnerController controller = new TimeAbsenceOwnerController(service);
        MockHttpServletRequest servletRequest = servletRequest();
        TimeAbsenceOwnerRequestContext.set(servletRequest, verified());

        ResponseEntity<?> response = controller.read(servletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getHeaders().getContentType()).isNull();
    }

    @Test
    void signedProblemMustExactlyBindStatusCodeRetryabilityCorrelationAndTime() {
        TimeAbsenceOwnerPorts.ResponseSigner signer = new TimeAbsenceOwnerPorts.ResponseSigner() {
            @Override
            public Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
                return Optional.empty();
            }

            @Override
            public Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    TimeAbsenceOwnerContract.Failure failure,
                    Instant issuedAt) {
                return Optional.of(problem(request, failure, issuedAt));
            }
        };
        TimeAbsenceOwnerService service = service(signer);

        TimeAbsenceOwnerContract.Problem problem = service.signedProblem(
                        verified(), TimeAbsenceOwnerContract.Failure.authorityStale())
                .orElseThrow();

        assertThat(problem.status()).isEqualTo(409);
        assertThat(problem.code()).isEqualTo("OWNER_AUTHORITY_STALE");
        assertThat(problem.retryable()).isTrue();
        assertThat(problem.correlationId()).isEqualTo(verified().correlationId());
        assertThat(problem.issuedAt()).isEqualTo(NOW);
    }

    @Test
    void signedProblemControllerUsesProblemJsonAndNoStoreHeaders() {
        TimeAbsenceOwnerPorts.ResponseSigner signer = new TimeAbsenceOwnerPorts.ResponseSigner() {
            @Override
            public Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
                return Optional.empty();
            }

            @Override
            public Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    TimeAbsenceOwnerContract.Failure failure,
                    Instant issuedAt) {
                return Optional.of(problem(request, failure, issuedAt));
            }
        };
        TimeAbsenceOwnerController controller = new TimeAbsenceOwnerController(service(signer));
        MockHttpServletRequest servletRequest = servletRequest();
        TimeAbsenceOwnerRequestContext.set(servletRequest, verified());

        ResponseEntity<?> response = controller.read(servletRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getPragma()).isEqualTo("no-cache");
        assertThat(response.getBody()).isInstanceOf(TimeAbsenceOwnerContract.Problem.class);
    }

    @Test
    void rejectsProblemDigestPayloadAndClosedJwsStructureMismatch() {
        for (String mode : List.of("digest", "payload", "structure")) {
            TimeAbsenceOwnerService service = service(malformedProblemSigner(mode));

            assertThat(service.signedProblem(
                    verified(), TimeAbsenceOwnerContract.Failure.authorityStale()))
                    .isEmpty();
        }
    }

    private static TimeAbsenceOwnerService service(
            TimeAbsenceOwnerPorts.ResponseSigner signer) {
        return new TimeAbsenceOwnerService(
                tenant -> Optional.empty(), request -> true,
                (request, grant) -> {
                    throw new AssertionError("owner facts must not be read without a current grant");
                }, signer, (request, outcome, digest) -> true,
                new TimeAbsenceOwnerCanonicalJson(
                        new ObjectMapper().findAndRegisterModules()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TimeAbsenceOwnerPorts.ResponseSigner malformedProblemSigner(String mode) {
        return new TimeAbsenceOwnerPorts.ResponseSigner() {
            @Override
            public Optional<TimeAbsenceOwnerContract.WorkloadResponseEvidence> signSuccess(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    Xcon027EmploymentAbsenceSnapshotV1 snapshot) {
                return Optional.empty();
            }

            @Override
            public Optional<TimeAbsenceOwnerContract.Problem> signProblem(
                    TimeAbsenceOwnerContract.VerifiedRequest request,
                    TimeAbsenceOwnerContract.Failure failure,
                    Instant issuedAt) {
                TimeAbsenceOwnerContract.Problem valid =
                        problem(request, failure, issuedAt);
                String digest = "digest".equals(mode)
                        ? "b".repeat(64) : valid.errorDigest();
                String payload = "payload".equals(mode)
                        ? "c".repeat(64) : digest;
                String signature = "structure".equals(mode)
                        ? "a.b.c"
                        : jws("dwp-workload-error+jws", valid.keyId(), payload);
                return Optional.of(new TimeAbsenceOwnerContract.Problem(
                        valid.status(), valid.code(), valid.retryable(),
                        valid.correlationId(), valid.issuedAt(), digest,
                        valid.keyId(), signature));
            }
        };
    }

    private static TimeAbsenceOwnerContract.VerifiedRequest verified() {
        TimeAbsenceOwnerContract.Request ownerRequest =
                new TimeAbsenceOwnerContract.Request(
                        RELATIONSHIP, 7L, LocalDate.parse("2026-10-21"));
        Instant issuedAt = Instant.parse("2026-10-20T15:00:00Z");
        UUID jti = UUID.fromString("40000000-0000-4000-8000-000000000001");
        String queryFingerprint = TimeAbsenceOwnerIngressFilter
                .route(servletRequest()).queryFingerprint();
        return new TimeAbsenceOwnerContract.VerifiedRequest(
                "GET", TimeAbsenceOwnerContract.PATH_PREFIX + RELATIONSHIP,
                queryFingerprint, TimeAbsenceOwnerContract.BINDING_ID,
                jti, UUID.fromString("50000000-0000-4000-8000-000000000001"),
                jti, issuedAt, issuedAt.plusSeconds(30), 71L,
                TimeAbsenceOwnerContract.CALLER_SPIFFE,
                TimeAbsenceOwnerContract.AUDIENCE, "time-key-2026-10",
                3L, "Asia/Seoul", TimeAbsenceOwnerContract.PURPOSE,
                TimeAbsenceOwnerContract.PROJECTION, 4L, 5L, ownerRequest);
    }

    private static MockHttpServletRequest servletRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", TimeAbsenceOwnerContract.PATH_PREFIX + RELATIONSHIP);
        request.setQueryString(
                "minimumRelationshipRevision=7&effectiveOn=2026-10-21");
        request.setParameter("minimumRelationshipRevision", "7");
        request.setParameter("effectiveOn", "2026-10-21");
        request.addHeader("X-Correlation-ID",
                "40000000-0000-4000-8000-000000000001");
        return request;
    }

    private static TimeAbsenceOwnerContract.Problem problem(
            TimeAbsenceOwnerContract.VerifiedRequest request,
            TimeAbsenceOwnerContract.Failure failure,
            Instant issuedAt) {
        String keyId = "response-key";
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", failure.status());
        fields.put("code", failure.code());
        fields.put("retryable", failure.retryable());
        fields.put("correlationId", request.correlationId());
        fields.put("issuedAt", issuedAt);
        fields.put("keyId", keyId);
        String digest = new TimeAbsenceOwnerCanonicalJson(
                new ObjectMapper().findAndRegisterModules()).digest(fields);
        return new TimeAbsenceOwnerContract.Problem(
                failure.status(), failure.code(), failure.retryable(),
                request.correlationId(), issuedAt, digest, keyId,
                jws("dwp-workload-error+jws", keyId, digest));
    }

    private static String jws(String type, String keyId, String digest) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = "{\"alg\":\"RS256\",\"typ\":\"" + type
                + "\",\"kid\":\"" + keyId + "\"}";
        byte[] signature = new byte[256];
        Arrays.fill(signature, (byte) 1);
        return encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(digest.getBytes(StandardCharsets.US_ASCII)) + "."
                + encoder.encodeToString(signature);
    }
}
