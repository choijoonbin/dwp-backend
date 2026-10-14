package com.dwp.services.payroll.input.compensationplan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Issues the exact, short-lived JWT half of the PAY workload identity. */
final class CompensationPlanOwnerTokenIssuer {

    private final ObjectMapper mapper;
    private final RSAKey key;
    private final String issuer;

    CompensationPlanOwnerTokenIssuer(
            ObjectMapper mapper,
            RSAKey key,
            String issuer) {
        this.mapper = Objects.requireNonNull(mapper).copy();
        this.key = Objects.requireNonNull(key);
        if (issuer == null || issuer.isBlank() || !issuer.equals(issuer.strip())) {
            throw new IllegalArgumentException("issuer is required");
        }
        this.issuer = issuer;
    }

    String issue(
            CompensationPlanSnapshotOwnerPort.ExactSnapshotRequest request,
            CompensationPlanSnapshotOwnerHttpClient.OwnerGrant grant,
            String path,
            Instant now) {
        long issuedAt = now.getEpochSecond();
        LinkedHashMap<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", CompensationPlanSnapshotOwnerHttpClient.AUDIENCE);
        claims.put("sub", CompensationPlanSnapshotOwnerHttpClient.SUBJECT);
        claims.put("iat", issuedAt);
        claims.put("nbf", issuedAt);
        claims.put("exp", issuedAt + 30);
        claims.put("jti", request.correlationId().toString());
        claims.put("scope", CompensationPlanSnapshotOwnerHttpClient.SCOPE);
        claims.put("tenantId", request.tenantId());
        claims.put("tenantGrantRevision", grant.tenantGrantRevision());
        claims.put("policyRevision", grant.policyRevision());
        claims.put("purpose", request.purposeCode());
        claims.put("projection", request.projectionId());
        claims.put("consumerSession", CompensationPlanSnapshotOwnerHttpClient.CONSUMER_SESSION);
        claims.put("populationPolicy", CompensationPlanSnapshotOwnerHttpClient.POPULATION_POLICY);
        claims.put("fieldPolicy", CompensationPlanSnapshotOwnerHttpClient.FIELD_POLICY);
        claims.put("method", "GET");
        claims.put("path", path);
        claims.put("snapshotId", request.snapshotId().toString());
        claims.put("snapshotRevision", request.snapshotRevision());
        claims.put("payloadDigest", request.payloadDigest());
        try {
            JWSObject object = new JWSObject(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(key.getKeyID())
                            .build(),
                    new Payload(mapper.writeValueAsString(claims)));
            object.sign(new RSASSASigner(key));
            return object.serialize();
        } catch (JOSEException | java.io.IOException exception) {
            throw new CompensationPlanSnapshotOwnerPort.TemporarilyUnavailableException(
                    "PAY owner-refetch workload token could not be issued.", exception);
        }
    }
}
