package com.dwp.services.auth.informationreplay;

import static org.junit.jupiter.api.Assertions.*;
import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InformationReplayJsonTest {
    private final InformationReplayJson json = new InformationReplayJson(new ObjectMapper());
    private com.fasterxml.jackson.databind.JsonNode parse(String value) {
        return json.parse(value.getBytes(StandardCharsets.UTF_8), 524288);
    }
    private void forbidden(org.junit.jupiter.api.function.Executable action) {
        assertEquals(ErrorCode.FORBIDDEN, assertThrows(BaseException.class, action).getErrorCode());
    }

    @Test void rejectsDuplicateAndTrailingDocuments() {
        forbidden(() -> parse("{\"source\":{\"jti\":1,\"jti\":2}}"));
        forbidden(() -> parse("{} {}"));
        forbidden(() -> parse(""));
    }

    @Test void rejectsMalformedUtf8AndUnpairedUnicode() {
        forbidden(() -> json.parse(new byte[]{34, (byte) 0xc0, (byte) 0xaf, 34}, 100));
        forbidden(() -> parse("\"\\ud800\""));
        forbidden(() -> parse("{\"\\udc00\":1}"));
        assertNotNull(parse("\"\\ud83d\\ude00\""));
    }

    @Test void enforcesClosedObjectFields() {
        InformationReplayJson.exact(parse("{\"a\":1}"), Set.of("a"));
        forbidden(() -> InformationReplayJson.exact(parse("{\"a\":1,\"b\":2}"), Set.of("a")));
        forbidden(() -> InformationReplayJson.exact(parse("[]"), Set.of()));
        forbidden(() -> InformationReplayJson.exact(parse("{}"), Set.of("a")));
    }

    @Test void rejectsFractionalStringAndOverflowCounters() {
        for (String value : new String[]{"1.0", "2e0", "\"1\"", "-1", "9223372036854775808", "9007199254740992"}) {
            forbidden(() -> InformationReplayJson.number(parse("{\"version\":" + value + "}"), "version", 0, 9007199254740991L));
        }
        assertEquals(9007199254740991L, InformationReplayJson.number(parse("{\"version\":9007199254740991}"), "version", 0, 9007199254740991L));
    }

    @Test void requiresCanonicalUuidHashAndUnpaddedBase64() {
        forbidden(() -> InformationReplayJson.uuid(parse("{\"id\":\"1-1-1-1-1\"}"), "id"));
        forbidden(() -> InformationReplayJson.uuid(parse("{\"id\":\"AAAAAAAA-0000-0000-0000-000000000001\"}"), "id"));
        forbidden(() -> InformationReplayJson.hash(parse("{\"sha\":\"" + "A".repeat(64) + "\"}"), "sha"));
        forbidden(() -> InformationReplayJson.part("AA=="));
        forbidden(() -> InformationReplayJson.part("AB"));
        assertArrayEquals(new byte[]{0}, InformationReplayJson.part("AA"));
    }

    @Test void canonicalizesRecursiveKeysWithoutReorderingArrays() {
        var value = parse("{\"z\":[{\"b\":2,\"a\":1},3],\"a\":0}");
        assertEquals("{\"a\":0,\"z\":[{\"a\":1,\"b\":2},3]}", json.canonical(value));
        assertEquals(json.digest(value), json.digest(parse("{\"a\":0,\"z\":[{\"a\":1,\"b\":2},3]}")));
        assertNotEquals(json.digest(value), json.digest(parse("{\"a\":0,\"z\":[3,{\"a\":1,\"b\":2}]}")));
    }

    @Test void boundsBytesAndNesting() {
        forbidden(() -> json.parse("{}".getBytes(StandardCharsets.UTF_8), 1));
        forbidden(() -> json.parse("{}".getBytes(StandardCharsets.UTF_8), 524289));
        forbidden(() -> parse("[".repeat(41) + "0" + "]".repeat(41)));
    }

    @Test void rejectsControlAndPaddedIdentifiers() {
        for (String value : new String[]{" padded", "padded ", "line\\nfeed", "\\u007f"}) {
            forbidden(() -> InformationReplayJson.text(parse("{\"id\":\"" + value + "\"}"), "id", 80));
        }
    }
}
