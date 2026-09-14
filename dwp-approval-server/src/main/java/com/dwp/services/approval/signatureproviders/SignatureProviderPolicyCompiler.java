package com.dwp.services.approval.signatureproviders;

import com.dwp.services.approval.signatureproviders.ApprovalSignatureProviderPolicyDtos.Rules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** A compiled policy is syntax evidence only; current registered sources must still authorize publication. */
public final class SignatureProviderPolicyCompiler {
    public static final class Compiled {
        private final Rules rules;
        private final String canonicalJson, sha256;
        private Compiled(Rules rules, String canonicalJson, String sha256) {
            this.rules = rules; this.canonicalJson = canonicalJson; this.sha256 = sha256;
        }
        public Rules rules() { return rules; }
        public String canonicalJson() { return canonicalJson; }
        public String sha256() { return sha256; }
    }
    private final ObjectMapper mapper;

    public SignatureProviderPolicyCompiler(ObjectMapper mapper) { this.mapper = mapper.copy(); }

    public Compiled compile(Rules rules) {
        SignatureProviderModel.required(rules);
        if (rules.signingEnabled() && (rules.configurationBinding() == null
                || (rules.requireTrustedCertificateChain() || rules.requireFreshRevocationEvidence()
                    || rules.requireTrustedTimestamp()) && rules.trustBundleId() == null))
            throw SignatureProviderModel.invalid("Enabled signing requires server-registered configuration and trust references");
        String canonical = canonical(mapper.valueToTree(rules));
        return new Compiled(rules, canonical, hash(canonical));
    }

    public Rules disabledInitialRules() {
        return new Rules(false, List.of(), List.of(), true, true, true, true, true, true, 365L, 3_600L, null, null);
    }

    public String reviewDigest(java.util.UUID policyId, long policyVersion, java.util.UUID draftVersionId,
                               long draftRevision, java.util.UUID originalMaker, java.util.UUID lastEditor, Compiled compiled) {
        var content = mapper.createObjectNode();
        content.put("contract", "DWP_SIGNATURE_PROVIDER_POLICY_REVIEW_V1");
        content.put("policyId", SignatureProviderModel.required(policyId).toString());
        content.put("policyVersion", SignatureProviderModel.version(policyVersion));
        content.put("draftVersionId", SignatureProviderModel.required(draftVersionId).toString());
        content.put("draftRevision", SignatureProviderModel.version(draftRevision));
        content.put("originalMakerPersonPublicId", SignatureProviderModel.required(originalMaker).toString());
        content.put("lastEditorPersonPublicId", SignatureProviderModel.required(lastEditor).toString());
        content.put("rulesSha256", SignatureProviderModel.required(compiled).sha256());
        return hash(canonical(content));
    }

    private String canonical(JsonNode node) {
        if (node.isObject()) {
            var keys = new java.util.ArrayList<String>(); node.fieldNames().forEachRemaining(keys::add); keys.sort(String::compareTo);
            return "{" + String.join(",", keys.stream().map(k -> quote(k) + ":" + canonical(node.get(k))).toList()) + "}";
        }
        if (node.isArray()) {
            var parts = new java.util.ArrayList<String>(); node.forEach(value -> parts.add(canonical(value)));
            return "[" + String.join(",", parts) + "]";
        }
        if (node.isTextual()) return quote(node.textValue());
        if (node.isNull() || node.isBoolean()) return node.toString();
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            long value = node.longValue(); SignatureProviderModel.version(value); return Long.toString(value);
        }
        throw SignatureProviderModel.invalid("Policy canonical JSON permits only bounded exact integers");
    }

    private String quote(String value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Policy serialization unavailable", failure); }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
