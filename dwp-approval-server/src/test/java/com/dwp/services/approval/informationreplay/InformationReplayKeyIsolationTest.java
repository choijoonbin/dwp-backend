package com.dwp.services.approval.informationreplay;

import static org.junit.jupiter.api.Assertions.*;

import com.dwp.core.exception.BaseException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InformationReplayKeyIsolationTest {
    static RSAKey owner,transport,auth;
    @BeforeAll static void keys() throws Exception {owner=key("replay-owner");transport=key("replay-transport");auth=key("replay-auth");}
    static RSAKey key(String id) throws Exception {
        return new RSAKeyGenerator(2048).keyID(id).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).generate();
    }
    String trust() {return new JWKSet(auth.toPublicJWK()).toString();}
    String empty() {return new JWKSet().toString();}
    @Test void validDisjointGroupsAreAcceptedWithoutExposingPrivateAttestationKeys() {
        var keys=new InformationReplayKeys(owner,transport,trust(),empty());assertFalse(keys.attestations.get(auth.getKeyID()).isPrivate());
    }
    @Test void ownerAndTransportCannotReuseTheSameKeyUnderAnotherId() {
        var alias=new RSAKey.Builder(owner).keyID("different-transport-id").build();
        assertThrows(BaseException.class,()->new InformationReplayKeys(owner,alias,trust(),empty()));
    }
    @Test void authAttestationCannotReuseOwnerOrTransportKeys() {
        var alias=new RSAKey.Builder(owner.toPublicJWK()).keyID("different-auth-id").build();
        assertThrows(BaseException.class,()->new InformationReplayKeys(owner,transport,new JWKSet(alias).toString(),empty()));
    }
    @Test void registeredOtherPurposeFingerprintsDenyAliasesDespiteDifferentKeyIds() {
        var alias=new RSAKey.Builder(owner.toPublicJWK()).keyID("old-purpose-owner").build();
        assertThrows(BaseException.class,()->new InformationReplayKeys(owner,transport,trust(),empty(),alias.toJSONString()));
        assertThrows(BaseException.class,()->new InformationReplayKeys(owner,transport,trust(),empty(),"kid:"+auth.getKeyID()));
    }
    @Test void authPrivateMaterialAndMissingCatalogueAreRejected() {
        assertThrows(BaseException.class,()->new InformationReplayKeys(owner,transport,new JWKSet(auth).toString(false),empty()));
        assertThrows(BaseException.class,()->new InformationReplayKeys(owner,transport,trust(),""));
    }
    @Test void duplicateAndTrailingKeyDocumentsAreRejectedBeforeCredentialInstantiation() {
        String raw=owner.toJSONString();
        assertThrows(BaseException.class,()->InformationReplayKeys.privateKey(raw+" {}"));
        assertThrows(BaseException.class,()->InformationReplayKeys.privateKey(raw.substring(0,raw.length()-1)+",\"kid\":\"duplicate\"}"));
    }
}
