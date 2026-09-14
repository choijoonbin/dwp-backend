package com.dwp.services.auth.retentionexecutionauthority;

import static com.dwp.services.auth.retentionexecutionauthority.RetentionExecutionProtocol.*;
import com.nimbusds.jose.jwk.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/** Two RSA trust rings and one separate Ed25519 execution key; no caller-selected issuer. */
public final class RetentionExecutionKeys {
    private final Map<String,RSAKey> owners,transports;
    private final PrivateKey execution;private final PublicKey publicExecution;private final String issuer,keyId;
    public RetentionExecutionKeys(RetentionExecutionJson json,String owner,String transport,String privateKey,String publicKey,
            String issuer,String keyId,List<String> forbidden) {
        try {
            owners=ring(json,owner);transports=ring(json,transport);
            if(issuer==null || !issuer.matches("[A-Za-z0-9._:-]{1,160}") || keyId==null || !keyId.matches("[A-Za-z0-9._:-]{1,80}")) throw unavailable();
            this.issuer=issuer;this.keyId=keyId;var factory=KeyFactory.getInstance("Ed25519");
            execution=factory.generatePrivate(new PKCS8EncodedKeySpec(der(privateKey)));
            publicExecution=factory.generatePublic(new X509EncodedKeySpec(der(publicKey)));
            var signer=Signature.getInstance("Ed25519");signer.initSign(execution);signer.update(PATH.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var verifier=Signature.getInstance("Ed25519");verifier.initVerify(publicExecution);verifier.update(PATH.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(!verifier.verify(signer.sign())) throw unavailable();
            var materials=new HashSet<String>();var identifiers=new HashSet<String>();identifiers.add(keyId);
            materials.add(RetentionExecutionJson.sha(publicExecution.getEncoded()));
            for(var group:List.of(owners,transports)) for(var key:group.values())
                if(!materials.add(material(key)) || !identifiers.add(key.getKeyID())) throw unavailable();
            for(String raw:forbidden) {
                if(raw==null || raw.isBlank()) continue;
                if(raw.startsWith("kid:")) {if(identifiers.contains(raw.substring(4))) throw unavailable();continue;}
                if(raw.stripLeading().startsWith("{")) {
                    var all=raw.contains("\"keys\"")?JWKSet.parse(raw).getKeys():List.of(JWK.parse(raw));
                    for(var key:all) {
                        if(identifiers.contains(key.getKeyID()) || key instanceof RSAKey rsa && materials.contains(material(rsa))) throw unavailable();
                        if(key instanceof OctetKeyPair octet && Curve.Ed25519.equals(octet.getCurve())) {
                            byte[] point=octet.getX().decode();if(point.length!=32) throw unavailable();
                            byte[] encoded=Arrays.copyOf(HexFormat.of().parseHex("302a300506032b6570032100"),44);System.arraycopy(point,0,encoded,12,32);
                            if(materials.contains(RetentionExecutionJson.sha(factory.generatePublic(new X509EncodedKeySpec(encoded)).getEncoded()))) throw unavailable();
                        }
                    }
                } else if(encodedShared(raw,materials)) throw unavailable();
            }
        } catch(Exception invalid) {throw unavailable();}
    }
    public RSAKey owner(String id) {var key=owners.get(id);if(key==null) throw denied();return key;}
    public RSAKey transport(String id) {var key=transports.get(id);if(key==null) throw denied();return key;}
    public PrivateKey execution() {return execution;}
    public PublicKey publicExecution() {return publicExecution;}
    public String issuer() {return issuer;}
    public String keyId() {return keyId;}
    private static Map<String,RSAKey> ring(RetentionExecutionJson json,String raw) throws Exception {
        if(raw==null || raw.isBlank()) throw unavailable();var parsed=json.parse(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8),65536);
        RetentionExecutionJson.exact(parsed,Set.of("keys"));if(!parsed.get("keys").isArray() || parsed.get("keys").isEmpty() || parsed.get("keys").size()>8) throw unavailable();
        var result=new LinkedHashMap<String,RSAKey>();var material=new HashSet<String>();
        for(var item:JWKSet.parse(raw).getKeys()) {
            if(!(item instanceof RSAKey key) || key.isPrivate() || key.size()<2048 || key.getKeyID()==null || !key.getKeyID().matches("[A-Za-z0-9._:-]{1,80}")
                    || key.getAlgorithm()!=null && !"RS256".equals(key.getAlgorithm().getName())
                    || key.getKeyUse()!=null && !KeyUse.SIGNATURE.equals(key.getKeyUse())
                    || result.put(key.getKeyID(),key)!=null || !material.add(material(key))) throw unavailable();
        }
        return Map.copyOf(result);
    }
    private static byte[] der(String raw) {
        if(raw==null || raw.isBlank() || raw.length()>16384) throw unavailable();
        return Base64.getDecoder().decode(raw.replace("-----BEGIN PRIVATE KEY-----","").replace("-----END PRIVATE KEY-----","")
                .replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s",""));
    }
    private static String material(RSAKey key) throws Exception {return RetentionExecutionJson.sha(key.toRSAPublicKey().getEncoded());}
    private boolean encodedShared(String raw,Set<String> materials) throws Exception {
        byte[] bytes=der(raw);
        for(String algorithm:List.of("RSA","Ed25519")) {
            var factory=KeyFactory.getInstance(algorithm);
            try {var key=factory.generatePublic(new X509EncodedKeySpec(bytes));return materials.contains(RetentionExecutionJson.sha(key.getEncoded()));}
            catch(InvalidKeySpecException notPublic) {
                try {
                    var key=factory.generatePrivate(new PKCS8EncodedKeySpec(bytes));
                    if(key instanceof java.security.interfaces.RSAPrivateCrtKey rsa)
                        return materials.contains(RetentionExecutionJson.sha(factory.generatePublic(new RSAPublicKeySpec(rsa.getModulus(),rsa.getPublicExponent())).getEncoded()));
                    if(key instanceof java.security.interfaces.EdECPrivateKey ed && execution instanceof java.security.interfaces.EdECPrivateKey own)
                        return MessageDigest.isEqual(ed.getBytes().orElseThrow(),own.getBytes().orElseThrow());
                } catch(InvalidKeySpecException notThisAlgorithm) { /* Other asymmetric purpose families cannot share RSA/Ed25519 material. */ }
            }
        }
        return false;
    }
}
