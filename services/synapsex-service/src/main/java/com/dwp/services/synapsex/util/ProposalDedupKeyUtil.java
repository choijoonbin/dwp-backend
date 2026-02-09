package com.dwp.services.synapsex.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase2 202: action proposal 멱등 키 생성
 * dedupKey = sha256(lower(type) + '|' + canonicalize(payload) + '|' + normalize(rationale))
 */
public final class ProposalDedupKeyUtil {

    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper OM = new ObjectMapper();

    private ProposalDedupKeyUtil() {
    }

    /**
     * 멱등 키 생성. 동일 입력이면 동일 키.
     */
    public static String compute(String type, JsonNode payload, String rationale) {
        String typePart = (type != null ? type : "").toLowerCase().trim();
        String payloadPart = canonicalizePayload(payload);
        String rationalePart = normalizeRationale(rationale);
        String input = typePart + "|" + payloadPart + "|" + rationalePart;
        return sha256(input);
    }

    private static String canonicalizePayload(JsonNode payload) {
        if (payload == null || payload.isNull()) return "";
        try {
            return OM.writeValueAsString(canonicalize(payload));
        } catch (JsonProcessingException e) {
            return payload.toString();
        }
    }

    private static Object canonicalize(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> iter = node.fields();
            while (iter.hasNext()) {
                Map.Entry<String, JsonNode> entry = iter.next();
                sorted.put(entry.getKey(), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode child : node) {
                list.add(canonicalize(child));
            }
            return list;
        }
        return node.isNumber() ? node.numberValue() : node.asText();
    }

    /**
     * rationale 정규화: trim + 연속 공백 → 1칸 + toLowerCase.
     * 대소문자만 다른 동일 문장도 동일 dedup_key로 처리(중복 방지).
     */
    private static String normalizeRationale(String rationale) {
        if (rationale == null) return "";
        return rationale.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
