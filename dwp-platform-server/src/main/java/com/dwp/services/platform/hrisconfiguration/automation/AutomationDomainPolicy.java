package com.dwp.services.platform.hrisconfiguration.automation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRulesProvider;
import java.util.HexFormat;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.scheduling.support.CronExpression;

import static com.dwp.services.platform.hrisconfiguration.automation.AutomationDtos.*;

/** Closed lifecycle, key rendering and mutually exclusive fence rules. */
final class AutomationDomainPolicy {

    enum AuthorityKind { WORKER, OWNER_RECOVERY, RECONCILER }
    enum RecoveryBranch { NO_ACTIVE_LEASE, WORKER_LEASE, RECONCILER_LEASE }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "tenantId", "definitionPublicId", "scopeType", "scopeId");

    private AutomationDomainPolicy() {
    }

    static VersionState nextVersionState(VersionState current, String operation) {
        return switch (operation) {
            case "VALIDATE" -> require(current, VersionState.DRAFT, VersionState.VALIDATED);
            case "SUBMIT" -> require(
                    current, VersionState.VALIDATED, VersionState.PENDING_APPROVAL);
            case "APPROVE" -> require(
                    current, VersionState.PENDING_APPROVAL, VersionState.APPROVED);
            case "ACTIVATE" -> require(
                    current, VersionState.APPROVED, VersionState.ACTIVE);
            default -> throw new IllegalArgumentException("Unknown version operation");
        };
    }

    static ScheduleState nextScheduleState(ScheduleState current, String operation) {
        return switch (operation) {
            case "SUBMIT" -> require(
                    current, ScheduleState.DRAFT, ScheduleState.PENDING_APPROVAL);
            case "APPROVE" -> require(
                    current, ScheduleState.PENDING_APPROVAL, ScheduleState.APPROVED);
            case "ACTIVATE" -> require(
                    current, ScheduleState.APPROVED, ScheduleState.ACTIVE);
            case "SUSPEND" -> require(
                    current, ScheduleState.ACTIVE, ScheduleState.SUSPENDED);
            default -> throw new IllegalArgumentException("Unknown schedule operation");
        };
    }

    static void requireMakerChecker(UUID author, UUID approver) {
        if (author == null || approver == null || author.equals(approver)) {
            throw new IllegalStateException("AUTOMATION_MAKER_CHECKER_REQUIRED");
        }
    }

    static String renderConcurrencyKey(
            String template,
            long tenantId,
            UUID definitionPublicId,
            String scopeType,
            UUID scopeId) {
        if (template == null || template.isBlank()) {
            throw new IllegalArgumentException("AUTOMATION_CONCURRENCY_TEMPLATE_REQUIRED");
        }
        Map<String, String> values = new HashMap<>();
        values.put("tenantId", Long.toString(tenantId));
        values.put("definitionPublicId", definitionPublicId.toString());
        values.put("scopeType", scopeType);
        if (scopeId != null) values.put("scopeId", scopeId.toString());
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!ALLOWED_PLACEHOLDERS.contains(name)) {
                throw new IllegalArgumentException(
                        "AUTOMATION_CONCURRENCY_PLACEHOLDER_NOT_ALLOWED");
            }
            if (!values.containsKey(name)) {
                throw new IllegalArgumentException(
                        "AUTOMATION_CONCURRENCY_PLACEHOLDER_UNRESOLVED");
            }
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(values.get(name)));
        }
        matcher.appendTail(rendered);
        if (rendered.indexOf("{") >= 0 || rendered.indexOf("}") >= 0) {
            throw new IllegalArgumentException(
                    "AUTOMATION_CONCURRENCY_PLACEHOLDER_UNRESOLVED");
        }
        byte[] bytes = rendered.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 1 || bytes.length > 300) {
            throw new IllegalArgumentException(
                    "AUTOMATION_CONCURRENCY_KEY_OUT_OF_BOUNDS");
        }
        return sha256(bytes);
    }

    static ZoneId requireScheduleDefinition(
            String expression, String timezone, String timezoneDatabaseRevision) {
        try {
            CronExpression.parse(expression);
            ZoneId zone = ZoneId.of(timezone);
            if (timezoneDatabaseRevision == null
                    || !ZoneRulesProvider.getVersions(zone.getId())
                    .containsKey(timezoneDatabaseRevision)) {
                throw new IllegalArgumentException(
                        "AUTOMATION_TIMEZONE_DATABASE_REVISION_UNAVAILABLE");
            }
            return zone;
        } catch (IllegalArgumentException invalid) {
            if ("AUTOMATION_TIMEZONE_DATABASE_REVISION_UNAVAILABLE"
                    .equals(invalid.getMessage())) {
                throw invalid;
            }
            throw new IllegalArgumentException(
                    "AUTOMATION_SCHEDULE_DEFINITION_INVALID", invalid);
        }
    }

    /**
     * Resolves the first strictly-future UTC fire using the sealed TZDB revision.
     * Gaps are skipped and overlaps deterministically choose the earlier offset.
     */
    static OffsetDateTime nextScheduleFire(
            String expression,
            String timezone,
            String timezoneDatabaseRevision,
            OffsetDateTime after) {
        ZoneId zone = requireScheduleDefinition(
                expression, timezone, timezoneDatabaseRevision);
        CronExpression cron = CronExpression.parse(expression);
        ZonedDateTime cursor = after.atZoneSameInstant(zone);
        for (int candidateCount = 0; candidateCount < 4; candidateCount++) {
            ZonedDateTime calculated = cron.next(cursor);
            if (calculated == null) break;
            var offsets = zone.getRules().getValidOffsets(
                    calculated.toLocalDateTime());
            if (offsets.isEmpty()) {
                var transition = zone.getRules().getTransition(
                        calculated.toLocalDateTime());
                if (transition == null) break;
                cursor = transition.getDateTimeAfter().atZone(zone);
                continue;
            }
            ZonedDateTime deterministic = ZonedDateTime.ofLocal(
                    calculated.toLocalDateTime(), zone, offsets.getFirst());
            if (deterministic.toInstant().isAfter(after.toInstant())) {
                return deterministic.withZoneSameInstant(ZoneOffset.UTC)
                        .toOffsetDateTime();
            }
            cursor = ZonedDateTime.ofLocal(calculated.toLocalDateTime(), zone,
                    offsets.getLast()).plusSeconds(1);
        }
        throw new IllegalStateException(
                "AUTOMATION_SCHEDULE_NEXT_FIRE_UNAVAILABLE");
    }

    static RecoveryDecision ownerRecovery(
            long currentControlFence,
            long currentWorkerFence,
            long currentReconcilerFence,
            Lease worker,
            Lease reconciler,
            UUID suppliedToken,
            OffsetDateTime databaseNow,
            boolean explicitlyRevoked) {
        if (worker != null && reconciler != null) {
            throw new IllegalStateException("AUTOMATION_AUTHORITY_LEASES_NOT_EXCLUSIVE");
        }
        if (worker == null && reconciler == null) {
            return new RecoveryDecision(
                    RecoveryBranch.NO_ACTIVE_LEASE, currentControlFence + 1,
                    currentWorkerFence, currentReconcilerFence, false);
        }
        Lease selected = worker == null ? reconciler : worker;
        if (suppliedToken == null || !selected.token().equals(suppliedToken)
                || (!explicitlyRevoked && selected.leasedUntil().isAfter(databaseNow))) {
            throw new IllegalStateException("AUTOMATION_STALE_RECOVERY_FENCE");
        }
        boolean workerSelected = worker != null;
        return new RecoveryDecision(
                workerSelected ? RecoveryBranch.WORKER_LEASE
                        : RecoveryBranch.RECONCILER_LEASE,
                currentControlFence + 1,
                workerSelected ? selected.authorityFenceEpoch() + 1
                        : currentWorkerFence,
                workerSelected ? currentReconcilerFence
                        : selected.authorityFenceEpoch() + 1,
                false);
    }

    static void requireActiveFence(
            AuthorityKind kind,
            Lease worker,
            Lease reconciler,
            UUID token,
            long authorityFence,
            long controlFence,
            OffsetDateTime databaseNow,
            boolean externalDispatch,
            String effectKind) {
        if (kind == AuthorityKind.OWNER_RECOVERY && externalDispatch) {
            throw new IllegalStateException("OWNER_RECOVERY_EXTERNAL_DISPATCH_FORBIDDEN");
        }
        Lease selected = switch (kind) {
            case WORKER -> worker;
            case RECONCILER -> reconciler;
            case OWNER_RECOVERY -> null;
        };
        if (kind == AuthorityKind.OWNER_RECOVERY) return;
        if (worker != null && reconciler != null) {
            throw new IllegalStateException("AUTOMATION_AUTHORITY_LEASES_NOT_EXCLUSIVE");
        }
        if (selected == null || token == null || !token.equals(selected.token())
                || selected.authorityFenceEpoch() != authorityFence
                || selected.controlFenceEpoch() != controlFence
                || !selected.leasedUntil().isAfter(databaseNow)) {
            throw new IllegalStateException("AUTOMATION_STALE_AUTHORITY_FENCE");
        }
        if (externalDispatch
                && !((kind == AuthorityKind.WORKER && "ORIGINAL".equals(effectKind))
                || (kind == AuthorityKind.RECONCILER
                && "COMPENSATION".equals(effectKind)))) {
            throw new IllegalStateException("AUTOMATION_EFFECT_AUTHORITY_REJECTED");
        }
    }

    static String scheduleFireKey(
            long tenantId,
            UUID schedulePublicId,
            long scheduleRevision,
            long approvedSubjectRevision,
            String policyDigest,
            OffsetDateTime scheduledInstantUtc) {
        String jcs = "{\"approvedSubjectRevisionDecimal\":\""
                + positive(approvedSubjectRevision) + "\",\"schedulePolicyDigest\":\""
                + policyDigest + "\",\"schedulePublicId\":\"" + schedulePublicId
                + "\",\"scheduleRevisionDecimal\":\"" + positive(scheduleRevision)
                + "\",\"scheduledInstantUtc\":\""
                + scheduledInstantUtc.toInstant() + "\",\"tenantIdDecimal\":\""
                + positiveSignedBigint(tenantId) + "\"}";
        return sha256(jcs.getBytes(StandardCharsets.UTF_8));
    }

    record Lease(
            UUID token,
            long authorityFenceEpoch,
            long controlFenceEpoch,
            OffsetDateTime leasedUntil) {
    }

    record RecoveryDecision(
            RecoveryBranch branch,
            long nextControlFence,
            long nextWorkerFence,
            long nextReconcilerFence,
            boolean externalDispatchAuthorized) {
    }

    private static <T> T require(T current, T expected, T target) {
        if (current != expected) {
            throw new IllegalStateException("AUTOMATION_LIFECYCLE_CONFLICT");
        }
        return target;
    }

    private static String positive(long value) {
        if (value < 1 || value > Sys006TypedArtifactContract.MAX_JSON_SAFE_INTEGER) {
            throw new IllegalArgumentException("Positive JSON-safe integer required");
        }
        return Long.toString(value);
    }

    private static String positiveSignedBigint(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("Positive signed BIGINT required");
        }
        return Long.toString(value);
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
