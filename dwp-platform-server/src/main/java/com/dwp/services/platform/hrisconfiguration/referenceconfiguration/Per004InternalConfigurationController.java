package com.dwp.services.platform.hrisconfiguration.referenceconfiguration;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import static com.dwp.platform.contracts.hris.referenceconfiguration.HrisReferenceConfigurationV1.*;

@RestController
@ConditionalOnProperty(name = "dwp.hris.reference-configuration.enabled", havingValue = "true")
class Per004InternalConfigurationController {
    private final Per004OwnerEvidenceStore evidence;
    private final LookupEvidenceSigner signer;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    Per004InternalConfigurationController(Per004OwnerEvidenceStore evidence, ObjectProvider<LookupEvidenceSigner> signer) {
        this.evidence = evidence; this.signer = signer.getIfAvailable();
    }

    @PostMapping("/internal/hris-reference-configuration/v1/owner-projections:query")
    @Transactional(readOnly = true)
    OwnerProjectionResponse projection(HttpServletRequest request, @RequestBody OwnerProjectionRequest body) {
        long tenant = Per004WorkloadSecurityFilter.admission(request).tenantId();
        OwnerProjectionRequest query = Per004ProjectionQuery.normalize(body, OffsetDateTime.now(ZoneOffset.UTC));
        return Per004ProjectionQuery.page(tenant, query, evidence.project(tenant, query), mapper);
    }

    @PostMapping("/internal/hris-reference-configuration/v1/authoritative-create-lookups:query")
    @Transactional
    CreateLookupResponse lookup(HttpServletRequest request, @RequestBody CreateLookupRequest body) {
        long tenant = Per004WorkloadSecurityFilter.admission(request).tenantId();
        CreateIdentity id = body == null ? null : body.identity();
        if (id == null || id.tenantId() != tenant || id.configurationId() == null
                || id.createSubjectPrincipalPublicId() == null || id.ownerPreparationReceiptId() == null
                || !"configuration.version.create".equals(id.originatingAction())
                || id.createIdempotencyKey() == null || id.createIdempotencyKey().length() < 16 || id.createIdempotencyKey().length() > 128
                || id.createIdempotencyKey().indexOf('\r') >= 0 || id.createIdempotencyKey().indexOf('\n') >= 0
                || id.createRequestDigest() == null || !id.createRequestDigest().matches("^[a-f0-9]{64}$")) throw invalid();
        if (signer == null) return new CreateLookupResponse(id, LookupState.UNKNOWN,
                null, null, null, null, OffsetDateTime.now(ZoneOffset.UTC), null);
        CreateLookupResponse found = evidence.lookup(id, OffsetDateTime.now(ZoneOffset.UTC));
        if (found.state() != LookupState.ABSENT_FINAL) return found;
        String signature = null;
        try { signature = signer.sign(found); }
        catch (RuntimeException failure) { throw new BaseException(ErrorCode.FORBIDDEN, "PER004_LOOKUP_SIGNER_UNAVAILABLE"); }
        // Throwing rolls back the new admission fence together with the failed negative response.
        if (signature == null || signature.isBlank()) throw new BaseException(ErrorCode.FORBIDDEN, "PER004_LOOKUP_SIGNER_UNAVAILABLE");
        return new CreateLookupResponse(id, found.state(), null, null, found.durableAdmissionFenceId(),
                found.evidenceDigest(), found.evaluatedAt(), signature);
    }
    private BaseException invalid() { return new BaseException(ErrorCode.INVALID_INPUT_VALUE, "PER004_INTERNAL_BINDING_INVALID"); }
}
