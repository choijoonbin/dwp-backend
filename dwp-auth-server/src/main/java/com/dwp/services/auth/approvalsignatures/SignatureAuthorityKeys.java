package com.dwp.services.auth.approvalsignatures;

import static com.dwp.services.auth.approvalsignatures.SignatureAuthorityJson.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Dedicated owner, transport and authority key families, never an MFA or artifact-signing key. */
public final class SignatureAuthorityKeys {
    private final Map<String, RSAKey> owners, transports;
    private final RSAKey signer;
    public SignatureAuthorityKeys(SignatureAuthorityJson json, String ownerJwks, String transportJwks,
            String privateJwk, String publicJwks, List<String> forbidden, boolean inventoryComplete) {
        try {
            if (!inventoryComplete || forbidden == null || forbidden.isEmpty()) throw unavailable();
            owners = publicKeys(json, ownerJwks, "approval-signature-owner:");
            transports = publicKeys(json, transportJwks, "approval-signature-transport:");
            json.parse(privateJwk.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            signer = RSAKey.parse(privateJwk);
            if (!signer.isPrivate()) throw unavailable();
            check(signer, "approval-signature-authority:");
            var authority = publicKeys(json, publicJwks, "approval-signature-authority:");
            var trusted = authority.get(signer.getKeyID());
            if (authority.size() != 1 || trusted == null || !material(trusted).equals(material(signer))) throw unavailable();
            var ids = new HashSet<String>(); var materials = new HashSet<String>();
            for (var group : List.of(owners, transports, authority)) for (var key : group.values())
                if (!ids.add(key.getKeyID()) || !materials.add(material(key))) throw unavailable();
            for (String raw : forbidden) {
                if (raw == null || raw.isBlank()) continue;
                for (JWK key : keys(raw))
                    if (ids.contains(key.getKeyID()) || materials.contains(material(key))) throw unavailable();
            }
        } catch (Exception invalid) { throw unavailable(); }
    }
    public RSAKey owner(String id) { return require(owners, id); }
    public RSAKey transport(String id) { return require(transports, id); }
    public RSAKey signer() { return signer; }
    private static RSAKey require(Map<String, RSAKey> keys, String id) {
        var key = keys.get(id); if (key == null) throw denied(); return key;
    }
    private static Map<String, RSAKey> publicKeys(SignatureAuthorityJson json, String raw, String prefix) throws Exception {
        SignatureAuthorityJson.keys(json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)), java.util.Set.of("keys"));
        var parsed = JWKSet.parse(raw).getKeys();
        if (parsed.isEmpty() || parsed.size() > 8) throw unavailable();
        var result = new java.util.LinkedHashMap<String, RSAKey>();
        for (var item : parsed) {
            if (!(item instanceof RSAKey key) || key.isPrivate()) throw unavailable(); check(key, prefix);
            if (result.put(key.getKeyID(), key) != null) throw unavailable();
        }
        return Map.copyOf(result);
    }
    private static void check(RSAKey key, String prefix) {
        if (key.size() < 2048 || key.getKeyID() == null || !key.getKeyID().startsWith(prefix) || key.getKeyID().length() > 100
                || !KeyUse.SIGNATURE.equals(key.getKeyUse()) || !JWSAlgorithm.RS256.equals(key.getAlgorithm())) throw unavailable();
    }
    private static List<JWK> keys(String raw) throws Exception {
        return raw.stripLeading().startsWith("{") ? raw.contains("\"keys\"") ? JWKSet.parse(raw).getKeys() : List.of(JWK.parse(raw))
                : List.of(SignatureAuthorityRsaKeyReader.read(raw));
    }
    private static String material(JWK key) throws Exception { return key.toPublicJWK().computeThumbprint().toString(); }
}
