package com.dwp.services.platform.mail;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.UUID;

/** Stable owner-command binding carried by the real owner mutation. */
public record MailProposalHandoffBinding(
        UUID proposalId,
        UUID commandId,
        long proposalVersion) {

    public static MailProposalHandoffBinding optional(
            UUID proposalId, UUID commandId, Long proposalVersion) {
        if (proposalId == null && commandId == null && proposalVersion == null) return null;
        if (proposalId == null || commandId == null || proposalVersion == null
                || proposalVersion < 0) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The complete Mail proposal owner binding is required.");
        }
        return new MailProposalHandoffBinding(proposalId, commandId, proposalVersion);
    }
}
