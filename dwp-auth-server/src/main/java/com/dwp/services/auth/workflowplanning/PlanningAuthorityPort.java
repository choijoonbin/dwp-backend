package com.dwp.services.auth.workflowplanning;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public interface PlanningAuthorityPort {
    void requireRegistered();
    Owner requireCurrent(PlanningProofVerifier.Verified proof);
    record Owner(String authRevision, String policyRevision, Instant expiresAt, JsonNode vector) {
        public Owner { vector=vector.deepCopy(); }
        @Override public JsonNode vector() { return vector.deepCopy(); }
        public boolean same(Owner other) { return other!=null && authRevision.equals(other.authRevision)
                && policyRevision.equals(other.policyRevision) && expiresAt.equals(other.expiresAt) && vector.equals(other.vector); }
    }
}
