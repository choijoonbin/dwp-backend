package com.dwp.services.time.workregime;

import com.dwp.services.time.workregime.WorkRegimeApiModels.ReceiptView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SegmentView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationRowView;
import com.dwp.services.time.workregime.WorkRegimeApiModels.SimulationView;
import com.dwp.services.time.workregime.WorkRegimeModels.Authority;
import com.dwp.services.time.workregime.WorkRegimeModels.CommandReceipt;
import com.dwp.services.time.workregime.WorkRegimeModels.DiffKind;
import com.dwp.services.time.workregime.WorkRegimeModels.Duty;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyCandidate;
import com.dwp.services.time.workregime.WorkRegimeModels.PolicyState;
import com.dwp.services.time.workregime.WorkRegimeModels.ResolutionCode;
import com.dwp.services.time.workregime.WorkRegimeModels.ResolvedSegment;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePack;
import com.dwp.services.time.workregime.WorkRegimeModels.RulePackState;
import com.dwp.services.time.workregime.WorkRegimeModels.ScheduleDiff;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationResult;
import com.dwp.services.time.workregime.WorkRegimeModels.SimulationState;
import com.dwp.services.time.workregime.WorkRegimeRepository.StoredSimulation;
import com.dwp.services.time.workregime.WorkRegimeRepository.WorkPlanRecord;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/** Pure owner-view mapping kept separate from command coordination and persistence. */
final class WorkRegimeViewMapper {

    private WorkRegimeViewMapper() {
    }

    static SimulationView simulation(StoredSimulation stored) {
        SimulationResult result = stored.result();
        return new SimulationView(
                stored.workRegimePublicId().toString(), stored.baseVersion(),
                result.state() == SimulationState.SUCCEEDED ? "COMPLETE" : "PARTIAL",
                result.calculatedAt(), Long.toString(result.policyRevision()),
                result.differences().stream().map(WorkRegimeViewMapper::row).toList(),
                result.findings(), List.of());
    }

    static SegmentView segment(ResolvedSegment segment) {
        ZoneId zone = ZoneId.of(segment.zoneId());
        var start = segment.startAt().atZone(zone);
        var end = segment.endAt().atZone(zone);
        return new SegmentView(
                segment.assignmentId() + ":" + segment.localWorkDate()
                        + ":" + segment.segmentKey(),
                segment.segmentKey(), segment.startAt(), segment.endAt(),
                localStamp(start.toLocalDate().toString(), start.toLocalTime().toString()),
                localStamp(end.toLocalDate().toString(), end.toLocalTime().toString()),
                offset(segment.startOffset()), offset(segment.endOffset()),
                segment.overnight(), segment.dstResolution().name());
    }

    static ReceiptView receipt(CommandReceipt receipt) {
        return new ReceiptView(
                receipt.receiptId().toString(),
                receipt.aggregateId().toString(),
                receipt.operation().name(),
                receipt.idempotencyKey().toString(),
                receipt.state().name(),
                receipt.updatedAt());
    }

    static String packState(WorkPlanRecord plan, LocalDate date, List<RulePack> packs) {
        List<RulePack> exact = packs.stream()
                .filter(pack -> pack.publicId().equals(plan.rulePackPublicId()))
                .filter(pack -> pack.policyRevision() == plan.policyRevision())
                .toList();
        if (exact.isEmpty()) return "MISSING";
        if (exact.size() != 1) return "INVALID";
        RulePack pack = exact.getFirst();
        if (pack.state() == RulePackState.REVOKED) return "REVOKED";
        if (!pack.period().contains(date)) return "EXPIRED";
        if (pack.state() != RulePackState.PUBLISHED || !pack.signatureVerified()) return "INVALID";
        return "CURRENT";
    }

    static String resolutionState(ResolutionCode code) {
        return switch (code) {
            case RESOLVED -> "RESOLVED";
            case POLICY_OVERLAP -> "OVERLAP";
            case PACK_REVOKED -> "REVOKED";
            case PACK_INVALID, PACK_CONFLICT -> "INVALID";
            case STALE_POLICY -> "STALE";
            default -> code.name();
        };
    }

    static String precedenceLevel(PolicyCandidate candidate) {
        return switch (candidate.scopeType()) {
            case GLOBAL, COUNTRY, SUBDIVISION -> "JURISDICTION";
            case TENANT, LEGAL_ENTITY -> "LEGAL_ENTITY";
            case BUSINESS_UNIT -> "COLLECTIVE_AGREEMENT";
            case WORKPLACE -> "LOCATION";
            case POPULATION -> "JOB";
            case PERSON -> "WORKER";
            case ASSIGNMENT -> "ASSIGNMENT";
        };
    }

    static List<String> availableActions(
            Authority authority, PolicyState state, boolean resolved) {
        if (state == PolicyState.DRAFT
                && authority.duties().contains(Duty.TIME_CONFIG_AUTHOR)) {
            return List.of("VALIDATE");
        }
        if (state == PolicyState.VALIDATED && resolved
                && authority.duties().contains(Duty.TIME_CONFIG_AUTHOR)) {
            return List.of("SIMULATE");
        }
        return List.of();
    }

    private static SimulationRowView row(ScheduleDiff difference) {
        return new SimulationRowView(
                difference.assignmentId() + ":" + difference.localWorkDate()
                        + ":" + difference.segmentKey(),
                difference.segmentKey(),
                minutes(difference.currentMinutes()), minutes(difference.draftMinutes()),
                difference.kind() == DiffKind.UNCHANGED ? "INFO"
                        : difference.kind() == DiffKind.REMOVED ? "WARNING" : "INFO");
    }

    private static String minutes(Long value) {
        return value == null ? "—" : value + " min";
    }

    private static String localStamp(String date, String time) {
        String minute = time.length() >= 5 ? time.substring(0, 5) : time;
        return date + " " + minute;
    }

    private static String offset(ZoneOffset value) {
        int total = value.getTotalSeconds();
        char sign = total < 0 ? '-' : '+';
        int absolute = Math.abs(total);
        int hours = absolute / 3600;
        int minutes = (absolute % 3600) / 60;
        return String.format("%c%02d:%02d", sign, hours, minutes);
    }
}
