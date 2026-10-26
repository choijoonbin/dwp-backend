package com.dwp.platform.contracts.hris.separation.v1;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Closed People-to-Time, People-to-Payroll and People-to-Auth task dispatch. */
public final class Hrm013TaskDispatchV1 {

    public static final String SCHEMA_NAME = "Hrm013TaskDispatchV1";
    public static final int SCHEMA_VERSION = 1;
    public static final String METHOD = "POST";
    public static final String CONTENT_TYPE = "application/json";
    public static final int MAXIMUM_REQUEST_BYTES = 32_768;
    public static final int MAXIMUM_RESPONSE_BYTES = 16_384;
    public static final UUID DISPATCH_EVENT_NAMESPACE = UUID.fromString(
            "089d9495-71cf-55aa-96e5-5cbd1682e5bb");

    public static final Profile TIME = new Profile(
            "SVC-PEP-TIM-004", "dwp-people-server", "dwp-time-server",
            "HRIS_SEPARATION_TIME_TASK_DISPATCH", "TIME_FINALIZATION",
            "/internal/hris-time/v1/separation-task-dispatches",
            "SeparationTimeFinalizationTaskDispatchRequested.v1",
            "SVC-AUT-HRM-015", "SVC-PEP-HRM-008",
            "HRIS_SEPARATION_TIME_TASK_RECEIPT");

    public static final Profile PAYROLL = new Profile(
            "SVC-PEP-PAY-002", "dwp-people-server", "dwp-payroll-server",
            "HRIS_SEPARATION_PAYROLL_TASK_DISPATCH", "PAYROLL_SETTLEMENT",
            "/internal/hris-payroll/v1/separation-task-dispatches",
            "SeparationPayrollSettlementTaskDispatchRequested.v1",
            "SVC-AUT-HRM-016", "SVC-PEP-HRM-008",
            "HRIS_SEPARATION_PAYROLL_TASK_RECEIPT");

    public static final Profile IDENTITY = new Profile(
            "SVC-AUT-HRM-014", "dwp-people-server", "dwp-auth-server",
            "HRIS_SEPARATION_IDENTITY_TASK_DISPATCH", "IDENTITY_ACCESS_REVOCATION",
            "/internal/auth/v1/separation-task-dispatches",
            "SeparationIdentityAccessRevocationTaskDispatchRequested.v1",
            "LOCAL_AUTH_LEDGER", "SVC-PEP-HRM-008",
            "HRIS_SEPARATION_IDENTITY_TASK_RECEIPT");

    public static final List<Profile> PROFILES = List.of(TIME, PAYROLL, IDENTITY);

    private Hrm013TaskDispatchV1() {
    }

    public record Profile(
            String bindingId,
            String callerWorkloadId,
            String ownerWorkloadId,
            String purpose,
            String taskType,
            String path,
            String eventType,
            String authorityResolverBindingId,
            String callbackBindingId,
            String callbackPurpose) {
        public Profile {
            Hrm013SeparationBridgeV1.requireCode(bindingId, "bindingId");
            requireWorkload(callerWorkloadId, "callerWorkloadId");
            requireWorkload(ownerWorkloadId, "ownerWorkloadId");
            Hrm013SeparationBridgeV1.requireCode(purpose, "purpose");
            Hrm013SeparationBridgeV1.requireCode(taskType, "taskType");
            if (path == null || !path.startsWith("/internal/")
                    || path.indexOf('?') >= 0 || path.indexOf('#') >= 0
                    || path.endsWith("/")) {
                throw new IllegalArgumentException("path is not exact internal path");
            }
            Objects.requireNonNull(eventType, "eventType");
            Objects.requireNonNull(authorityResolverBindingId,
                    "authorityResolverBindingId");
            Hrm013SeparationBridgeV1.requireCode(callbackBindingId,
                    "callbackBindingId");
            Hrm013SeparationBridgeV1.requireCode(callbackPurpose, "callbackPurpose");
        }

        public String method() {
            return METHOD;
        }

        public String audience() {
            return ownerWorkloadId;
        }

        public String admissionReceiptPurpose() {
            return purpose + "_ADMISSION_RECEIPT";
        }
    }

    public sealed interface DispatchBody permits
            TimeAndPayrollTaskDispatch,
            IdentityTaskDispatch {
        String schemaName();

        int schemaVersion();

        UUID dispatchEventId();

        UUID casePublicId();

        UUID taskPublicId();

        String taskType();

        long taskOwnerRevision();

        long taskVersion();

        Instant dispatchedAt();

        String payloadSha256();

        UUID correlationId();

        Map<String, Object> wireValues(boolean includePayloadSha256);

        DispatchBody withPayloadSha256(String payloadSha256);
    }

    public record TimeAndPayrollTaskDispatch(
            String schemaName,
            int schemaVersion,
            UUID dispatchEventId,
            UUID casePublicId,
            UUID taskPublicId,
            String taskType,
            long taskOwnerRevision,
            long taskVersion,
            Instant dispatchedAt,
            String payloadSha256,
            UUID correlationId,
            UUID workerPublicId,
            UUID relationshipPublicId,
            String separationType,
            LocalDate effectiveDate,
            LocalDate lastWorkingDate) implements DispatchBody {
        public TimeAndPayrollTaskDispatch {
            requireCommon(schemaName, schemaVersion, dispatchEventId, casePublicId,
                    taskPublicId, taskType, taskOwnerRevision, taskVersion,
                    dispatchedAt, payloadSha256, correlationId);
            Hrm013SeparationBridgeV1.requireUuid(workerPublicId, "workerPublicId");
            Hrm013SeparationBridgeV1.requireUuid(
                    relationshipPublicId, "relationshipPublicId");
            Hrm013SeparationBridgeV1.requireCode(separationType, "separationType");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
            Objects.requireNonNull(lastWorkingDate, "lastWorkingDate");
        }

        @Override public Map<String, Object> wireValues(boolean includeDigest) {
            Map<String, Object> values = commonValues(this);
            values.put("workerPublicId", workerPublicId);
            values.put("relationshipPublicId", relationshipPublicId);
            values.put("separationType", separationType);
            values.put("effectiveDate", effectiveDate.toString());
            values.put("lastWorkingDate", lastWorkingDate.toString());
            if (includeDigest) values.put("payloadSha256", payloadSha256);
            return values;
        }

        @Override public TimeAndPayrollTaskDispatch withPayloadSha256(String digest) {
            return new TimeAndPayrollTaskDispatch(
                    schemaName, schemaVersion, dispatchEventId, casePublicId,
                    taskPublicId, taskType, taskOwnerRevision, taskVersion,
                    dispatchedAt, digest, correlationId, workerPublicId,
                    relationshipPublicId, separationType, effectiveDate, lastWorkingDate);
        }
    }

    public record IdentityTaskDispatch(
            String schemaName,
            int schemaVersion,
            UUID dispatchEventId,
            UUID casePublicId,
            UUID taskPublicId,
            String taskType,
            long taskOwnerRevision,
            long taskVersion,
            Instant dispatchedAt,
            String payloadSha256,
            UUID correlationId,
            UUID personPublicId,
            String separationType,
            LocalDate effectiveDate) implements DispatchBody {
        public IdentityTaskDispatch {
            requireCommon(schemaName, schemaVersion, dispatchEventId, casePublicId,
                    taskPublicId, taskType, taskOwnerRevision, taskVersion,
                    dispatchedAt, payloadSha256, correlationId);
            Hrm013SeparationBridgeV1.requireUuid(personPublicId, "personPublicId");
            Hrm013SeparationBridgeV1.requireCode(separationType, "separationType");
            Objects.requireNonNull(effectiveDate, "effectiveDate");
        }

        @Override public Map<String, Object> wireValues(boolean includeDigest) {
            Map<String, Object> values = commonValues(this);
            values.put("personPublicId", personPublicId);
            values.put("separationType", separationType);
            values.put("effectiveDate", effectiveDate.toString());
            if (includeDigest) values.put("payloadSha256", payloadSha256);
            return values;
        }

        @Override public IdentityTaskDispatch withPayloadSha256(String digest) {
            return new IdentityTaskDispatch(
                    schemaName, schemaVersion, dispatchEventId, casePublicId,
                    taskPublicId, taskType, taskOwnerRevision, taskVersion,
                    dispatchedAt, digest, correlationId, personPublicId,
                    separationType, effectiveDate);
        }
    }

    public record Hrm013TaskDispatchAdmissionReceiptV1(
            UUID dispatchEventId,
            UUID taskPublicId,
            String payloadSha256,
            String requestBodySha256,
            Instant admittedAt,
            String receiptSha256,
            UUID correlationId) {
        public Hrm013TaskDispatchAdmissionReceiptV1 {
            Hrm013SeparationBridgeV1.requireUuid(dispatchEventId, "dispatchEventId");
            Hrm013SeparationBridgeV1.requireUuid(taskPublicId, "taskPublicId");
            Hrm013SeparationBridgeV1.requireDigest(payloadSha256, "payloadSha256");
            Hrm013SeparationBridgeV1.requireDigest(
                    requestBodySha256, "requestBodySha256");
            Hrm013SeparationBridgeV1.requireInstant(admittedAt, "admittedAt");
            Hrm013SeparationBridgeV1.requireDigest(receiptSha256, "receiptSha256");
            Hrm013SeparationBridgeV1.requireUuid(correlationId, "correlationId");
        }

        Map<String, Object> wireValues(boolean includeReceiptSha256) {
            Map<String, Object> values = Hrm013SeparationBridgeV1.values(
                    "dispatchEventId", dispatchEventId,
                    "taskPublicId", taskPublicId,
                    "payloadSha256", payloadSha256,
                    "requestBodySha256", requestBodySha256,
                    "admittedAt", admittedAt,
                    "correlationId", correlationId);
            if (includeReceiptSha256) values.put("receiptSha256", receiptSha256);
            return values;
        }

        public Hrm013TaskDispatchAdmissionReceiptV1 withReceiptSha256(String digest) {
            return new Hrm013TaskDispatchAdmissionReceiptV1(
                    dispatchEventId, taskPublicId, payloadSha256,
                    requestBodySha256, admittedAt, digest, correlationId);
        }
    }

    public static Profile requireProfile(
            String bindingId,
            String ownerWorkloadId,
            String purpose,
            String taskType) {
        List<Profile> matches = PROFILES.stream()
                .filter(profile -> profile.bindingId().equals(bindingId))
                .filter(profile -> profile.ownerWorkloadId().equals(ownerWorkloadId))
                .filter(profile -> profile.purpose().equals(purpose))
                .filter(profile -> profile.taskType().equals(taskType))
                .toList();
        if (matches.size() != 1) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        return matches.get(0);
    }

    public static <T extends DispatchBody> T seal(T body) {
        Objects.requireNonNull(body, "body");
        @SuppressWarnings("unchecked")
        T sealed = (T) body.withPayloadSha256(payloadSha256(body));
        return sealed;
    }

    public static String payloadSha256(DispatchBody body) {
        return Hrm013SeparationBridgeV1.sha256(
                Hrm013SeparationBridgeV1.canonicalObjectBytes(
                        Objects.requireNonNull(body, "body").wireValues(false)));
    }

    public static void requirePayloadSha256(DispatchBody body) {
        if (!Hrm013SeparationBridgeV1.constantTimeEquals(
                body.payloadSha256(), payloadSha256(body))) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
    }

    public static UUID dispatchEventId(
            long tenantId, UUID taskPublicId, long taskVersion, String bindingId) {
        Hrm013SeparationBridgeV1.requirePositive(tenantId, "tenantId");
        Hrm013SeparationBridgeV1.requireUuid(taskPublicId, "taskPublicId");
        Hrm013SeparationBridgeV1.requirePositive(taskVersion, "taskVersion");
        Hrm013SeparationBridgeV1.requireCode(bindingId, "bindingId");
        String name = tenantId + "\n" + taskPublicId + "\n" + taskVersion
                + "\n" + bindingId;
        return uuidV5(DISPATCH_EVENT_NAMESPACE, name.getBytes(StandardCharsets.UTF_8));
    }

    public static void requireProfileBinding(
            Profile profile, long tenantId, DispatchBody body) {
        Objects.requireNonNull(profile, "profile");
        requireKnownProfile(profile);
        Objects.requireNonNull(body, "body");
        boolean variantMatches = profile.equals(IDENTITY)
                ? body instanceof IdentityTaskDispatch
                : body instanceof TimeAndPayrollTaskDispatch;
        UUID expectedEvent = dispatchEventId(
                tenantId, body.taskPublicId(), body.taskVersion(), profile.bindingId());
        if (!variantMatches
                || !profile.taskType().equals(body.taskType())
                || body.taskOwnerRevision() != 1
                || !expectedEvent.equals(body.dispatchEventId())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        requirePayloadSha256(body);
    }

    public static byte[] encode(Profile profile, long tenantId, DispatchBody body) {
        requireProfileBinding(profile, tenantId, body);
        byte[] bytes = Hrm013SeparationBridgeV1.canonicalObjectBytes(
                body.wireValues(true));
        Hrm013SeparationBridgeV1.requireMaximum(bytes, MAXIMUM_REQUEST_BYTES);
        return bytes;
    }

    public static byte[] encode(DispatchBody body) {
        requirePayloadSha256(body);
        byte[] bytes = Hrm013SeparationBridgeV1.canonicalObjectBytes(
                body.wireValues(true));
        Hrm013SeparationBridgeV1.requireMaximum(bytes, MAXIMUM_REQUEST_BYTES);
        return bytes;
    }

    public static DispatchBody decode(Profile profile, long tenantId, byte[] bytes) {
        Objects.requireNonNull(profile, "profile");
        requireKnownProfile(profile);
        Set<String> common = Set.of(
                "schemaName", "schemaVersion", "dispatchEventId", "casePublicId",
                "taskPublicId", "taskType", "taskOwnerRevision", "taskVersion",
                "dispatchedAt", "payloadSha256", "correlationId");
        Set<String> exact = new java.util.HashSet<>(common);
        if (profile.equals(IDENTITY)) {
            exact.addAll(Set.of("personPublicId", "separationType", "effectiveDate"));
        } else {
            exact.addAll(Set.of("workerPublicId", "relationshipPublicId",
                    "separationType", "effectiveDate", "lastWorkingDate"));
        }
        JsonNode node = Hrm013SeparationBridgeV1.strictObject(
                bytes, MAXIMUM_REQUEST_BYTES, exact);
        try {
            DispatchBody body;
            if (profile.equals(IDENTITY)) {
                body = new IdentityTaskDispatch(
                        Hrm013SeparationBridgeV1.text(node, "schemaName"),
                        exactInt(node, "schemaVersion"),
                        Hrm013SeparationBridgeV1.uuid(node, "dispatchEventId"),
                        Hrm013SeparationBridgeV1.uuid(node, "casePublicId"),
                        Hrm013SeparationBridgeV1.uuid(node, "taskPublicId"),
                        Hrm013SeparationBridgeV1.text(node, "taskType"),
                        Hrm013SeparationBridgeV1.number(node, "taskOwnerRevision"),
                        Hrm013SeparationBridgeV1.number(node, "taskVersion"),
                        Hrm013SeparationBridgeV1.instant(node, "dispatchedAt"),
                        Hrm013SeparationBridgeV1.text(node, "payloadSha256"),
                        Hrm013SeparationBridgeV1.uuid(node, "correlationId"),
                        Hrm013SeparationBridgeV1.uuid(node, "personPublicId"),
                        Hrm013SeparationBridgeV1.text(node, "separationType"),
                        date(node, "effectiveDate"));
            } else {
                body = new TimeAndPayrollTaskDispatch(
                        Hrm013SeparationBridgeV1.text(node, "schemaName"),
                        exactInt(node, "schemaVersion"),
                        Hrm013SeparationBridgeV1.uuid(node, "dispatchEventId"),
                        Hrm013SeparationBridgeV1.uuid(node, "casePublicId"),
                        Hrm013SeparationBridgeV1.uuid(node, "taskPublicId"),
                        Hrm013SeparationBridgeV1.text(node, "taskType"),
                        Hrm013SeparationBridgeV1.number(node, "taskOwnerRevision"),
                        Hrm013SeparationBridgeV1.number(node, "taskVersion"),
                        Hrm013SeparationBridgeV1.instant(node, "dispatchedAt"),
                        Hrm013SeparationBridgeV1.text(node, "payloadSha256"),
                        Hrm013SeparationBridgeV1.uuid(node, "correlationId"),
                        Hrm013SeparationBridgeV1.uuid(node, "workerPublicId"),
                        Hrm013SeparationBridgeV1.uuid(node, "relationshipPublicId"),
                        Hrm013SeparationBridgeV1.text(node, "separationType"),
                        date(node, "effectiveDate"), date(node, "lastWorkingDate"));
            }
            requireProfileBinding(profile, tenantId, body);
            Hrm013SeparationBridgeV1.requireCanonical(bytes, encode(body));
            return body;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static Hrm013TaskDispatchAdmissionReceiptV1 seal(
            Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        Objects.requireNonNull(receipt, "receipt");
        return receipt.withReceiptSha256(receiptSha256(receipt));
    }

    public static String receiptSha256(
            Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        return Hrm013SeparationBridgeV1.sha256(
                Hrm013SeparationBridgeV1.canonicalObjectBytes(
                        Objects.requireNonNull(receipt, "receipt")
                                .wireValues(false)));
    }

    public static void requireReceiptSha256(
            Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        if (!Hrm013SeparationBridgeV1.constantTimeEquals(
                receipt.receiptSha256(), receiptSha256(receipt))) {
            throw Hrm013SafeErrorV1.digestConflict();
        }
    }

    public static byte[] encode(Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        requireReceiptSha256(receipt);
        byte[] bytes = Hrm013SeparationBridgeV1.canonicalObjectBytes(
                receipt.wireValues(true));
        Hrm013SeparationBridgeV1.requireMaximum(bytes, MAXIMUM_RESPONSE_BYTES);
        return bytes;
    }

    public static Hrm013TaskDispatchAdmissionReceiptV1 decodeAdmissionReceipt(
            byte[] bytes) {
        JsonNode node = Hrm013SeparationBridgeV1.strictObject(
                bytes, MAXIMUM_RESPONSE_BYTES, Set.of(
                        "dispatchEventId", "taskPublicId", "payloadSha256",
                        "requestBodySha256", "admittedAt", "receiptSha256",
                        "correlationId"));
        try {
            Hrm013TaskDispatchAdmissionReceiptV1 receipt =
                    new Hrm013TaskDispatchAdmissionReceiptV1(
                    Hrm013SeparationBridgeV1.uuid(node, "dispatchEventId"),
                    Hrm013SeparationBridgeV1.uuid(node, "taskPublicId"),
                    Hrm013SeparationBridgeV1.text(node, "payloadSha256"),
                    Hrm013SeparationBridgeV1.text(node, "requestBodySha256"),
                    Hrm013SeparationBridgeV1.instant(node, "admittedAt"),
                    Hrm013SeparationBridgeV1.text(node, "receiptSha256"),
                    Hrm013SeparationBridgeV1.uuid(node, "correlationId"));
            requireReceiptSha256(receipt);
            Hrm013SeparationBridgeV1.requireCanonical(bytes, encode(receipt));
            return receipt;
        } catch (Hrm013SafeErrorV1.Failure failure) {
            throw failure;
        } catch (RuntimeException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    public static void requireAdmissionReceiptBinding(
            DispatchBody request,
            byte[] exactRequestBytes,
            Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(receipt, "receipt");
        requireReceiptSha256(receipt);
        String requestBodySha256 = requestBodySha256(exactRequestBytes);
        if (!request.dispatchEventId().equals(receipt.dispatchEventId())
                || !request.taskPublicId().equals(receipt.taskPublicId())
                || !request.correlationId().equals(receipt.correlationId())
                || !Hrm013SeparationBridgeV1.constantTimeEquals(
                request.payloadSha256(), receipt.payloadSha256())
                || !Hrm013SeparationBridgeV1.constantTimeEquals(
                requestBodySha256, receipt.requestBodySha256())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }

    public static void requireAdmissionReceiptBinding(
            Profile profile,
            long tenantId,
            DispatchBody request,
            byte[] exactRequestBytes,
            Hrm013TaskDispatchAdmissionReceiptV1 receipt) {
        requireProfileBinding(profile, tenantId, request);
        requireAdmissionReceiptBinding(request, exactRequestBytes, receipt);
    }

    /** Validates the separate HRM-008 completion/failure lane against dispatch lineage. */
    public static void requireCallbackReceiptBinding(
            Profile profile,
            DispatchBody dispatch,
            Hrm013SeparationBridgeV1.BindingProfile callbackProfile,
            Hrm013SeparationBridgeV1.SeparationTaskReceiptV2 callback) {
        Objects.requireNonNull(profile, "profile");
        requireKnownProfile(profile);
        Objects.requireNonNull(dispatch, "dispatch");
        Objects.requireNonNull(callbackProfile, "callbackProfile");
        Objects.requireNonNull(callback, "callback");
        boolean variantMatches = profile.equals(IDENTITY)
                ? dispatch instanceof IdentityTaskDispatch
                : dispatch instanceof TimeAndPayrollTaskDispatch;
        requirePayloadSha256(dispatch);
        if (!variantMatches
                || !profile.taskType().equals(dispatch.taskType())
                || !profile.callbackBindingId().equals(callbackProfile.bindingId())
                || !profile.ownerWorkloadId().equals(callbackProfile.callerWorkloadId())
                || !profile.callbackPurpose().equals(callbackProfile.purpose())
                || !dispatch.casePublicId().equals(callback.casePublicId())
                || !dispatch.taskPublicId().equals(callback.taskPublicId())
                || !dispatch.taskType().equals(callback.taskType())
                || dispatch.taskOwnerRevision() != callback.ownerRevision()
                || !dispatch.correlationId().equals(callback.correlationId())
                || !Hrm013SeparationBridgeV1.constantTimeEquals(
                dispatch.payloadSha256(), callback.originatingPayloadSha256())) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
        Hrm013SeparationBridgeV1.requireBodyBinding(callbackProfile, callback);
        Hrm013SeparationBridgeV1.requirePayloadSha256(callback);
    }

    public static String requestBodySha256(byte[] exactBodyBytes) {
        Objects.requireNonNull(exactBodyBytes, "exactBodyBytes");
        Hrm013SeparationBridgeV1.requireMaximum(
                exactBodyBytes, MAXIMUM_REQUEST_BYTES);
        return Hrm013SeparationBridgeV1.sha256(exactBodyBytes);
    }

    public static String commandDigest(
            Profile profile, long tenantId, String requestBodySha256) {
        Objects.requireNonNull(profile, "profile");
        requireKnownProfile(profile);
        Hrm013SeparationBridgeV1.requirePositive(tenantId, "tenantId");
        Hrm013SeparationBridgeV1.requireDigest(
                requestBodySha256, "requestBodySha256");
        String preimage = String.join("\n",
                profile.bindingId(), METHOD, profile.path(),
                profile.callerWorkloadId(), profile.audience(),
                Long.toString(tenantId), profile.purpose(), CONTENT_TYPE,
                requestBodySha256);
        return Hrm013SeparationBridgeV1.sha256(
                preimage.getBytes(StandardCharsets.UTF_8));
    }

    public static String commandDigest(
            Profile profile, long tenantId, byte[] exactBodyBytes) {
        return commandDigest(profile, tenantId,
                requestBodySha256(exactBodyBytes));
    }

    private static Map<String, Object> commonValues(DispatchBody body) {
        return new LinkedHashMap<>(Hrm013SeparationBridgeV1.values(
                "schemaName", body.schemaName(),
                "schemaVersion", body.schemaVersion(),
                "dispatchEventId", body.dispatchEventId(),
                "casePublicId", body.casePublicId(),
                "taskPublicId", body.taskPublicId(),
                "taskType", body.taskType(),
                "taskOwnerRevision", body.taskOwnerRevision(),
                "taskVersion", body.taskVersion(),
                "dispatchedAt", body.dispatchedAt(),
                "correlationId", body.correlationId()));
    }

    private static void requireCommon(
            String schemaName,
            int schemaVersion,
            UUID dispatchEventId,
            UUID casePublicId,
            UUID taskPublicId,
            String taskType,
            long taskOwnerRevision,
            long taskVersion,
            Instant dispatchedAt,
            String payloadSha256,
            UUID correlationId) {
        if (!SCHEMA_NAME.equals(schemaName) || schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("schema name or version mismatch");
        }
        Hrm013SeparationBridgeV1.requireUuid(dispatchEventId, "dispatchEventId");
        Hrm013SeparationBridgeV1.requireUuid(casePublicId, "casePublicId");
        Hrm013SeparationBridgeV1.requireUuid(taskPublicId, "taskPublicId");
        Hrm013SeparationBridgeV1.requireCode(taskType, "taskType");
        Hrm013SeparationBridgeV1.requirePositive(
                taskOwnerRevision, "taskOwnerRevision");
        Hrm013SeparationBridgeV1.requirePositive(taskVersion, "taskVersion");
        Hrm013SeparationBridgeV1.requireInstant(dispatchedAt, "dispatchedAt");
        Hrm013SeparationBridgeV1.requireDigest(payloadSha256, "payloadSha256");
        Hrm013SeparationBridgeV1.requireUuid(correlationId, "correlationId");
    }

    private static LocalDate date(JsonNode node, String field) {
        String value = Hrm013SeparationBridgeV1.text(node, field);
        try {
            LocalDate date = LocalDate.parse(value);
            if (!date.toString().equals(value)) {
                throw Hrm013SafeErrorV1.malformed();
            }
            return date;
        } catch (DateTimeParseException exception) {
            throw Hrm013SafeErrorV1.malformed();
        }
    }

    private static int exactInt(JsonNode node, String field) {
        long value = Hrm013SeparationBridgeV1.number(node, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw Hrm013SafeErrorV1.malformed();
        }
        return (int) value;
    }

    private static UUID uuidV5(UUID namespace, byte[] name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(uuidBytes(namespace));
            byte[] hash = sha1.digest(name);
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            ByteBuffer bytes = ByteBuffer.wrap(hash);
            return new UUID(bytes.getLong(), bytes.getLong());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-1 unavailable", impossible);
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static void requireWorkload(String value, String field) {
        if (value == null || !value.matches("^dwp-[a-z]+-server$")) {
            throw new IllegalArgumentException(field + " is not canonical");
        }
    }

    private static void requireKnownProfile(Profile profile) {
        if (!PROFILES.contains(profile)) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }
}
