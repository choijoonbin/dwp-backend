package com.dwp.services.approval.informationreplay;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Dedicated key groups are disjoint from each other and the deployment's registered forbidden key catalogue. */
public final class InformationReplayKeys {
    final RSAKey owner,transport;
    final Map<String,RSAKey> attestations;
    public InformationReplayKeys(RSAKey owner,RSAKey transport,String trustedAuth,String prohibitedCatalogue) {
        this(owner,transport,trustedAuth,prohibitedCatalogue,new String[0]);
    }
    public InformationReplayKeys(RSAKey owner,RSAKey transport,String trustedAuth,String prohibitedCatalogue,String... registeredKeys) {
        try {
            validate(owner,true);validate(transport,true);var ids=new HashSet<String>();var prints=new HashSet<String>();
            for(var key:new RSAKey[]{owner,transport}) if(!ids.add(key.getKeyID()) || !prints.add(key.computeThumbprint().toString())) throw unavailable();
            if(prohibitedCatalogue==null || prohibitedCatalogue.isBlank() || prohibitedCatalogue.length()>65536) throw unavailable();
            for(var key:catalogue(prohibitedCatalogue)) {
                if(ids.contains(key.getKeyID()) || prints.contains(key.computeThumbprint().toString())) throw unavailable();
                ids.add(key.getKeyID());prints.add(key.computeThumbprint().toString());
            }
            for(String raw:registeredKeys) {
                if(raw==null || raw.isBlank()) continue;
                if(raw.startsWith("kid:")) {String id=raw.substring(4);if(id.equals(owner.getKeyID()) || id.equals(transport.getKeyID())) throw unavailable();ids.add(id);continue;}
                for(var key:catalogue(raw)) {
                    if(key.getKeyID()!=null && (key.getKeyID().equals(owner.getKeyID()) || key.getKeyID().equals(transport.getKeyID()))
                            || key.computeThumbprint().equals(owner.computeThumbprint()) || key.computeThumbprint().equals(transport.computeThumbprint())) throw unavailable();
                    if(key.getKeyID()!=null) ids.add(key.getKeyID());prints.add(key.computeThumbprint().toString());
                }
            }
            if(trustedAuth==null || trustedAuth.isBlank() || trustedAuth.length()>65536) throw unavailable();var current=new HashMap<String,RSAKey>();
            for(var candidate:catalogue(trustedAuth)) {
                if(!(candidate instanceof RSAKey key)) throw unavailable();validate(key,false);
                if(!ids.add(key.getKeyID()) || !prints.add(key.computeThumbprint().toString()) || current.putIfAbsent(key.getKeyID(),key)!=null) throw unavailable();
            }
            if(current.isEmpty() || current.size()>8) throw unavailable();this.owner=owner;this.transport=transport;this.attestations=Map.copyOf(current);
        } catch(com.dwp.core.exception.BaseException error) {throw error;} catch(Exception error) {throw unavailable();}
    }
    static RSAKey privateKey(String raw) {
        if(raw==null || raw.isBlank() || raw.length()>65536) throw unavailable();
        try {strict(raw);return RSAKey.parse(raw);} catch(Exception invalid) {throw unavailable();}
    }
    private static java.util.List<JWK> catalogue(String raw) throws Exception {
        if(raw==null || raw.isBlank() || raw.length()>65536) throw unavailable();
        if(raw.startsWith("-----BEGIN ")) return java.util.List.of(JWK.parseFromPEMEncodedObjects(raw));
        var node=strict(raw);return node.has("keys")?JWKSet.parse(raw).getKeys():java.util.List.of(JWK.parse(raw));
    }
    private static com.fasterxml.jackson.databind.JsonNode strict(String raw) {
        var json=new com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson();
        var node=json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),65536);if(!node.isObject()) throw unavailable();json.bytes(node);return node;
    }
    private static void validate(RSAKey key,boolean secret) {
        if(key==null || key.isPrivate()!=secret || key.size()<2048 || !KeyUse.SIGNATURE.equals(key.getKeyUse())
                || !JWSAlgorithm.RS256.equals(key.getAlgorithm()) || key.getKeyID()==null || !key.getKeyID().matches("[A-Za-z0-9._-]{1,80}")) throw unavailable();
    }
}
