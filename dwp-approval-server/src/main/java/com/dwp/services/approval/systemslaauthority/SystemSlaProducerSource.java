package com.dwp.services.approval.systemslaauthority;

import com.dwp.services.approval.domain.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** Producer post-write checks always mint new JTIs and perform a fresh signed Auth HTTP lookup. */
public final class SystemSlaProducerSource {
    private final SystemSlaCurrentSource current;
    private final ApprovalSystemSlaNativeSource nativeSource;
    private final Supplier<SystemSlaSourceProofIssuer> issuer;
    private final Supplier<AuthApprovalSystemSlaAuthorityClient> client;
    private final SystemSlaJson json;
    private final Clock clock;
    private final SystemSlaSourceWitnessJournal journal;
    public SystemSlaProducerSource(SystemSlaCurrentSource current,ApprovalSystemSlaNativeSource nativeSource,Supplier<SystemSlaSourceProofIssuer> issuer,
            Supplier<AuthApprovalSystemSlaAuthorityClient> client,SystemSlaJson json,Clock clock,SystemSlaSourceWitnessJournal journal) {
        this.current = current; this.nativeSource = nativeSource; this.issuer = issuer; this.client = client; this.json = json; this.clock = clock;
        this.journal=Objects.requireNonNull(journal);
    }
    public ApprovalWorkflowQuorumSlaRuntime.ProducerAuthority authority(ApprovalWorkflowQuorumSlaRuntime.Lease lease) {
        var original = current.produce(lease); var seal = original.exchange().seal(); var recipients = new ArrayList<Long>();
        for (var seat : original.recipients()) if (seat.get("eligible").booleanValue() && seal.taskEligible(SystemSlaJson.uuid(seat,"taskId"))) recipients.add(SystemSlaJson.integer(seat,"userId",true));
        String revision = "asla-" + SystemSlaJson.sha((original.authorityRevision()+'\n'+seal.nativeVectorSha256()).getBytes(StandardCharsets.UTF_8));
        return new ApprovalWorkflowQuorumSlaRuntime.ProducerAuthority(recipients,revision,original.expiresAt(),() -> fresh(original),event -> journal.record(event,original));
    }
    private void fresh(SystemSlaSourceAttestationVerifier.Verified original) {
        var seal = original.exchange().seal(); nativeSource.unchanged(seal);
        var exchange = issuer.get().issue(seal); var fresh = client.get().evaluate(exchange);
        if (fresh.exchange().seal()!=seal || !fresh.authorityRevision().equals(original.authorityRevision())
                || !json.digest(stable(fresh.recipients())).equals(json.digest(stable(original.recipients())))
                || !fresh.expiresAt().isAfter(clock.instant()) || !original.expiresAt().isAfter(clock.instant())) throw SystemSlaJson.changed();
        nativeSource.unchanged(seal);
    }
    private ArrayNode stable(com.fasterxml.jackson.databind.JsonNode recipients) {
        var output = JsonNodeFactory.instance.arrayNode();
        for (var seat : recipients) { var value = (ObjectNode) seat.deepCopy(); value.remove("expiresAt"); output.add(value); }
        return output;
    }
}
