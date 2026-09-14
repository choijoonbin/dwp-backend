package com.dwp.services.auth.systemslaauthority;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class SystemSlaCurrentAuthority implements SystemSlaAuthorityPort {
    private final SystemSlaSubjectRepository repository;
    private final Clock clock;
    @Autowired public SystemSlaCurrentAuthority(SystemSlaSubjectRepository repository) { this(repository, Clock.systemUTC()); }
    public SystemSlaCurrentAuthority(SystemSlaSubjectRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }
    @Override public Current current(SystemSlaProofVerifier.Verified proof) {
        if (proof == null || !proof.expiresAt().isAfter(clock.instant())) throw SystemSlaJson.denied();
        var bindings = proof.bindings(); var before = repository.snapshot(bindings);
        var recipients = new ArrayList<Recipient>(); Instant expiry = proof.expiresAt();
        var request = bindings.source().get("request"); String role = bindings.source().get("stage").get("candidateRole").textValue();
        for (var seat : bindings.audience()) {
            long user = SystemSlaJson.integer(seat, "userId", true); var subject = before.subjects().get(user);
            var person = SystemSlaJson.uuid(seat, "personPublicId"); var principal = subject.principal();
            Instant seatExpiry = subject.expiresAt() == null || subject.expiresAt().isAfter(proof.expiresAt()) ? proof.expiresAt() : subject.expiresAt();
            String reason = "ELIGIBLE";
            if (!before.resourceActive()) reason = "RESOURCE_SET_INACTIVE";
            else if (principal.isNull()) reason = "SUBJECT_MISSING";
            else if (!"ACTIVE".equals(principal.get("tenantStatus").asText())) reason = "TENANT_INACTIVE";
            else if (!"ACTIVE".equals(principal.get("status").asText()) || !"TENANT".equals(principal.get("identityPlane").asText())) reason = "SUBJECT_INACTIVE";
            else if (!person.toString().equals(principal.get("personPublicId").asText())) reason = "PERSON_CHANGED";
            else if (user == request.get("requesterUserId").longValue() || person.toString().equals(request.get("requesterPersonPublicId").textValue())) reason = "REQUESTER_SOD";
            else if (!subject.role(role)) reason = "ROLE_MISSING";
            else if (!subject.permission("APP.APPROVALS:VIEW")) reason = "APP_NOT_ENTITLED";
            else if (!subject.permission("ACTION.APPROVAL_TASK:VIEW") || !subject.permission("ACTION.APPROVAL_TASK:APPROVE")) reason = "TASK_PERMISSION_DENIED";
            else if (!seatExpiry.isAfter(clock.instant())) reason = "SOURCE_EXPIRED";
            if (seatExpiry.isBefore(expiry)) expiry = seatExpiry;
            recipients.add(new Recipient(user, person, SystemSlaJson.uuid(seat, "taskId"), SystemSlaJson.integer(seat, "taskVersion", false), "ELIGIBLE".equals(reason), reason, seatExpiry));
        }
        var after = repository.snapshot(bindings);
        if (!before.sha256().equals(after.sha256()) || before.resourceActive() != after.resourceActive()) throw SystemSlaJson.changed();
        expiry = Instant.ofEpochSecond(expiry.getEpochSecond());
        if (!expiry.isAfter(clock.instant())) throw SystemSlaJson.denied();
        String vector = SystemSlaJson.sha(bindings.sourceDigest() + '\n' + before.sha256());
        return new Current("asla-" + vector, vector, clock.instant(), expiry, recipients);
    }
}
