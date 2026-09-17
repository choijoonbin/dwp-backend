package com.dwp.services.people.hr;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;

import java.util.UUID;

/** Stable Mail proposal command proof carried into the HR owner mutation. */
public record HrMailProposalBinding(
        UUID proposalId,
        UUID commandId,
        long proposalVersion) {

    public static HrMailProposalBinding optional(
            UUID proposalId,
            UUID commandId,
            Long proposalVersion) {
        if (proposalId == null && commandId == null && proposalVersion == null) return null;
        if (proposalId == null || commandId == null || proposalVersion == null
                || proposalVersion < 0) {
            throw new BaseException(
                    ErrorCode.INVALID_INPUT_VALUE,
                    "The complete Mail proposal owner binding is required.");
        }
        return new HrMailProposalBinding(proposalId, commandId, proposalVersion);
    }
}
