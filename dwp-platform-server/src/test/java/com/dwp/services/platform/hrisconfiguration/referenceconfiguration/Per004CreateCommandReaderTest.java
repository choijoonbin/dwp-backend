package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.charset.StandardCharsets;
import static com.dwp.services.platform.hrisconfiguration.referenceconfiguration.Per004TestFixtures.*;
import static org.assertj.core.api.Assertions.*;

class Per004CreateCommandReaderTest {
    @Test void readsOpaqueObjectAndRejectsUnknownDuplicateOrMalformedBodiesWithoutEcho() throws Exception {
        String json = MAPPER.writeValueAsString(typed());
        assertThat(read(json)).isEqualTo(typed());
        for (String bad : new String[]{json.replace("\"domain\":\"PER\"", "\"domain\":\"PER\",\"domain\":\"SYS\""),
                json.replaceFirst("\\{", "{\"unknownSecret\":\"transit-secret\","), json + "{}",
                json.substring(0, json.length() - 1), json.replace("\"schemaVersion\":1", "\"schemaVersion\":\"transit-secret\"")}) {
            assertThatThrownBy(() -> read(bad)).isInstanceOf(BaseException.class)
                    .hasMessageNotContaining("transit-secret").hasMessageNotContaining("syntheticSecret").hasNoCause();
        }
    }
    @Test void rawUtf8LimitCountsOpaqueContentIncludingWhitespace() throws Exception {
        String prefix = "{\"domain\":\"PER\",\"effectiveFrom\":\"2026-10-19T08:00:00Z\",\"schemaKey\":\"" + SCHEMA
                + "\",\"schemaVersion\":1,\"ownerPayload\":";
        String exact = "{\"k\":\"" + "x".repeat(262136) + "\"}";
        assertThat(exact.getBytes(StandardCharsets.UTF_8)).hasSize(262144);
        assertThat(read(prefix + exact + "}").ownerPayload()).isNotNull();
        assertThatThrownBy(() -> read(prefix + "{ " + exact.substring(1) + "}"))
                .isInstanceOf(BaseException.class);
    }
    private ReferenceConfigurationDtos.CreateVersionCommand read(String json) {
        var request = new MockHttpServletRequest(); request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return Per004CreateCommandReader.read(request, MAPPER);
    }
}
