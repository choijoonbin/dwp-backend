package com.dwp.services.time.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Canonical digest of the closed response with its two circular reference fields removed. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.home.enabled",
        havingValue = "true",
        matchIfMissing = false)
final class TimeHomeCanonicalDigest {

    private final ObjectMapper canonical;

    TimeHomeCanonicalDigest(ObjectMapper mapper) {
        canonical = Objects.requireNonNull(mapper, "mapper").copy();
        canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    String digest(HrisHomeContributionMaterializationV1 materialization) {
        Objects.requireNonNull(materialization, "materialization");
        try {
            ObjectNode root = canonical.valueToTree(materialization);
            root.remove("payloadRef");
            root.remove("payloadDigest");
            byte[] bytes = canonical.writeValueAsBytes(root);
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    "Time Home canonical digest could not be computed", failure);
        }
    }
}
