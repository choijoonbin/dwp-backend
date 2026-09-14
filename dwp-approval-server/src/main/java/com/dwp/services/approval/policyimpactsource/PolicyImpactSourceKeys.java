package com.dwp.services.approval.policyimpactsource;

import static com.dwp.services.approval.policyimpactsource.PolicyImpactSourceJson.*;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Map;

public final class PolicyImpactSourceKeys {
    private final RSAKey owner, transport;
    private final Map<String, RSAKey> attestations;
    public PolicyImpactSourceKeys(PolicyImpactSourceJson json, String ownerPrivate, String transportPrivate,
            String attestationPublic, List<String> forbidden) {
        try {
            owner = privateKey(json, ownerPrivate); transport = privateKey(json, transportPrivate);
            keys(json.parse(attestationPublic.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.util.Set.of("keys"));
            var parsed = JWKSet.parse(attestationPublic).getKeys();
            if (parsed.isEmpty() || parsed.size() > 8) throw unavailable();
            var attests = new java.util.HashMap<String, RSAKey>();
            for (JWK item : parsed) {
                RSAKey key = rsa(item, false); if (attests.put(key.getKeyID(), key) != null) throw unavailable();
            }
            attestations = Map.copyOf(attests);
            var material = new java.util.HashSet<String>(); var kids = new java.util.HashSet<String>();
            var all = new java.util.ArrayList<>(attestations.values()); all.add(owner); all.add(transport);
            for (var key : all) if (!material.add(material(key)) || !kids.add(key.getKeyID())) throw unavailable();
            for (String raw : forbidden) {
                if (raw == null || raw.isBlank()) continue;
                if (raw.startsWith("kid:")) {
                    if (kids.contains(raw.substring(4))) throw unavailable(); continue;
                }
                if (!raw.stripLeading().startsWith("{")) {
                    if (material.contains(encodedMaterial(raw))) throw unavailable(); continue;
                }
                var other = raw.contains("\"keys\"") ? JWKSet.parse(raw).getKeys() : List.of(JWK.parse(raw));
                for (JWK item : other) if (!(item instanceof RSAKey key) || material.contains(material(key)) || kids.contains(key.getKeyID())) throw unavailable();
            }
        } catch (Exception invalid) { throw unavailable(); }
    }
    public RSAKey owner() { return owner; }
    public RSAKey transport() { return transport; }
    public RSAKey attestation(String kid) {
        var key = attestations.get(kid); if (key == null) throw denied(); return key;
    }
    private static RSAKey privateKey(PolicyImpactSourceJson json, String raw) throws Exception {
        if (raw == null || raw.isBlank() || !json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)).isObject()) throw unavailable();
        return rsa(JWK.parse(raw), true);
    }
    private static RSAKey rsa(JWK item, boolean requirePrivate) {
        if (!(item instanceof RSAKey key) || key.isPrivate() != requirePrivate || key.size() < 2048
                || key.getKeyID() == null || key.getKeyID().isBlank() || key.getKeyID().length() > 80
                || key.getAlgorithm() != null && !"RS256".equals(key.getAlgorithm().getName())
                || key.getKeyUse() != null && !KeyUse.SIGNATURE.equals(key.getKeyUse())) throw unavailable();
        return key;
    }
    private static String material(RSAKey key) throws Exception { return sha(key.toRSAPublicKey().getEncoded()); }
    private static String encodedMaterial(String raw) throws Exception {
        if (raw.length() > 65536) throw unavailable();
        String body = raw.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(body); var factory = java.security.KeyFactory.getInstance("RSA");
        try { return sha(factory.generatePublic(new java.security.spec.X509EncodedKeySpec(der)).getEncoded()); }
        catch (java.security.spec.InvalidKeySpecException notPublic) {
            var key = factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
            if (!(key instanceof java.security.interfaces.RSAPrivateCrtKey rsa)) throw unavailable();
            return sha(factory.generatePublic(new java.security.spec.RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent())).getEncoded());
        }
    }
}
