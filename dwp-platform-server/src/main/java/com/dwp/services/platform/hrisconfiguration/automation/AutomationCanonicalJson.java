package com.dwp.services.platform.hrisconfiguration.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;

/** Deterministic closed-object canonicalization used for owner request/evidence seals. */
final class AutomationCanonicalJson {

    private final ObjectMapper json;

    AutomationCanonicalJson(ObjectMapper json) {
        this.json = json;
    }

    String digest(Object value) {
        return AutomationDomainPolicy.sha256(bytes(value));
    }

    String text(Object value) {
        return new String(bytes(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] bytes(Object value) {
        try {
            return new JsonCanonicalizer(
                    json.writeValueAsString(value)).getEncodedUTF8();
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("AUTOMATION_CANONICAL_JSON_INVALID", invalid);
        }
    }
}
