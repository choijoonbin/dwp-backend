package com.dwp.services.payroll.preparation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Authoritative tuple-to-source membership seam; absence fails closed. */
interface PayrollPreparationPopulationSnapshotPort {

    Review review(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationAuthority.PopulationTuple population,
            Instant asOf);

    Materialization materialize(
            PayrollPreparationAuthority.Actor actor,
            PayrollPreparationAuthority.PopulationTuple population,
            UUID payPeriodId,
            Instant asOf,
            String reviewedDigest);

    enum Availability { EXACT, UNAVAILABLE }

    record Review(
            Availability availability,
            long itemCount,
            long revision,
            String projectionDigest) {

        public Review {
            Objects.requireNonNull(availability, "availability");
            if (availability == Availability.EXACT) {
                if (itemCount < 0 || revision < 1) {
                    throw new IllegalArgumentException("population review is invalid");
                }
                PayrollPreparationModels.digest(
                        projectionDigest, "projectionDigest");
            } else if (itemCount != 0 || revision != 0
                    || projectionDigest != null) {
                throw new IllegalArgumentException(
                        "unavailable population review contains facts");
            }
        }

        static Review unavailable() {
            return new Review(Availability.UNAVAILABLE, 0, 0, null);
        }
    }

    record Materialization(
            Availability availability,
            UUID projectionId,
            long projectionRevision,
            String projectionDigest,
            List<PayrollPreparationModels.SourceLine> lines) {

        public Materialization {
            Objects.requireNonNull(availability, "availability");
            lines = lines == null ? List.of() : List.copyOf(lines);
            if (availability == Availability.EXACT) {
                Objects.requireNonNull(projectionId, "projectionId");
                if (projectionRevision < 1) {
                    throw new IllegalArgumentException(
                            "population projection revision must be positive");
                }
                PayrollPreparationModels.digest(
                        projectionDigest, "projectionDigest");
                if (lines.isEmpty()) {
                    throw new IllegalArgumentException(
                            "exact population materialization requires lines");
                }
                for (int index = 0; index < lines.size(); index++) {
                    if (lines.get(index).lineSequence() != index + 1) {
                        throw new IllegalArgumentException(
                                "population materialization must be ordered");
                    }
                }
            } else if (projectionId != null || projectionRevision != 0
                    || projectionDigest != null || !lines.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable population materialization contains facts");
            }
        }

        static Materialization unavailable() {
            return new Materialization(
                    Availability.UNAVAILABLE, null, 0, null, List.of());
        }
    }
}
