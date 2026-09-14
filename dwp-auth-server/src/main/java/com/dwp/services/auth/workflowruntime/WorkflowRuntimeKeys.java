package com.dwp.services.auth.workflowruntime;

import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeJson.*;
import static com.dwp.services.auth.workflowruntime.WorkflowRuntimeProtocol.*;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class WorkflowRuntimeKeys {
    private final Map<String, RSAKey> owners;
    private final Map<String, RSAKey> transports;
    private final Map<String, RSAKey> attestations;
    private final RSAKey signer;
    @Autowired
    public WorkflowRuntimeKeys(WorkflowRuntimeJson json,
            @Value("${dwp.auth.approval-workflow-runtime.owner-trusted-keys:}") String owners,
            @Value("${dwp.auth.approval-workflow-runtime.transport-trusted-keys:}") String transports,
            @Value("${dwp.auth.approval-workflow-runtime.attestation-private-key:}") String signer,
            @Value("${dwp.auth.approval-workflow-runtime.attestation-trusted-keys:}") String attestations,
            @Value("${dwp.auth.approval-form-user-proof-jwks:}") String users,
            @Value("${dwp.auth.approval-workflow-role-proof-jwks:}") String roles,
            @Value("${dwp.auth.approval-workflow-role-transport-jwks:}") String roleTransports,
            @Value("${dwp.auth.approval-workflow-role-mapping-private-jwk:}") String roleMapping) {
        this(json, owners, transports, signer, attestations, new String[] {users, roles, roleTransports, roleMapping});
    }
    public WorkflowRuntimeKeys(WorkflowRuntimeJson json, String ownerKeys, String transportKeys,
            String privateKey, String attestationKeys, String... prohibited) {
        Map<String, RSAKey> parsedOwners = Map.of(), parsedTransports = Map.of(), parsedAttestations = Map.of(); RSAKey parsedSigner = null;
        try {
            parsedOwners = publicKeys(json, ownerKeys); parsedTransports = publicKeys(json, transportKeys);
            if (privateKey != null && !privateKey.isBlank()) json.read(privateKey);
            parsedSigner = privateKey == null || privateKey.isBlank() ? null : RSAKey.parse(privateKey);
            if (parsedSigner != null && (!valid(parsedSigner) || !parsedSigner.isPrivate() || privateKey.length() > 16384)) throw denied();
            parsedAttestations = attestationKeys == null || attestationKeys.isBlank()
                    ? parsedSigner == null ? Map.of() : Map.of(parsedSigner.getKeyID(), parsedSigner.toPublicJWK()) : publicKeys(json, attestationKeys);
            if (parsedSigner != null && (!parsedAttestations.containsKey(parsedSigner.getKeyID())
                    || !parsedAttestations.get(parsedSigner.getKeyID()).computeThumbprint().equals(parsedSigner.computeThumbprint()))) throw denied();
            var ids = new HashSet<String>(); var thumbs = new HashSet<String>();
            for (var group : java.util.List.of(parsedOwners, parsedTransports, parsedAttestations)) {
                for (RSAKey key : group.values()) if (!ids.add(key.getKeyID()) || !thumbs.add(key.computeThumbprint().toString())) throw denied();
            }
            for (String raw : prohibited) if (raw != null && !raw.isBlank()) {
                if (raw.length() > 65536) throw denied();
                var tree = json.read(raw);
                java.util.List<JWK> forbidden = tree.has("keys") ? JWKSet.parse(raw).getKeys() : java.util.List.of(JWK.parse(raw));
                for (var key : forbidden) if (ids.contains(key.getKeyID()) || thumbs.contains(key.computeThumbprint().toString())) throw denied();
            }
        } catch (Exception exception) { parsedOwners = Map.of(); parsedTransports = Map.of(); parsedAttestations = Map.of(); parsedSigner = null; }
        this.owners = parsedOwners; this.transports = parsedTransports; this.attestations = parsedAttestations; this.signer = parsedSigner;
    }
    private static Map<String, RSAKey> publicKeys(WorkflowRuntimeJson json, String raw) throws Exception {
        if (raw == null || raw.isBlank()) return Map.of();
        if (raw.length() > 65536) throw denied(); exact(json.read(raw), Set.of("keys"));
        var result = new HashMap<String, RSAKey>(); var thumbs = new HashSet<String>();
        for (var value : JWKSet.parse(raw).getKeys()) {
            if (!(value instanceof RSAKey key) || !valid(key) || key.isPrivate() || !thumbs.add(key.computeThumbprint().toString())
                    || result.putIfAbsent(key.getKeyID(), key) != null) throw denied();
        }
        if (result.isEmpty() || result.size() > 8) throw denied(); return Map.copyOf(result);
    }
    private static boolean valid(RSAKey key) {
        return key.size() >= 2048 && KeyUse.SIGNATURE.equals(key.getKeyUse()) && JWSAlgorithm.RS256.equals(key.getAlgorithm())
                && key.getKeyID() != null && key.getKeyID().matches("[A-Za-z0-9._-]{1,80}");
    }
    public Map<String, RSAKey> owners() { return owners; }
    public Map<String, RSAKey> transports() { return transports; }
    public Map<String, RSAKey> attestations() { return attestations; }
    public RSAKey signer() { if (signer == null) throw unavailable(); return signer; }
}
