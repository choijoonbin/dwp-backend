package com.dwp.services.people.security;

import com.dwp.core.security.Hrm012OwnerReceiptAuthorityContract;
import com.dwp.core.security.Hrm012OwnerReceiptAuthorityVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public final class Hrm012OwnerReceiptAuthorityEvidenceVerifier {

    private final Hrm012OwnerReceiptAuthorityVerifier verifier;

    @Autowired
    public Hrm012OwnerReceiptAuthorityEvidenceVerifier(
            ObjectMapper mapper,
            @Value("${dwp.people.hrm012-owner-receipt.current-key-id:}") String keyId,
            @Value("${dwp.people.hrm012-owner-receipt.current-public-key-pem:}") String publicKey) {
        this.verifier = new Hrm012OwnerReceiptAuthorityVerifier(
                mapper, Clock.systemUTC(), keyId,
                Hrm012OwnerReceiptAuthorityVerifier.parsePublicKey(publicKey));
    }

    Hrm012OwnerReceiptAuthorityEvidenceVerifier(
            Hrm012OwnerReceiptAuthorityVerifier verifier) {
        this.verifier = verifier;
    }

    Hrm012OwnerReceiptAuthorityVerifier.Evidence verify(
            HttpServletRequest request, String marker) {
        PeopleRequestContext.Actor identity = PeopleRequestContext.require();
        String correlation = exact(request, "X-Correlation-ID");
        String idempotency = exact(request, "Idempotency-Key");
        if (correlation == null || idempotency == null) {
            throw new com.dwp.core.exception.BaseException(
                    com.dwp.core.common.ErrorCode.FORBIDDEN,
                    "HRM-012 trusted request binding is missing.");
        }
        Set<String> acceptedApps = new LinkedHashSet<>();
        Set<String> permissions = identity.permissions();
        if (permissions.contains("APP.HCM:VIEW")) acceptedApps.add("APP.HCM:VIEW");
        if (permissions.contains("APP.HRIS:VIEW")) acceptedApps.add("APP.HRIS:VIEW");
        return verifier.verify(marker, new Hrm012OwnerReceiptAuthorityVerifier.Expected(
                identity.tenantId(), Long.toString(identity.userId()), correlation,
                Hrm012OwnerReceiptAuthorityContract.sha256(idempotency), acceptedApps));
    }

    private String exact(HttpServletRequest request, String name) {
        var values = java.util.Collections.list(request.getHeaders(name));
        if (values.size() != 1 || values.getFirst() == null) return null;
        String value = values.getFirst();
        return value.isBlank() || !value.equals(value.strip()) || value.indexOf(',') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 ? null : value;
    }
}
