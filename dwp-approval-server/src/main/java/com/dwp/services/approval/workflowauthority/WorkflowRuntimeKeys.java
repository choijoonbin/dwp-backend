package com.dwp.services.approval.workflowauthority;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public final class WorkflowRuntimeKeys {
    final RSAKey owner, transport;
    final Map<String,RSAKey> attestations;
    public WorkflowRuntimeKeys(RSAKey owner, RSAKey transport, String authJwks, String... prohibitedJwks) {
        try {
            validate(owner,true); validate(transport,true);
            var thumbprints = new HashSet<String>(); var ids = new HashSet<String>();
            for (RSAKey key : new RSAKey[] {owner,transport}) {
                if (!thumbprints.add(key.computeThumbprint().toString()) || !ids.add(key.getKeyID())) throw unavailable();
            }
            for (String raw : prohibitedJwks) if (raw != null && !raw.isBlank()) {
                if (raw.length() > 65536) throw unavailable();
                for (var key : JWKSet.parse(raw).getKeys()) {
                    if (thumbprints.contains(key.computeThumbprint().toString()) || ids.contains(key.getKeyID())) throw unavailable();
                    thumbprints.add(key.computeThumbprint().toString()); ids.add(key.getKeyID());
                }
            }
            if (authJwks == null || authJwks.isBlank() || authJwks.length() > 65536) throw unavailable();
            var json = new WorkflowRuntimeJson(); WorkflowRuntimeJson.exact(json.parse(authJwks.getBytes(java.nio.charset.StandardCharsets.UTF_8),65536), java.util.Set.of("keys"));
            var keys = new HashMap<String,RSAKey>();
            for (var candidate : JWKSet.parse(authJwks).getKeys()) {
                if (!(candidate instanceof RSAKey key)) throw unavailable(); validate(key,false);
                if (!thumbprints.add(key.computeThumbprint().toString()) || !ids.add(key.getKeyID()) || keys.putIfAbsent(key.getKeyID(),key)!=null) throw unavailable();
            }
            if (keys.isEmpty() || keys.size()>8) throw unavailable();
            this.owner=owner; this.transport=transport; this.attestations=Map.copyOf(keys);
        } catch (com.dwp.core.exception.BaseException error) { throw error; }
        catch (Exception error) { throw unavailable(); }
    }
    static RSAKey privateKey(String keyId, String encoded) {
        if (encoded == null || encoded.isBlank() || encoded.length()>65536) throw unavailable();
        try {
            String body = encoded.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            var raw = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            if (!(raw instanceof RSAPrivateCrtKey key)) throw unavailable();
            return new RSAKey.Builder(com.nimbusds.jose.util.Base64URL.encode(key.getModulus()),com.nimbusds.jose.util.Base64URL.encode(key.getPublicExponent()))
                    .privateKey(key).keyID(keyId).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        } catch (Exception error) { throw unavailable(); }
    }
    private static void validate(RSAKey key, boolean privateRequired) {
        if (key==null || key.isPrivate()!=privateRequired || key.size()<2048 || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || key.getKeyID()==null || !key.getKeyID().matches("[A-Za-z0-9._-]{1,80}")) throw unavailable();
    }
}
