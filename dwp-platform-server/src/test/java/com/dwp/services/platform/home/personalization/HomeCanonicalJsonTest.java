package com.dwp.services.platform.home.personalization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HomeCanonicalJsonTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HomeCanonicalJson canonicalJson = new HomeCanonicalJson(objectMapper);

    @Test
    void equivalentNestedObjectOrdersHaveTheSameFingerprint() throws Exception {
        var first = objectMapper.readTree(
                "{\"z\":1,\"request\":{\"b\":2,\"a\":1},\"items\":[{\"y\":2,\"x\":1}]}");
        var second = objectMapper.readTree(
                "{\"items\":[{\"x\":1,\"y\":2}],\"request\":{\"a\":1,\"b\":2},\"z\":1}");

        assertThat(canonicalJson.fingerprint(first))
                .isEqualTo(canonicalJson.fingerprint(second));
    }

    @Test
    void operationAndTargetRemainPartOfTheFingerprint() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("operation", "UPDATE_VIEW");
        first.put("target", "view-a");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("target", "view-b");
        second.put("operation", "UPDATE_VIEW");

        assertThat(canonicalJson.fingerprint(first))
                .isNotEqualTo(canonicalJson.fingerprint(second));
    }
}
