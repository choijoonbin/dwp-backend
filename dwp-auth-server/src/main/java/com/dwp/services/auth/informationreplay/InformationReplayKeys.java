package com.dwp.services.auth.informationreplay;

import static com.dwp.services.auth.informationreplay.InformationReplayJson.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class InformationReplayKeys {
    private Map<String, RSAKey> owners = Map.of(), transports = Map.of(), attestations = Map.of();
    private RSAKey signer;
    public InformationReplayKeys(InformationReplayJson json, String ownerKeys, String transportKeys,
            String privateKey, String attestationKeys, String... prohibited) {
        try {
            owners = publicKeys(json, ownerKeys); transports = publicKeys(json, transportKeys);
            if (privateKey != null && !privateKey.isBlank()) {
                json.parse(privateKey.getBytes(java.nio.charset.StandardCharsets.UTF_8), 16384);
                signer = RSAKey.parse(privateKey);
                if (!valid(signer) || !signer.isPrivate()) throw denied();
            }
            attestations = attestationKeys == null || attestationKeys.isBlank()
                    ? signer == null ? Map.of() : Map.of(signer.getKeyID(), signer.toPublicJWK()) : publicKeys(json, attestationKeys);
            if (signer != null && (!attestations.containsKey(signer.getKeyID())
                    || !attestations.get(signer.getKeyID()).computeThumbprint().equals(signer.computeThumbprint()))) throw denied();
            var ids = new HashSet<String>(); var thumbs = new HashSet<String>();
            for (var group : List.of(owners, transports, attestations)) for (RSAKey key : group.values()) {
                if (!ids.add(key.getKeyID()) || !thumbs.add(key.computeThumbprint().toString())) throw denied();
            }
            for (String raw : prohibited) if (raw != null && !raw.isBlank()) {
                List<JWK> forbidden;
                if (raw.strip().startsWith("-----BEGIN PRIVATE KEY-----")) {
                    byte[] der = java.util.Base64.getDecoder().decode(raw.replace("-----BEGIN PRIVATE KEY-----", "")
                            .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
                    var key = (java.security.interfaces.RSAPrivateCrtKey) java.security.KeyFactory.getInstance("RSA")
                            .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
                    forbidden = List.of(new RSAKey.Builder(com.nimbusds.jose.util.Base64URL.encode(key.getModulus()),
                            com.nimbusds.jose.util.Base64URL.encode(key.getPublicExponent())).build());
                } else {
                    var tree = json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8), 65536);
                    forbidden = tree.has("keys") ? JWKSet.parse(raw).getKeys() : List.of(JWK.parse(raw));
                }
                for (var key : forbidden) if (ids.contains(key.getKeyID()) || thumbs.contains(key.computeThumbprint().toString())) throw denied();
            }
        } catch (Exception invalid) { owners = Map.of(); transports = Map.of(); attestations = Map.of(); signer = null; }
    }
    private static Map<String, RSAKey> publicKeys(InformationReplayJson json, String raw) throws Exception {
        if (raw == null || raw.isBlank()) return Map.of();
        exact(json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8), 65536), Set.of("keys"));
        var result = new HashMap<String, RSAKey>(); var thumbs = new HashSet<String>();
        for (var value : JWKSet.parse(raw).getKeys()) {
            if (!(value instanceof RSAKey key) || !valid(key) || key.isPrivate()
                    || !thumbs.add(key.computeThumbprint().toString()) || result.putIfAbsent(key.getKeyID(), key) != null) throw denied();
        }
        if (result.isEmpty() || result.size() > 8) throw denied();
        return Map.copyOf(result);
    }
    private static boolean valid(RSAKey key) {
        return key.size() >= 2048 && KeyUse.SIGNATURE.equals(key.getKeyUse()) && JWSAlgorithm.RS256.equals(key.getAlgorithm())
                && key.getKeyID() != null && key.getKeyID().matches("[A-Za-z0-9._-]{1,80}");
    }
    public RSAKey owner(String kid) { if (owners.isEmpty()) throw unavailable(); var key = owners.get(kid); if (key == null) throw denied(); return key; }
    public RSAKey transport(String kid) { if (transports.isEmpty()) throw unavailable(); var key = transports.get(kid); if (key == null) throw denied(); return key; }
    public RSAKey signer() { if (signer == null) throw unavailable(); return signer; }
}
