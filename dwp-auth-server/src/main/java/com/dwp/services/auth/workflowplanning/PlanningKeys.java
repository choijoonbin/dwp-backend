package com.dwp.services.auth.workflowplanning;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Configuration only; three independent purposes cannot share key material or key identifiers. */
public final class PlanningKeys {
    private final Map<String, RSAKey> owners;
    private final Map<String, RSAKey> transports;
    private final RSAKey signer;
    public PlanningKeys(PlanningJson json, String owner, String transport, String privateAttestation,
            String publicAttestation, List<String> forbidden) {
        try {
            owners = publicKeys(json, owner); transports = publicKeys(json, transport);
            var privateJson = json.parse(privateAttestation.getBytes(java.nio.charset.StandardCharsets.UTF_8), 65536);
            if (!privateJson.isObject()) throw PlanningProtocol.unavailable();
            signer = RSAKey.parse(privateAttestation);
            if (!signer.isPrivate() || signer.size() < 2048 || signer.getKeyID() == null
                    || signer.getKeyUse() != null && !com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(signer.getKeyUse())
                    || signer.getAlgorithm() != null && !"RS256".equals(signer.getAlgorithm().getName())) throw PlanningProtocol.unavailable();
            var attestations = publicKeys(json, publicAttestation);
            var trusted = attestations.get(signer.getKeyID());
            if (attestations.size() != 1 || trusted == null || !material(trusted).equals(material(signer))) throw PlanningProtocol.unavailable();
            var materials = new HashSet<String>(); var identifiers = new HashSet<String>();
            for (var group : List.of(owners, transports, attestations)) for (var key : group.values()) {
                if (!materials.add(material(key)) || !identifiers.add(key.getKeyID())) throw PlanningProtocol.unavailable();
            }
            for (String raw : forbidden) {
                if (raw == null || raw.isBlank()) continue;
                if (raw.startsWith("kid:")) {
                    if (identifiers.contains(raw.substring(4))) throw PlanningProtocol.unavailable(); continue;
                }
                if (!raw.stripLeading().startsWith("{")) {
                    if (materials.contains(encodedMaterial(raw))) throw PlanningProtocol.unavailable(); continue;
                }
                for (var key : allKeys(raw)) if (materials.contains(material(key)) || identifiers.contains(key.getKeyID()))
                    throw PlanningProtocol.unavailable();
            }
        } catch (Exception error) { throw PlanningProtocol.unavailable(); }
    }
    public RSAKey owner(String kid) { return require(owners, kid); }
    public RSAKey transport(String kid) { return require(transports, kid); }
    public RSAKey signer() { return signer; }
    private RSAKey require(Map<String, RSAKey> keys, String kid) {
        RSAKey key = keys.get(kid); if (key == null) throw PlanningProtocol.denied(); return key;
    }
    private static Map<String, RSAKey> publicKeys(PlanningJson json, String raw) throws Exception {
        if (raw == null || raw.isBlank()) throw PlanningProtocol.unavailable();
        var parsed = json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8), 65536);
        PlanningJson.exact(parsed, java.util.Set.of("keys"));
        if (!parsed.get("keys").isArray() || parsed.get("keys").isEmpty() || parsed.get("keys").size() > 8) throw PlanningProtocol.unavailable();
        var result = new java.util.LinkedHashMap<String, RSAKey>();
        for (JWK item : JWKSet.parse(raw).getKeys()) {
            if (!(item instanceof RSAKey key) || key.isPrivate() || key.size() < 2048 || key.getKeyID() == null
                    || key.getKeyID().isBlank() || key.getKeyID().length() > 80 || result.put(key.getKeyID(), key) != null
                    || key.getAlgorithm() != null && !"RS256".equals(key.getAlgorithm().getName())
                    || key.getKeyUse() != null && !com.nimbusds.jose.jwk.KeyUse.SIGNATURE.equals(key.getKeyUse())) throw PlanningProtocol.unavailable();
        }
        return Map.copyOf(result);
    }
    private static List<RSAKey> allKeys(String raw) throws Exception {
        var keys = raw.contains("\"keys\"") ? JWKSet.parse(raw).getKeys() : List.of(JWK.parse(raw));
        var result = new java.util.ArrayList<RSAKey>();
        for (JWK key : keys) if (key instanceof RSAKey rsa) result.add(rsa); else throw PlanningProtocol.unavailable();
        return result;
    }
    private static String material(RSAKey key) throws Exception { return PlanningJson.sha(key.toRSAPublicKey().getEncoded()); }
    private static String encodedMaterial(String raw) throws Exception {
        if (raw.length() > 65536) throw PlanningProtocol.unavailable();
        String body = raw.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
        byte[] der = java.util.Base64.getDecoder().decode(body); var factory = java.security.KeyFactory.getInstance("RSA");
        try { return PlanningJson.sha(factory.generatePublic(new java.security.spec.X509EncodedKeySpec(der)).getEncoded()); }
        catch (java.security.spec.InvalidKeySpecException notPublic) {
            var key = factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
            if (!(key instanceof java.security.interfaces.RSAPrivateCrtKey rsa)) throw PlanningProtocol.unavailable();
            return PlanningJson.sha(factory.generatePublic(new java.security.spec.RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent())).getEncoded());
        }
    }
}
