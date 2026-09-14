package com.dwp.services.approval.systemslaauthority;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Dedicated owner, transport, and Auth response families; configured foreign keys cannot alias them. */
public final class SystemSlaSourceKeys {
    private final RSAKey owner, transport;
    private final Map<String, RSAKey> attestations;
    public SystemSlaSourceKeys(SystemSlaJson json, String ownerJwk, String transportJwk, String publicJwks, List<String> forbidden) {
        try {
            owner = privateKey(json, ownerJwk); transport = privateKey(json, transportJwk);
            var tree = json.parse(publicJwks.getBytes(StandardCharsets.UTF_8)); SystemSlaJson.keys(tree, Set.of("keys"));
            var values = JWKSet.parse(publicJwks).getKeys(); if (values.isEmpty() || values.size() > 8 || forbidden == null) throw SystemSlaJson.unavailable();
            var ids = new HashSet<String>(); var prints = new HashSet<String>();
            unique(owner, ids, prints); unique(transport, ids, prints); var result = new HashMap<String, RSAKey>();
            for (var value : values) {
                if (!(value instanceof RSAKey rsa) || rsa.isPrivate()) throw SystemSlaJson.unavailable();
                valid(rsa); unique(rsa, ids, prints); result.put(rsa.getKeyID(), rsa);
            }
            attestations = Map.copyOf(result);
            for (String raw : forbidden) {
                if (raw == null || raw.isBlank()) continue;
                if (raw.startsWith("kid:")) { if (ids.contains(raw.substring(4))) throw SystemSlaJson.unavailable(); continue; }
                for (var rsa : foreign(json, raw)) if (ids.contains(rsa.getKeyID()) || prints.contains(rsa.computeThumbprint().toString())) throw SystemSlaJson.unavailable();
            }
        } catch (Exception invalid) { throw SystemSlaJson.unavailable(); }
    }
    static List<RSAKey> foreign(SystemSlaJson json, String raw) throws Exception {
        if (raw.stripLeading().startsWith("{")) {
            var value = json.parse(raw.getBytes(StandardCharsets.UTF_8));
            var keys = value.has("keys") ? JWKSet.parse(raw).getKeys() : List.of(JWK.parse(raw));
            var result = new ArrayList<RSAKey>();
            for (var key : keys) { if (!(key instanceof RSAKey rsa)) throw SystemSlaJson.unavailable(); result.add(rsa.toPublicJWK()); }
            return result;
        }
        if (raw.length() > 65536) throw SystemSlaJson.unavailable();
        String encoded = raw.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] bytes = Base64.getDecoder().decode(encoded); var factory = java.security.KeyFactory.getInstance("RSA");
        java.security.interfaces.RSAPublicKey publicKey;
        try { publicKey = (java.security.interfaces.RSAPublicKey) factory.generatePublic(new java.security.spec.X509EncodedKeySpec(bytes)); }
        catch (java.security.spec.InvalidKeySpecException privateKey) {
            var rsa = (java.security.interfaces.RSAPrivateCrtKey) factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(bytes));
            publicKey = (java.security.interfaces.RSAPublicKey) factory.generatePublic(new java.security.spec.RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        }
        return List.of(new RSAKey.Builder(publicKey).build());
    }
    private static RSAKey privateKey(SystemSlaJson json, String raw) throws Exception {
        json.parse(raw.getBytes(StandardCharsets.UTF_8)); var key = RSAKey.parse(raw);
        if (!key.isPrivate()) throw SystemSlaJson.unavailable(); valid(key); return key;
    }
    private static void valid(RSAKey key) {
        if (key.size() < 2048 || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                || key.getKeyID() == null || !key.getKeyID().matches("[A-Za-z0-9_-]{1,80}")) throw SystemSlaJson.unavailable();
    }
    private static void unique(RSAKey key, Set<String> ids, Set<String> prints) throws Exception {
        if (!ids.add(key.getKeyID()) || !prints.add(key.computeThumbprint().toString())) throw SystemSlaJson.unavailable();
    }
    RSAKey owner() { return owner; }
    RSAKey transport() { return transport; }
    RSAKey attestation(String kid) { var key = attestations.get(kid); if (key == null) throw SystemSlaJson.denied(); return key; }
    public List<RSAKey> publicKeys() {
        var result = new ArrayList<RSAKey>(attestations.values()); result.add(owner.toPublicJWK()); result.add(transport.toPublicJWK()); return List.copyOf(result);
    }
}
