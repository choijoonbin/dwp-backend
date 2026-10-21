package com.dwp.services.time.absence.employment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Strict detached-authority RS256 verifier for People workload responses. */
final class EmploymentAbsenceJwsVerifier {

    private final ObjectMapper mapper;

    EmploymentAbsenceJwsVerifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    boolean verify(
            String compact,
            String expectedType,
            String expectedKid,
            String expectedDigest,
            EmploymentAbsenceContract.ResponseSigningKey key) {
        try {
            if (!key.active() || !key.keyId().equals(expectedKid)
                    || !(key.publicKey() instanceof RSAPublicKey rsa)
                    || rsa.getModulus().bitLength() < 2048) {
                return false;
            }
            String[] segments = compact.split("\\.", -1);
            if (segments.length != 3 || !canonicalBase64Url(segments[0])
                    || !canonicalBase64Url(segments[1])
                    || !canonicalBase64Url(segments[2])) {
                return false;
            }
            JsonNode header = mapper.readTree(decode(segments[0]));
            if (!header.isObject() || header.size() != 3
                    || !exactKeys(header, Set.of("alg", "typ", "kid"))
                    || !"RS256".equals(text(header, "alg"))
                    || !expectedType.equals(text(header, "typ"))
                    || !expectedKid.equals(text(header, "kid"))) {
                return false;
            }
            String payload = new String(decode(segments[1]), StandardCharsets.US_ASCII);
            if (!expectedDigest.equals(payload)) return false;
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(rsa);
            verifier.update((segments[0] + "." + segments[1])
                    .getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(decode(segments[2]));
        } catch (RuntimeException | GeneralSecurityException
                | java.io.IOException exception) {
            return false;
        }
    }

    private static boolean exactKeys(JsonNode object, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        Iterator<String> names = object.fieldNames();
        names.forEachRemaining(actual::add);
        return actual.equals(expected);
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean canonicalBase64Url(String value) {
        if (value.isEmpty() || value.indexOf('=') >= 0
                || !value.matches("[A-Za-z0-9_-]+")) {
            return false;
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(decode(value)).equals(value);
    }

    private static byte[] decode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
