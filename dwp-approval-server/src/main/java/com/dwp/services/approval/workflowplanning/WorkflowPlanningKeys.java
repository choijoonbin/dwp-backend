package com.dwp.services.approval.workflowplanning;

import static com.dwp.services.approval.workflowauthority.WorkflowRuntimeProtocol.unavailable;

import com.dwp.services.approval.workflowauthority.WorkflowRuntimeJson;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public final class WorkflowPlanningKeys {
    final RSAKey owner,transport;
    final Map<String,RSAKey> attestations;
    public WorkflowPlanningKeys(RSAKey owner,RSAKey transport,String trusted,String prohibited,String... registered) {
        try {
            validate(owner,true);validate(transport,true);var ids=new HashSet<String>();var prints=new HashSet<String>();
            for(var key:List.of(owner,transport)) if(!ids.add(key.getKeyID()) || !prints.add(key.computeThumbprint().toString())) throw unavailable();
            if(prohibited==null || prohibited.isBlank()) throw unavailable();
            for(var key:catalogue(prohibited)) {if(ids.contains(key.getKeyID()) || prints.contains(key.computeThumbprint().toString())) throw unavailable();
                if(key.getKeyID()!=null) ids.add(key.getKeyID());prints.add(key.computeThumbprint().toString());}
            for(String raw:registered) {
                if(raw==null || raw.isBlank()) continue;
                if(raw.startsWith("kid:")) {String id=raw.substring(4);if(!ids.add(id)) throw unavailable();continue;}
                for(var key:catalogue(raw)) {
                    if(key.getKeyID()!=null && (key.getKeyID().equals(owner.getKeyID()) || key.getKeyID().equals(transport.getKeyID()))
                            || key.computeThumbprint().equals(owner.computeThumbprint()) || key.computeThumbprint().equals(transport.computeThumbprint())) throw unavailable();
                    if(key.getKeyID()!=null) ids.add(key.getKeyID());prints.add(key.computeThumbprint().toString());
                }
            }
            var current=new HashMap<String,RSAKey>();for(var candidate:catalogue(trusted)) {
                if(!(candidate instanceof RSAKey key)) throw unavailable();validate(key,false);
                if(!ids.add(key.getKeyID()) || !prints.add(key.computeThumbprint().toString()) || current.putIfAbsent(key.getKeyID(),key)!=null) throw unavailable();
            }
            if(current.isEmpty() || current.size()>8) throw unavailable();this.owner=owner;this.transport=transport;attestations=Map.copyOf(current);
        } catch(com.dwp.core.exception.BaseException error) {throw error;} catch(Exception error) {throw unavailable();}
    }
    static RSAKey privateKey(String raw) {
        try {strict(raw);return RSAKey.parse(raw);} catch(Exception invalid) {throw unavailable();}
    }
    private static List<JWK> catalogue(String raw) throws Exception {
        if(raw==null || raw.isBlank() || raw.length()>65536) throw unavailable();
        if(raw.startsWith("-----BEGIN ")) return List.of(JWK.parseFromPEMEncodedObjects(raw));
        var node=strict(raw);return node.has("keys")?JWKSet.parse(raw).getKeys():List.of(JWK.parse(raw));
    }
    private static com.fasterxml.jackson.databind.JsonNode strict(String raw) {
        if(raw==null || raw.isBlank()) throw unavailable();var json=new WorkflowRuntimeJson();var node=json.parse(raw.getBytes(StandardCharsets.UTF_8),65536);
        if(!node.isObject()) throw unavailable();json.bytes(node);return node;
    }
    private static void validate(RSAKey key,boolean secret) {
        if(key==null || key.isPrivate()!=secret || key.size()<2048 || !KeyUse.SIGNATURE.equals(key.getKeyUse()) || !JWSAlgorithm.RS256.equals(key.getAlgorithm())
                || key.getKeyID()==null || !key.getKeyID().matches("[A-Za-z0-9._-]{1,80}")) throw unavailable();
    }
}
