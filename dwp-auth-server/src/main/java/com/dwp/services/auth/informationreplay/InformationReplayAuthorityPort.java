package com.dwp.services.auth.informationreplay;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public interface InformationReplayAuthorityPort {
    Current requireCurrent(InformationReplayProofVerifier.Verified proof);
    record Current(String ownerAuthRevision, String ownerPolicyRevision, Instant expiresAt, JsonNode vector, JsonNode result) {
        public Current { vector = vector.deepCopy(); result = result.deepCopy(); }
        @Override public JsonNode vector() { return vector.deepCopy(); }
        @Override public JsonNode result() { return result.deepCopy(); }
    }
}
