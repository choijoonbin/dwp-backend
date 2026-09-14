package com.dwp.services.approval.systemslaauthority;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import java.util.*;

/** The notification transport and Approval response key families are mutually and externally disjoint. */
public final class SystemSlaNotificationKeys {
    private final Map<String, RSAKey> transport;
    private final RSAKey signer;
    public SystemSlaNotificationKeys(SystemSlaJson json, String transportJwks, String privateJwk, String publicJwks, List<RSAKey> prohibited) {
        if (json == null || prohibited == null || prohibited.isEmpty()) throw SystemSlaJson.unavailable();
        try {
            var ids = new HashSet<String>(); var prints = new HashSet<String>();
            for (var key : prohibited) { if (key == null) throw SystemSlaJson.unavailable(); if (key.getKeyID() != null) ids.add(key.getKeyID()); prints.add(key.computeThumbprint().toString()); }
            transport = read(json, transportJwks, "notification-sla-recipient-transport:", ids, prints);
            json.parse(privateJwk.getBytes(java.nio.charset.StandardCharsets.UTF_8)); signer = RSAKey.parse(privateJwk);
            if (!signer.isPrivate() || !valid(signer, "approval-sla-recipient-authority:")) throw SystemSlaJson.unavailable();
            var publicKeys = read(json, publicJwks, "approval-sla-recipient-authority:", ids, prints);
            if (publicKeys.size() != 1 || !signer.toPublicJWK().equals(publicKeys.get(signer.getKeyID()))) throw SystemSlaJson.unavailable();
        } catch (Exception invalid) { throw SystemSlaJson.unavailable(); }
    }
    private static Map<String, RSAKey> read(SystemSlaJson json, String raw, String prefix, Set<String> ids, Set<String> prints) throws Exception {
        json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)); var keys = JWKSet.parse(raw).getKeys();
        if (keys.isEmpty() || keys.size() > 8) throw SystemSlaJson.unavailable();
        var output = new HashMap<String, RSAKey>();
        for (var key : keys) {
            if (!(key instanceof RSAKey rsa) || rsa.isPrivate() || !valid(rsa, prefix) || !ids.add(rsa.getKeyID())
                    || !prints.add(rsa.computeThumbprint().toString()) || output.put(rsa.getKeyID(), rsa) != null) throw SystemSlaJson.unavailable();
        }
        return Map.copyOf(output);
    }
    private static boolean valid(RSAKey key, String prefix) {
        return key.size() >= 2048 && JWSAlgorithm.RS256.equals(key.getAlgorithm()) && KeyUse.SIGNATURE.equals(key.getKeyUse())
                && key.getKeyID() != null && key.getKeyID().matches(java.util.regex.Pattern.quote(prefix) + "[A-Za-z0-9._-]{1,48}");
    }
    public RSAKey transport(String kid) { var key = transport.get(kid); if (key == null) throw SystemSlaJson.denied(); return key; }
    RSAKey signer() { return signer; }
}
