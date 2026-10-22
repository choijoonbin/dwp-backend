package com.dwp.services.people.integration;

import com.dwp.services.people.hris.contracts.hrm012.Hrm012CanonicalJson;
import com.dwp.services.people.hris.contracts.hrm012.Hrm012Problem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Hrm012CanonicalJsonTest {
    private final Hrm012CanonicalJson canonical = new Hrm012CanonicalJson(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void followsIndependentRfc8785NumberStringAndPropertyOrderingVectors() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("numbers", List.of(
                333333333.33333329d, 1.0e30d, 4.50d, 2.0e-3d,
                1.0e-27d, -0.0d, 1.0e-6d, 1.0e21d));
        value.put("string", "€$\u000f\nA'B\"\\\"");
        value.put("\u20ac", "Euro Sign");
        value.put("\r", "Carriage Return");
        value.put("\ufb33", "Hebrew Letter Dalet With Dagesh");
        value.put("1", "One");
        value.put("\ud83d\ude00", "Emoji: Grinning Face");
        value.put("\u0080", "Control");
        value.put("ö", "Latin Small Letter O With Diaeresis");

        String encoded = new String(canonical.bytes(value), StandardCharsets.UTF_8);

        assertThat(encoded).isEqualTo(
                "{\"\\r\":\"Carriage Return\",\"1\":\"One\","
                        + "\"numbers\":[333333333.3333333,1e+30,4.5,0.002,"
                        + "1e-27,0,0.000001,1e+21],"
                        + "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\"\","
                        + "\"\u0080\":\"Control\",\"ö\":\"Latin Small Letter O With Diaeresis\","
                        + "\"€\":\"Euro Sign\",\"😀\":\"Emoji: Grinning Face\","
                        + "\"דּ\":\"Hebrew Letter Dalet With Dagesh\"}");
    }

    @Test
    void jcsEquivalentIntegralAndDecimalInputsHaveOneDigest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThat(canonical.digest(mapper.readTree("{\"value\":1}")))
                .isEqualTo(canonical.digest(mapper.readTree("{\"value\":1.0}")));
    }

    @Test
    void matchesIndependentRfc8785AndEcmaBinary64BoundaryVectors() {
        Map<Long, String> vectors = new LinkedHashMap<>();
        vectors.put(0x0000000000000000L, "0");
        vectors.put(0x8000000000000000L, "0");
        vectors.put(0x0000000000000001L, "5e-324");
        vectors.put(0x8000000000000001L, "-5e-324");
        vectors.put(0x0000000000000002L, "1e-323");
        vectors.put(0x0000000000000014L, "1e-322");
        vectors.put(0x7fefffffffffffffL, "1.7976931348623157e+308");
        vectors.put(0xffefffffffffffffL, "-1.7976931348623157e+308");
        vectors.put(0x4340000000000000L, "9007199254740992");
        vectors.put(0xc340000000000000L, "-9007199254740992");
        vectors.put(0x4430000000000000L, "295147905179352830000");
        vectors.put(0x44b52d02c7e14af5L, "9.999999999999997e+22");
        vectors.put(0x44b52d02c7e14af6L, "1e+23");
        vectors.put(0x44b52d02c7e14af7L, "1.0000000000000001e+23");
        vectors.put(0x444b1ae4d6e2ef4eL, "999999999999999700000");
        vectors.put(0x444b1ae4d6e2ef4fL, "999999999999999900000");
        vectors.put(0x444b1ae4d6e2ef50L, "1e+21");
        vectors.put(0x3eb0c6f7a0b5ed8cL, "9.999999999999997e-7");
        vectors.put(0x3eb0c6f7a0b5ed8dL, "0.000001");
        vectors.put(0x41b3de4355555553L, "333333333.3333332");
        vectors.put(0x41b3de4355555554L, "333333333.33333325");
        vectors.put(0x41b3de4355555555L, "333333333.3333333");
        vectors.put(0x41b3de4355555556L, "333333333.3333334");
        vectors.put(0x41b3de4355555557L, "333333333.33333343");
        vectors.put(0xbecbf647612f3696L, "-0.0000033333333333333333");
        vectors.put(0x43143ff3c1cb0959L, "1424953923781206.2");

        vectors.forEach((bits, expected) -> assertThat(new String(
                canonical.bytes(Double.longBitsToDouble(bits)),
                StandardCharsets.UTF_8)).as("IEEE-754 bits %016x", bits)
                .isEqualTo(expected));
    }

    @Test
    void rejectsNonFiniteNumbersAndLoneSurrogates() {
        assertThatThrownBy(() -> canonical.bytes(Map.of("number", Double.NaN)))
                .isInstanceOf(Hrm012Problem.class);
        assertThatThrownBy(() -> canonical.bytes(Map.of("number", Double.POSITIVE_INFINITY)))
                .isInstanceOf(Hrm012Problem.class);
        assertThatThrownBy(() -> canonical.bytes(Map.of("text", "\ud800")))
                .isInstanceOf(Hrm012Problem.class);
    }
}
