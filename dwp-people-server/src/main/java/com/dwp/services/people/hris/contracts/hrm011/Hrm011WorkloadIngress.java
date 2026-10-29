package com.dwp.services.people.hris.contracts.hrm011;

import com.dwp.contracts.hris.xcon.v1.Xcon017WorkerDependentEligibilitySnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon018WorkerTaxIdentitySnapshotV1;
import com.dwp.contracts.hris.xcon.v1.Xcon019WorkerBankAccountTokenSnapshotV1;
import com.dwp.platform.contract.AuditEventEnvelope;
import com.dwp.platform.contract.DataClassification;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011WorkloadContract.SnapshotRequest;
import com.dwp.services.people.hris.contracts.hrm011.Hrm011WorkloadContract.TrustedWorkload;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractCanonicalizer;
import com.dwp.services.people.hris.employeeservice.employmentcontract.EmploymentContractException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Dedicated SVC-003/SVC-005 handler consuming only filter-established authority. */
@Service
@ConditionalOnProperty(prefix = "dwp.hris.contract-compensation-workbench",
        name = "enabled", havingValue = "true", matchIfMissing = false)
public class Hrm011WorkloadIngress {
    public static final String PAYROLL_CALLER = Hrm011WorkloadContract.PAYROLL_CALLER;
    public static final String AUDIENCE = Hrm011WorkloadContract.AUDIENCE;
    public static final String SVC003_OPERATION = "bootstrapCompensationBasisSnapshots";
    public static final String SVC005_OPERATION = "bootstrapPayrollWorkerFactSnapshots";
    public static final String SVC003_PURPOSE = Hrm011WorkloadContract.SVC003_PURPOSE;
    public static final String SVC005_PURPOSE = Hrm011WorkloadContract.SVC005_PURPOSE;
    public static final String COMPENSATION = Hrm011WorkloadContract.COMPENSATION;
    public static final Set<String> PAYROLL_PROJECTIONS =
            Hrm011WorkloadContract.PAYROLL_PROJECTIONS;

    private final PayrollWorkerFactSource payrollFacts;
    private final EmploymentContractCanonicalizer canonicalizer;
    private final ObjectMapper mapper;
    private final Hrm011WorkloadAuditPort audit;
    private final byte[] cursorKey;
    private final Clock clock;

    public Hrm011WorkloadIngress(
            PayrollWorkerFactSource payrollFacts,
            EmploymentContractCanonicalizer canonicalizer,
            ObjectMapper mapper,
            Hrm011WorkloadAuditPort audit,
            @Value("${dwp.hris.contract-compensation-workbench.workload-cursor-key:}")
            String cursorKey) {
        this.payrollFacts = Objects.requireNonNull(payrollFacts);
        this.canonicalizer = Objects.requireNonNull(canonicalizer);
        this.mapper = Objects.requireNonNull(mapper);
        this.audit = Objects.requireNonNull(audit);
        this.cursorKey = cursorKey == null
                ? new byte[0] : cursorKey.getBytes(StandardCharsets.UTF_8);
        this.clock = Clock.systemUTC();
    }

    @Transactional
    public SnapshotPage compensationSnapshots(
            Hrm011WorkloadRequestContext.VerifiedRequest verified) {
        requireVerified(verified, Hrm011WorkloadRouteContract.COMPENSATION_BINDING,
                Hrm011WorkloadRouteContract.COMPENSATION_PATH, SVC003_PURPOSE);
        SnapshotRequest request = verified.snapshotRequest();
        TrustedWorkload trusted = verified.trusted();
        requireRequest(request, SVC003_PURPOSE, Set.of(COMPENSATION));
        requireTrusted(trusted, verified.correlationId(), SVC003_PURPOSE);
        if (request.cursor() != null) {
            verifyCursor(request.cursor(), trusted, request,
                    "pay-group-source-unadmitted:v1");
        }
        // XCON-002 requires payGroupPublicId. No typed People source is admitted,
        // therefore the only safe production result is a valid homogeneous empty page.
        SnapshotPage result = page(
                request, "pay-group-source-unadmitted:v1", List.of(), null);
        return auditedSuccess(
                SVC003_OPERATION, verified.correlationId(), trusted, request, result);
    }

    @Transactional
    public SnapshotPage payrollWorkerFacts(
            Hrm011WorkloadRequestContext.VerifiedRequest verified) {
        requireVerified(verified, Hrm011WorkloadRouteContract.PAYROLL_FACTS_BINDING,
                Hrm011WorkloadRouteContract.PAYROLL_FACTS_PATH, SVC005_PURPOSE);
        SnapshotRequest request = verified.snapshotRequest();
        TrustedWorkload trusted = verified.trusted();
        requireRequest(request, SVC005_PURPOSE, PAYROLL_PROJECTIONS);
        requireTrusted(trusted, verified.correlationId(), SVC005_PURPOSE);
        CursorClaims claims = request.cursor() == null ? null
                : verifyCursor(request.cursor(), trusted, request, null);
        PayrollSlice slice = payrollFacts.read(
                trusted.tenantId(), request.projection(), request.asOf(),
                request.limit() + 1, claims == null ? null : claims.lastKey());
        if (slice == null || !request.projection().equals(slice.projection())
                || slice.ownerRevision() == null || slice.ownerRevision().isBlank()) {
            throw EmploymentContractException.unavailable(
                    "The SVC-005 typed projection provider is unavailable.");
        }
        if (claims != null && !claims.ownerRevision().equals(slice.ownerRevision())) {
            throw EmploymentContractException.conflict(
                    "HRM011_WORKLOAD_CURSOR_REVISION_CONFLICT",
                    "The SVC-005 owner revision changed.");
        }
        List<Object> ordered = validateAndOrder(request.projection(), slice.items());
        boolean more = ordered.size() > request.limit();
        List<Object> items = more
                ? List.copyOf(ordered.subList(0, request.limit())) : List.copyOf(ordered);
        String lastKey = more ? itemKey(items.getLast()) : null;
        String next = more ? issueCursor(new CursorClaims(
                trusted.tenantId(), trusted.caller(), request.purposeCode(),
                request.projection(), request.asOf(), slice.ownerRevision(), lastKey)) : null;
        SnapshotPage result = page(request, slice.ownerRevision(), items, next);
        return auditedSuccess(
                SVC005_OPERATION, verified.correlationId(), trusted, request, result);
    }

    private void requireVerified(
            Hrm011WorkloadRequestContext.VerifiedRequest verified,
            String bindingId,
            String path,
            String purpose) {
        if (verified == null || !"GET".equals(verified.method())
                || !bindingId.equals(verified.bindingId())
                || !path.equals(verified.path())
                || verified.tenantGrantRevision() < 1
                || verified.attemptId().equals(verified.correlationId())
                || !purpose.equals(verified.snapshotRequest().purposeCode())) {
            throw EmploymentContractException.denied(
                    "Verified HRM-011 workload authority is required.");
        }
    }

    private void requireRequest(
            SnapshotRequest request, String purpose, Set<String> projections) {
        if (request == null || !purpose.equals(request.purposeCode())
                || request.asOf() == null || !projections.contains(request.projection())
                || request.limit() < 1 || request.limit() > 500
                || request.cursor() != null && (request.cursor().isBlank()
                || request.cursor().length() > 4096)) {
            throw EmploymentContractException.invalid(
                    "The workload snapshot request is invalid.");
        }
    }

    private void requireTrusted(
            TrustedWorkload trusted, UUID correlationId, String purpose) {
        if (trusted == null || correlationId == null
                || trusted.tenantId() < 1
                || !PAYROLL_CALLER.equals(trusted.caller())
                || !AUDIENCE.equals(trusted.audience())
                || !trusted.tls13() || !trusted.mtlsAuthenticated()
                || !trusted.oauthClientJwtAuthenticated()
                || !trusted.tenantGrantValid()
                || trusted.policyRevision() < 1
                || !purpose.equals(trusted.purposeCode())) {
            throw EmploymentContractException.denied(
                    "Authenticated payroll workload authority is required.");
        }
    }

    private SnapshotPage auditedSuccess(
            String operation,
            UUID correlationId,
            TrustedWorkload trusted,
            SnapshotRequest request,
            SnapshotPage page) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callerWorkloadId", trusted.caller());
        data.put("tenantId", trusted.tenantId());
        data.put("operationId", operation);
        data.put("purposeCode", request.purposeCode());
        data.put("projectionId", request.projection());
        data.put("policyRevision", trusted.policyRevision());
        data.put("correlationId", correlationId.toString());
        data.put("outcome", "SUCCESS");
        data.put("rowCount", page.items().size());
        data.put("payloadDigest", page.pageDigest());
        UUID eventId = Hrm011WorkloadAuditPort.deterministicEventId(data);
        audit.record(new AuditEventEnvelope(
                eventId.toString(), URI.create(Hrm011WorkloadAuditPort.SOURCE),
                Hrm011WorkloadAuditPort.TYPE,
                Hrm011WorkloadAuditPort.SPEC_VERSION, clock.instant(), operation,
                Long.toString(trusted.tenantId()), correlationId.toString(),
                null, Hrm011WorkloadAuditPort.SCHEMA_VERSION,
                DataClassification.RESTRICTED, data));
        return page;
    }

    private List<Object> validateAndOrder(String projection, List<?> values) {
        if (values == null) throw EmploymentContractException.unavailable(
                "The SVC-005 provider returned no typed page.");
        Class<?> expected = switch (projection) {
            case "DEPENDENT_ELIGIBILITY_V1" ->
                    Xcon017WorkerDependentEligibilitySnapshotV1.class;
            case "TAX_IDENTITY_V1" -> Xcon018WorkerTaxIdentitySnapshotV1.class;
            case "BANK_ACCOUNT_TOKEN_V1" -> Xcon019WorkerBankAccountTokenSnapshotV1.class;
            default -> throw EmploymentContractException.invalid("Unknown SVC-005 projection.");
        };
        ArrayList<Object> items = new ArrayList<>();
        for (Object value : values) {
            if (!expected.isInstance(value)) {
                throw EmploymentContractException.unavailable(
                        "Mixed or untyped SVC-005 pages are forbidden.");
            }
            items.add(value);
        }
        items.sort(Comparator.comparing(this::itemKey));
        return items;
    }

    private String itemKey(Object item) {
        if (item instanceof Xcon017WorkerDependentEligibilitySnapshotV1 value) {
            return value.workerId() + ":" + value.snapshotId();
        }
        if (item instanceof Xcon018WorkerTaxIdentitySnapshotV1 value) {
            return value.workerId() + ":" + value.snapshotId();
        }
        if (item instanceof Xcon019WorkerBankAccountTokenSnapshotV1 value) {
            return value.workerId() + ":" + value.snapshotId();
        }
        throw EmploymentContractException.unavailable(
                "An untyped SVC-005 item was returned.");
    }

    private SnapshotPage page(
            SnapshotRequest request, String ownerRevision, List<?> items,
            String nextCursor) {
        Map<String, Object> digestInput = new LinkedHashMap<>();
        digestInput.put("schemaVersion", 1);
        digestInput.put("asOf", request.asOf());
        digestInput.put("projection", request.projection());
        digestInput.put("ownerRevision", ownerRevision);
        digestInput.put("items", items);
        digestInput.put("nextCursor", nextCursor);
        return new SnapshotPage(
                1, request.asOf(), request.projection(), ownerRevision,
                List.copyOf(items), nextCursor, canonicalizer.digest(digestInput));
    }

    private String issueCursor(CursorClaims claims) {
        requireCursorKey();
        try {
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mapper.writeValueAsBytes(claims));
            return payload + '.' + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(hmac(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SVC cursor serialization failed.", exception);
        }
    }

    private CursorClaims verifyCursor(
            String token, TrustedWorkload trusted, SnapshotRequest request,
            String knownRevision) {
        requireCursorKey();
        try {
            String[] segments = token.split("\\.", -1);
            if (segments.length != 2 || !MessageDigest.isEqual(
                    hmac(segments[0]), Base64.getUrlDecoder().decode(segments[1]))) {
                throw new IllegalArgumentException();
            }
            CursorClaims claims = mapper.readValue(
                    Base64.getUrlDecoder().decode(segments[0]), CursorClaims.class);
            if (claims.tenantId() != trusted.tenantId()
                    || !trusted.caller().equals(claims.caller())
                    || !request.purposeCode().equals(claims.purposeCode())
                    || !request.projection().equals(claims.projection())
                    || !request.asOf().equals(claims.asOf())
                    || knownRevision != null
                    && !knownRevision.equals(claims.ownerRevision())
                    || claims.lastKey() == null || claims.lastKey().isBlank()) {
                throw new IllegalArgumentException();
            }
            return claims;
        } catch (RuntimeException | IOException exception) {
            throw EmploymentContractException.invalid(
                    "The workload cursor is invalid or outside this authority scope.");
        }
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorKey, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable.", exception);
        }
    }

    private void requireCursorKey() {
        if (cursorKey.length < 32) {
            throw EmploymentContractException.unavailable(
                    "The workload cursor signing key is unavailable.");
        }
    }

    public record SnapshotPage(
            int schemaVersion,
            Instant asOf,
            String projection,
            String ownerRevision,
            List<?> items,
            String nextCursor,
            String pageDigest) {
        public SnapshotPage {
            items = List.copyOf(items);
        }
    }

    public record PayrollSlice(
            String projection,
            String ownerRevision,
            List<?> items) {
    }

    private record CursorClaims(
            long tenantId,
            String caller,
            String purposeCode,
            String projection,
            Instant asOf,
            String ownerRevision,
            String lastKey) {
    }

    /** Typed source. It may return only one exact XCON projection per page. */
    public interface PayrollWorkerFactSource {
        PayrollSlice read(long tenantId, String projection, Instant asOf,
                          int limit, String afterCanonicalKey);
    }
}
