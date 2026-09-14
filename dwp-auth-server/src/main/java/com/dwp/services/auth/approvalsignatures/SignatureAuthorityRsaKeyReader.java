package com.dwp.services.auth.approvalsignatures;

import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.Base64;

/** JDK-only X.509/PKCS#8 reader for forbidden-key inventory and original MFA verification. */
public final class SignatureAuthorityRsaKeyReader {
    private SignatureAuthorityRsaKeyReader(){ }
    public static RSAKey read(String raw) throws Exception {
        if(raw==null || raw.isBlank() || raw.length()>65536)throw SignatureAuthorityJson.unavailable();
        String value=raw.strip();
        if(value.startsWith("-----BEGIN")) {
            var matcher=java.util.regex.Pattern.compile("\\A-----BEGIN (PUBLIC|PRIVATE) KEY-----\\s+([A-Za-z0-9+/=\\s]+)\\s+-----END \\1 KEY-----\\z").matcher(value);
            if(!matcher.matches())throw SignatureAuthorityJson.unavailable();
            return decode(Base64.getDecoder().decode(matcher.group(2).replaceAll("\\s","")),matcher.group(1).equals("PRIVATE"));
        }
        byte[] der=Base64.getDecoder().decode(value.replaceAll("\\s",""));
        try{return decode(der,false);}catch(java.security.GeneralSecurityException privateKey){return decode(der,true);}
    }
    private static RSAKey decode(byte[] der,boolean privateKey) throws Exception {
        var factory=KeyFactory.getInstance("RSA");
        if(!privateKey)return new RSAKey.Builder((RSAPublicKey)factory.generatePublic(new X509EncodedKeySpec(der))).build();
        var key=(RSAPrivateCrtKey)factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        var pub=(RSAPublicKey)factory.generatePublic(new RSAPublicKeySpec(key.getModulus(),key.getPublicExponent()));return new RSAKey.Builder(pub).privateKey(key).build();
    }
}
