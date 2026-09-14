package com.dwp.services.auth.workflowruntime;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public interface WorkflowRuntimeIdentityPort {
    OwnerEvidence requireOwner(WorkflowRuntimeProofVerifier.Verified proof);
    record OwnerEvidence(String authRevision, String policyRevision, Instant expiresAt, JsonNode stableVector) {
        public OwnerEvidence { stableVector = stableVector.deepCopy(); }
        @Override public JsonNode stableVector() { return stableVector.deepCopy(); }
    }
}
