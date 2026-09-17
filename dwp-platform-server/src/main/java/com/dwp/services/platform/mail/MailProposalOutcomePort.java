package com.dwp.services.platform.mail;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public interface MailProposalOutcomePort {

    enum Owner {
        MAIL,
        CALENDAR,
        WORK,
        HR
    }

    void validate(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding);

    /**
     * Validates that a remote owner may begin a new, irreversible mutation.
     * Terminal and uncertain outcomes are intentionally excluded; they require
     * replay/reconciliation rather than another domain write.
     */
    void validateNewExecution(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding,
            OwnerMutation mutation);

    /**
     * Releases an execution reservation only after the owner transaction has proved that it
     * rolled back before creating the target resource. A missing callback deliberately leaves
     * the handoff reserved so user cancellation fails closed and reconciliation remains possible.
     */
    MailDtos.ProposalHandoff notExecuted(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding,
            String reasonCode,
            String correlationId);

    /** Returns the current owner receipt without changing it. */
    MailDtos.ProposalHandoff status(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding);

    MailDtos.ProposalHandoff executed(
            long tenantId,
            long actorId,
            Owner owner,
            MailProposalHandoffBinding binding,
            String resultRef,
            String correlationId);

    MailDtos.ProposalHandoff cancel(
            long tenantId,
            long actorId,
            UUID proposalId,
            UUID commandId,
            long proposalVersion,
            String correlationId);

    record OwnerMutation(UUID sourceThreadId, Map<String, Object> payload) {
        public OwnerMutation {
            payload = payload == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }
}
