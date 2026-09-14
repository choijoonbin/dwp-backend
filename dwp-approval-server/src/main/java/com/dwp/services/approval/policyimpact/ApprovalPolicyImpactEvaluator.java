package com.dwp.services.approval.policyimpact;

import static com.dwp.services.approval.policyimpact.ApprovalPolicyImpactDtos.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ApprovalPolicyImpactEvaluator {
    private final ApprovalPolicyImpactRuntimePort runtime;
    public ApprovalPolicyImpactEvaluator(ApprovalPolicyImpactRuntimePort runtime) { this.runtime = runtime; }
    public void validate(String key, Rules rules) {
        if (key == null || rules == null || rules.enforcementMode() == null || rules.severity() == null
                || rules.lifecycleState() == null || !Set.of("BLOCK", "WARN", "MONITOR").contains(rules.enforcementMode())
                || !Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(rules.severity())
                || !Set.of("ACTIVE", "DISABLED", "RETIRED").contains(rules.lifecycleState())) invalid();
        Set<String> fields = switch (key) {
            case "BLOCK_SELF_APPROVAL" -> Set.of("requesterCannotDecide");
            case "REQUIRE_REJECT_REASON" -> Set.of("minimumLength");
            case "CAPTURE_DECISION_EVIDENCE" -> Set.of("retentionClass");
            case "SLA_ESCALATION" -> Set.of("warningPercent", "breachPercent");
            default -> throw new BaseException(ErrorCode.INVALID_INPUT_VALUE);
        };
        if (!fields.equals(rules.rule().keySet())) invalid();
        if (key.equals("REQUIRE_REJECT_REASON")) integer(rules.rule().get("minimumLength"), 4, 1000);
        if (key.equals("SLA_ESCALATION")) {
            int warning = integer(rules.rule().get("warningPercent"), 1, 99);
            integer(rules.rule().get("breachPercent"), warning, 100);
        }
        runtime.validate(key, rules);
    }
    public static int integer(Object value, int min, int max) {
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float) invalid();
        try {
            int parsed = new BigDecimal(value.toString()).intValueExact();
            if (parsed < min || parsed > max) invalid();
            return parsed;
        } catch (ArithmeticException | NumberFormatException error) { invalid(); return 0; }
    }
    public String digest(Head head) {
        Map<String, Object> value = new TreeMap<>();
        value.put("policyId", head.policyId().toString()); value.put("key", head.policyKey());
        value.put("rowVersion", head.rowVersion()); value.put("current", ruleMap(head.current()));
        value.put("pending", head.pending() == null ? null : ruleMap(head.pending()));
        value.put("pendingBy", head.pendingBy()); value.put("pendingAt", head.pendingAt() == null ? null : head.pendingAt().toString());
        value.put("publishedVersionId", head.publishedVersionId() == null ? null : head.publishedVersionId().toString());
        value.put("publishedVersion", head.publishedVersion());
        value.put("metadataProvenance", head.metadataProvenance());
        value.put("capturedAt", head.capturedAt() == null ? null : head.capturedAt().toString());
        return runtime.digest(value);
    }
    private Map<String, Object> ruleMap(Rules rules) {
        return Map.of("enforcementMode", rules.enforcementMode(), "severity", rules.severity(),
                "lifecycleState", rules.lifecycleState(), "rule", rules.rule());
    }
    public List<Diff> diff(Head head) {
        if (head.pending() == null) return List.of();
        Map<String, Object> before = new TreeMap<>(ruleMap(head.current()));
        Map<String, Object> after = new TreeMap<>(ruleMap(head.pending()));
        before.remove("rule"); after.remove("rule");
        head.current().rule().forEach((key, value) -> before.put("rule." + key, value));
        head.pending().rule().forEach((key, value) -> after.put("rule." + key, value));
        Set<String> keys = new java.util.TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        return keys.stream().filter(key -> !java.util.Objects.equals(before.get(key), after.get(key)))
                .map(key -> new Diff(key, !before.containsKey(key) ? "ADD" : !after.containsKey(key) ? "REMOVE" : "CHANGE",
                        before.get(key), after.get(key))).toList();
    }
    public Effect effect(Head head, boolean typed, RuntimeDifference difference) {
        if (head.pending() == null) return new Effect(List.of("NO_PROPOSAL"), false, false, false, false);
        List<String> reasons = new ArrayList<>();
        boolean changed = false;
        boolean config = false;
        switch (head.policyKey()) {
            case "BLOCK_SELF_APPROVAL" -> reasons.add("OWNER_SOD_CANNOT_BE_OVERRIDDEN");
            case "REQUIRE_REJECT_REASON" -> {
                changed = typed ? !head.current().rule().equals(head.pending().rule())
                        : runtime.legacyRejectMinimum(head.current()) != runtime.legacyRejectMinimum(head.pending());
                if (changed) reasons.add("FUTURE_REJECT_CONSTRAINT_CHANGED");
            }
            case "SLA_ESCALATION" -> {
                changed = typed && !head.current().rule().equals(head.pending().rule());
                if (changed) reasons.add("FUTURE_SLA_THRESHOLDS_CHANGED");
                reasons.add(typed ? "EXISTING_TIMERS_REMAIN_FROZEN" : "LEGACY_SLA_HAS_NO_RUNTIME_CONSUMER");
                config = !typed;
            }
            case "CAPTURE_DECISION_EVIDENCE" -> { config = true; reasons.add("AUDIT_RETENTION_CONFIGURATION_NOT_CONSUMED"); }
            default -> invalid();
        }
        if (typed && !head.policyKey().equals("CAPTURE_DECISION_EVIDENCE")
                && !head.current().lifecycleState().equals(head.pending().lifecycleState())) {
            changed = true;
            reasons.add("TYPED_RUNTIME_REQUIRES_ACTIVE_POLICY");
        }
        boolean pin = difference != null && (difference.currentFrozenPinConflict() || difference.publishInvalidatesPins());
        if (difference != null) {
            if (difference.currentFrozenPinConflict()) reasons.add("FROZEN_PIN_ALREADY_DIFFERS_FROM_LIVE_PENDING_SAVE_VERSION");
            if (difference.publishInvalidatesPins()) reasons.add("PUBLICATION_CAS_INVALIDATES_QUORUM_PINS");
            if (difference.proposedUnavailable()) { changed = true; reasons.add("PROPOSED_QUORUM_POLICY_UNAVAILABLE"); }
        }
        if (diff(head).stream().anyMatch(value -> value.path().equals("severity"))) reasons.add("SEVERITY_METADATA_CHANGED");
        if (reasons.isEmpty()) reasons.add("NO_EFFECTIVE_CONSTRAINT_CHANGE");
        return new Effect(reasons, changed, pin, config, false);
    }
    private static void invalid() { throw new BaseException(ErrorCode.INVALID_INPUT_VALUE); }
}
