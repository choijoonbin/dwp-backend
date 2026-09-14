package com.dwp.services.auth.retentionexecutionauthority;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public interface RetentionExecutionAuthorityPort {
    Observation requireCurrent(RetentionExecutionProofVerifier.Verified proof);
    record Observation(String authRevision,String policyRevision,JsonNode vector,Instant expiresAt) {
        public Observation {vector=vector.deepCopy();}
        @Override public JsonNode vector() {return vector.deepCopy();}
        public boolean same(Observation other) {
            return authRevision.equals(other.authRevision) && policyRevision.equals(other.policyRevision) && vector.equals(other.vector);
        }
    }
}
