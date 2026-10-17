package com.dwp.services.payroll.preparation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authoritative semantic owner seam; the default is deliberately unavailable. */
interface PayrollPreparationSemanticSnapshotPort {

    Resolution resolve(
            List<PayrollPreparationModels.SourceLine> sourceLines,
            List<PayrollPreparationModels.FormulaRef> formulas);

    record Resolution(
            Availability availability,
            UUID sourceId,
            long sourceRevision,
            String functionSetVersion,
            String semanticBindingDigest,
            List<PayrollPreparationModels.PreparedLine> lines) {

        public Resolution {
            lines = lines == null ? List.of() : List.copyOf(lines);
            if (availability == Availability.EXACT) {
                Objects.requireNonNull(sourceId, "sourceId");
                if (sourceRevision < 1) {
                    throw new IllegalArgumentException(
                            "semantic source revision must be positive");
                }
                PayrollPreparationModels.token(
                        functionSetVersion, 64, "functionSetVersion");
                PayrollPreparationModels.digest(
                        semanticBindingDigest, "semanticBindingDigest");
                if (lines.isEmpty()) {
                    throw new IllegalArgumentException("exact semantic lines are required");
                }
            } else if (sourceId != null || sourceRevision != 0
                    || functionSetVersion != null
                    || semanticBindingDigest != null || !lines.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable semantic result cannot contain facts");
            }
        }

        static Resolution unavailable() {
            return new Resolution(
                    Availability.UNAVAILABLE, null, 0, null, null, List.of());
        }

        static Resolution exact(
                UUID sourceId,
                long sourceRevision,
                String functionSetVersion,
                String semanticBindingDigest,
                List<PayrollPreparationModels.PreparedLine> lines) {
            return new Resolution(
                    Availability.EXACT, sourceId, sourceRevision, functionSetVersion,
                    semanticBindingDigest, lines);
        }
    }

    enum Availability { EXACT, UNAVAILABLE }
}
