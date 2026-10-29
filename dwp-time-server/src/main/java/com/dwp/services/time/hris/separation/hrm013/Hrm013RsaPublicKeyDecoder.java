package com.dwp.services.time.hris.separation.hrm013;

import com.dwp.platform.contracts.hris.separation.v1.HrisServiceGrantAuthorityV1;
import com.dwp.platform.contracts.hris.separation.v1.Hrm013SafeErrorV1;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Decodes a governed HRM-013 authority JWK without depending on an HTTP adapter. */
final class Hrm013RsaPublicKeyDecoder {
    private Hrm013RsaPublicKeyDecoder() {}

    static PublicKey decode(HrisServiceGrantAuthorityV1.RsaPublicJwk value) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    new BigInteger(1, Base64.getUrlDecoder().decode(value.n())),
                    new BigInteger(1, Base64.getUrlDecoder().decode(value.e()))));
        } catch (Exception failure) {
            throw Hrm013SafeErrorV1.authorityDenied();
        }
    }
}
