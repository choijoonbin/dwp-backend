package com.dwp.services.payroll.statutory.pay003;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface Pay003Store {

    void bindTenant(long tenantId);

    void lockIdempotency(
            long tenantId, UUID subjectPrincipalPublicId, String idempotencyKey);

    Optional<Pay003Models.StoredReceipt> receipt(
            long tenantId,
            UUID subjectPrincipalPublicId,
            String idempotencyKey);

    Optional<Pay003Models.PackSnapshot> pack(
            Pay003Authority.Actor actor, UUID packId, boolean lock);

    Pay003Models.SourcePage<Pay003Models.PackSnapshot> statutory(
            Pay003Authority.Actor actor,
            Pay003Models.QueryRequest query,
            Instant asOf);

    Pay003Models.SourcePage<Pay003Models.TaxSocialInsuranceFact> socialInsurance(
            Pay003Authority.Actor actor,
            Pay003Models.QueryRequest query,
            Instant asOf);

    void commit(Pay003Models.ActivationCommit commit);
}
