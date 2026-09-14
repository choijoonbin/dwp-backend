package com.dwp.services.auth.systemslaauthority;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.List;
import java.util.Map;

public final class SystemSlaKeys {
    private final Map<String, RSAKey> owners, transports;
    private final RSAKey signer;
    public SystemSlaKeys(SystemSlaJson json, String owners, String transports, String signer, String attestations, List<String> forbidden) {
        try {
            this.owners = publicKeys(json, owners); this.transports = publicKeys(json, transports);
            json.parse(signer.getBytes(java.nio.charset.StandardCharsets.UTF_8)); this.signer = RSAKey.parse(signer);
            if (!this.signer.isPrivate()) throw SystemSlaJson.unavailable(); validate(this.signer);
            var trusted = publicKeys(json, attestations);
            if (trusted.size() != 1 || !material(this.signer).equals(material(trusted.get(this.signer.getKeyID())))) throw SystemSlaJson.unavailable();
            var materials = new java.util.HashSet<String>(); var identifiers = new java.util.HashSet<String>();
            for (var group : List.of(this.owners, this.transports, trusted)) for (var key : group.values()) {
                if (!materials.add(material(key)) || !identifiers.add(key.getKeyID())) throw SystemSlaJson.unavailable();
            }
            for (String raw : forbidden) {
                if (raw == null || raw.isBlank()) continue;
                if (raw.startsWith("kid:")) { if (identifiers.contains(raw.substring(4))) throw SystemSlaJson.unavailable(); continue; }
                if (!raw.stripLeading().startsWith("{")) {
                    if (materials.contains(pemMaterial(raw))) throw SystemSlaJson.unavailable(); continue;
                }
                json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (JWK key : raw.contains("\"keys\"") ? JWKSet.parse(raw).getKeys() : List.of(JWK.parse(raw))) {
                    if (!(key instanceof RSAKey rsa) || materials.contains(material(rsa)) || identifiers.contains(rsa.getKeyID())) throw SystemSlaJson.unavailable();
                }
            }
        } catch (Exception invalid) { throw SystemSlaJson.unavailable(); }
    }
    public RSAKey owner(String id) { return required(owners, id); }
    public RSAKey transport(String id) { return required(transports, id); }
    public RSAKey signer() { return signer; }
    private static RSAKey required(Map<String, RSAKey> keys, String id) { var key = keys.get(id); if (key == null) throw SystemSlaJson.denied(); return key; }
    private static Map<String, RSAKey> publicKeys(SystemSlaJson json, String raw) throws Exception {
        if (raw == null || raw.isBlank()) throw SystemSlaJson.unavailable();
        var tree = json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)); SystemSlaJson.keys(tree, java.util.Set.of("keys"));
        if (!tree.get("keys").isArray() || tree.get("keys").isEmpty() || tree.get("keys").size() > 8) throw SystemSlaJson.unavailable();
        var result = new java.util.HashMap<String, RSAKey>();
        for (JWK item : JWKSet.parse(raw).getKeys()) {
            if (!(item instanceof RSAKey key) || key.isPrivate()) throw SystemSlaJson.unavailable(); validate(key);
            if (result.put(key.getKeyID(), key) != null) throw SystemSlaJson.unavailable();
        }
        return Map.copyOf(result);
    }
    private static void validate(RSAKey key) {
        if (key.size() < 2048 || key.getKeyID() == null || !key.getKeyID().matches("[a-zA-Z0-9_-]{1,80}")
                || key.getAlgorithm() != null && !"RS256".equals(key.getAlgorithm().getName())
                || key.getKeyUse() != null && !com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(key.getKeyUse())) throw SystemSlaJson.unavailable();
    }
    private static String material(RSAKey key) throws Exception { if (key == null) throw SystemSlaJson.unavailable(); return SystemSlaJson.sha(key.toRSAPublicKey().getEncoded()); }
    private static String pemMaterial(String raw) throws Exception {
        if (raw.length() > 65536) throw SystemSlaJson.unavailable();
        String body = raw.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] encoded = java.util.Base64.getDecoder().decode(body); var factory = java.security.KeyFactory.getInstance("RSA");
        try { return SystemSlaJson.sha(factory.generatePublic(new java.security.spec.X509EncodedKeySpec(encoded)).getEncoded()); }
        catch (java.security.spec.InvalidKeySpecException privateKey) {
            var rsa = (java.security.interfaces.RSAPrivateCrtKey) factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(encoded));
            return SystemSlaJson.sha(factory.generatePublic(new java.security.spec.RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent())).getEncoded());
        }
    }
}
