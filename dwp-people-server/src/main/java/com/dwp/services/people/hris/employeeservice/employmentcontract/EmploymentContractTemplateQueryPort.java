package com.dwp.services.people.hris.employeeservice.employmentcontract;

import com.dwp.platform.contracts.hris.generated.EmploymentContractTemplateSnapshotV1;

import java.time.LocalDate;
import java.util.UUID;

/** Typed PDX-015 lookup boundary; callers never infer or synthesize templates. */
public interface EmploymentContractTemplateQueryPort {

    EmploymentContractTemplateSnapshotV1 requirePublished(TemplateQuery query);

    /**
     * The admitted query carries only relationship and legal-employer public IDs.
     * Consequently GLOBAL, that LEGAL_ENTITY, and that relationship SUBJECT are
     * the only template scopes this owner can prove without inventing authority.
     */
    static void requireTrustedScope(
            TemplateQuery query, EmploymentContractTemplateSnapshotV1 snapshot) {
        if (query == null || snapshot == null) {
            throw EmploymentContractException.unavailable(
                    "The PDX-015 template scope is unavailable.");
        }
        boolean trusted = switch (snapshot.scopeType()) {
            case "GLOBAL" -> snapshot.scopeId() == null;
            case "LEGAL_ENTITY" -> query.legalEmployerPublicId()
                    .equals(snapshot.scopeId());
            case "SUBJECT" -> query.relationshipPublicId().equals(snapshot.scopeId());
            default -> false;
        };
        if (!trusted) {
            throw EmploymentContractException.unavailable(
                    "The PDX-015 template scope does not match the trusted relationship.");
        }
    }

    record TemplateQuery(
            long trustedTenantId,
            UUID relationshipPublicId,
            UUID legalEmployerPublicId,
            UUID templatePublicId,
            long templateVersion,
            String locale,
            LocalDate effectiveFrom) {
        public TemplateQuery {
            if (trustedTenantId < 1 || relationshipPublicId == null
                    || legalEmployerPublicId == null || templatePublicId == null
                    || templateVersion < 1 || locale == null || locale.isBlank()
                    || effectiveFrom == null) {
                throw EmploymentContractException.invalid(
                        "The PDX-015 template query is incomplete.");
            }
        }
    }
}
