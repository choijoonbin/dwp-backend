package com.dwp.services.payroll.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.dwp.platform.contracts.hris.home.HrisHomeMaterializationDigestV1;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.MASK;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.OMIT;
import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.Decision.VIEW;
import static com.dwp.services.payroll.home.PayrollHomePorts.*;

final class PayrollHomeContributionProjector {

    private static final String DEEP_LINK = "/hr/pay";
    private final PayrollHomePayloadCodec codec;
    private final Duration freshFor;
    private final Duration staleAfter;

    PayrollHomeContributionProjector(
            PayrollHomePayloadCodec codec, Duration freshFor, Duration staleAfter) {
        this.codec = codec;
        this.freshFor = freshFor;
        this.staleAfter = staleAfter;
    }

    HrisHomeContributionMaterializationV1 project(
            ResolvedPayrollHomeAuthority authority,
            PayrollHomeSourceSnapshot source) {
        Map<String, HrisHomeContributionMaterializationV1.Decision> requested =
                authority.fieldDecisions();
        List<String> missing = new ArrayList<>();

        var period = text(requested.get("payPeriodLabel"), source.payPeriodLabel(), missing,
                "payPeriodLabel");
        boolean aggregate = authority.ownerPopulationScope()
                != HrisHomeContributionMaterializationV1.OwnerPopulationScope.SELF;
        var gross = money(aggregate ? OMIT : requested.get("grossPay"), source.grossPay(),
                missing, "grossPay");
        var net = money(aggregate ? OMIT : requested.get("netPay"), source.netPay(),
                missing, "netPay");
        var payslip = bool(requested.get("payslipAvailable"), source.payslipAvailable(),
                missing, "payslipAvailable");

        var data = new HrisHomeContributionMaterializationV1.PayrollHomeData(
                HrisHomeContributionMaterializationV1.WidgetType.PAYROLL_SUMMARY,
                period, gross, net, payslip);
        List<HrisHomeContributionMaterializationV1.FieldDecision> decisions = List.of(
                decision("payPeriodLabel", period), decision("grossPay", gross),
                decision("netPay", net), decision("payslipAvailable", payslip));

        boolean anyVisible = decisions.stream().anyMatch(value -> value.decision() != OMIT);
        var state = missing.isEmpty()
                ? HrisHomeContributionMaterializationV1.State.FRESH
                : anyVisible
                ? HrisHomeContributionMaterializationV1.State.DEGRADED
                : HrisHomeContributionMaterializationV1.State.UNAVAILABLE;
        List<HrisHomeContributionMaterializationV1.PartialFailure> failures = missing.isEmpty()
                ? List.of()
                : List.of(new HrisHomeContributionMaterializationV1.PartialFailure(
                        HrisHomeContributionMaterializationV1.Module.PAY,
                        authority.widgetKey(), authority.browserAudience(),
                        authority.ownerPopulationScope(), authority.scopeRevision(),
                        authority.fieldPolicyRevision(),
                        HrisHomeContributionMaterializationV1.PURPOSE,
                        HrisHomeContributionMaterializationV1.FailureCode.OWNER_UNAVAILABLE,
                        false, failureId(authority, source, missing), source.asOf()));

        var freshUntil = source.asOf().plus(freshFor);
        var staleAt = source.asOf().plus(staleAfter);
        HrisHomeContributionMaterializationV1 draft = materialization(
                authority, source, data, decisions, failures, state, freshUntil, staleAt,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_REFERENCE,
                HrisHomeMaterializationDigestV1.PLACEHOLDER_DIGEST);
        String digest = codec.digest(draft);
        String reference = codec.reference(authority, source.sourceVersion(), digest);
        HrisHomeContributionMaterializationV1 result = materialization(
                authority, source, data, decisions, failures, state, freshUntil, staleAt,
                reference, digest);
        if (!HrisHomeMaterializationDigestV1.matches(result, digest)) {
            throw new IllegalStateException("Payroll Home canonical digest was not stable.");
        }
        return result;
    }

    private HrisHomeContributionMaterializationV1 materialization(
            ResolvedPayrollHomeAuthority authority,
            PayrollHomeSourceSnapshot source,
            HrisHomeContributionMaterializationV1.PayrollHomeData data,
            List<HrisHomeContributionMaterializationV1.FieldDecision> decisions,
            List<HrisHomeContributionMaterializationV1.PartialFailure> failures,
            HrisHomeContributionMaterializationV1.State state,
            java.time.Instant freshUntil,
            java.time.Instant staleAt,
            String payloadRef,
            String payloadDigest) {
        return new HrisHomeContributionMaterializationV1(
                HrisHomeContributionMaterializationV1.PROFILE,
                HrisHomeContributionMaterializationV1.Module.PAY,
                authority.widgetKey(),
                HrisHomeContributionMaterializationV1.WidgetType.PAYROLL_SUMMARY,
                authority.browserAudience(), authority.ownerPopulationScope(),
                authority.scopeRevision(), authority.populationScopeDigest(),
                authority.fieldPolicyRevision(), HrisHomeContributionMaterializationV1.PURPOSE,
                state, source.asOf(), freshUntil, staleAt, DEEP_LINK,
                source.sourceVersion(), payloadRef, payloadDigest, source.asOf(),
                data, decisions, failures);
    }

    private HrisHomeContributionMaterializationV1.TextProjection text(
            HrisHomeContributionMaterializationV1.Decision decision,
            Optional<String> value,
            List<String> missing,
            String field) {
        if (decision == OMIT) return omitted();
        if (decision == MASK) return masked();
        if (decision == VIEW && value.isPresent()) {
            return new HrisHomeContributionMaterializationV1.ViewedText(VIEW, value.get());
        }
        missing.add(field);
        return omitted();
    }

    private HrisHomeContributionMaterializationV1.MoneyProjection money(
            HrisHomeContributionMaterializationV1.Decision decision,
            Optional<MoneyValue> value,
            List<String> missing,
            String field) {
        if (decision == OMIT) return omitted();
        if (decision == MASK) return masked();
        if (decision == VIEW && value.isPresent()) {
            return new HrisHomeContributionMaterializationV1.ViewedMoney(
                    VIEW, value.get().amount(), value.get().currency());
        }
        missing.add(field);
        return omitted();
    }

    private HrisHomeContributionMaterializationV1.BooleanProjection bool(
            HrisHomeContributionMaterializationV1.Decision decision,
            Optional<Boolean> value,
            List<String> missing,
            String field) {
        if (decision == OMIT) return omitted();
        if (decision == MASK) return masked();
        if (decision == VIEW && value.isPresent()) {
            return new HrisHomeContributionMaterializationV1.ViewedBoolean(VIEW, value.get());
        }
        missing.add(field);
        return omitted();
    }

    private HrisHomeContributionMaterializationV1.MaskedField masked() {
        return new HrisHomeContributionMaterializationV1.MaskedField(
                MASK, HrisHomeContributionMaterializationV1.MASKED_DISPLAY);
    }

    private HrisHomeContributionMaterializationV1.OmittedField omitted() {
        return new HrisHomeContributionMaterializationV1.OmittedField(OMIT);
    }

    private HrisHomeContributionMaterializationV1.FieldDecision decision(
            String field, HrisHomeContributionMaterializationV1.Projection projection) {
        return new HrisHomeContributionMaterializationV1.FieldDecision(
                field, projection.decision());
    }

    private UUID failureId(
            ResolvedPayrollHomeAuthority authority,
            PayrollHomeSourceSnapshot source,
            List<String> missing) {
        String material = authority.tenantId() + "|" + authority.widgetKey() + "|"
                + authority.scopeRevision() + "|" + source.sourceVersion() + "|"
                + String.join(",", missing);
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
