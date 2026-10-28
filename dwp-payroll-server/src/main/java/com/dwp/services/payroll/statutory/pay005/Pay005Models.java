package com.dwp.services.payroll.statutory.pay005;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class Pay005Models {

    private Pay005Models() {
    }

    record PostedMoney(BigDecimal amount, String currency) {
        PostedMoney {
            Objects.requireNonNull(amount, "amount");
            if (amount.signum() < 0 || amount.scale() > 4
                    || amount.precision() + Math.max(0, 4 - amount.scale()) > 19) {
                throw new IllegalArgumentException(
                        "PostedMoney amount requires exact NUMERIC(19,4)");
            }
            amount = amount.setScale(4);
            if (currency == null || !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency is invalid");
            }
        }
    }

    record SourceLine(
            UUID resultLineId,
            PostedMoney contribution,
            String sourceSemantic) {
        SourceLine {
            Objects.requireNonNull(resultLineId, "resultLineId");
            Objects.requireNonNull(contribution, "contribution");
            if (!"TYPED_COUNTRY_RULE_OUTPUT".equals(sourceSemantic)) {
                throw new IllegalArgumentException(
                        "Generic deduction or control totals cannot become TAX_RESULT");
            }
        }
    }

    record TaxResultProviderOutcome(
            long tenantId,
            UUID runId,
            int attemptNo,
            UUID workerResultId,
            UUID packId,
            long activationRevision,
            String policyVersion,
            PostedMoney amount,
            List<SourceLine> sourceLines,
            String certifiedProviderEvidenceDigest,
            Instant recordedAt) {
        TaxResultProviderOutcome {
            if (tenantId < 1 || attemptNo < 1 || activationRevision < 1) {
                throw new IllegalArgumentException("provider lineage is invalid");
            }
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(workerResultId, "workerResultId");
            Objects.requireNonNull(packId, "packId");
            token(policyVersion, 64, "policyVersion");
            Objects.requireNonNull(amount, "amount");
            sourceLines = List.copyOf(sourceLines);
            if (sourceLines.isEmpty()) {
                throw new IllegalArgumentException("sourceLines is empty");
            }
            digest(certifiedProviderEvidenceDigest,
                    "certifiedProviderEvidenceDigest");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }

    record PreparedTaxResultFact(
            UUID taxResultFactId,
            TaxResultProviderOutcome outcome,
            List<UUID> sourceResultLineIds,
            String sourceLineSetDigest,
            String providerOutcomeDigest,
            String resultDigest) {
        PreparedTaxResultFact {
            Objects.requireNonNull(taxResultFactId, "taxResultFactId");
            Objects.requireNonNull(outcome, "outcome");
            sourceResultLineIds = List.copyOf(sourceResultLineIds);
            digest(sourceLineSetDigest, "sourceLineSetDigest");
            digest(providerOutcomeDigest, "providerOutcomeDigest");
            digest(resultDigest, "resultDigest");
        }
    }

    static void token(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum
                || !value.equals(value.strip())
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    static void digest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
