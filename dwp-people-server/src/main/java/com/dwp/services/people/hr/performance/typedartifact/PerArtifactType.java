package com.dwp.services.people.hr.performance.typedartifact;

public enum PerArtifactType {
    COMPENSATION_GUIDELINES("CompensationGuidelinesContent.v1", "COMPENSATION_CYCLE"),
    COMPENSATION_POPULATION("CompensationPopulationContent.v1", "COMPENSATION_CYCLE"),
    COMPENSATION_PROPOSAL(
            "CompensationProposalContent.v1", "COMPENSATION_PROPOSAL_REVISION");

    public static final int SCHEMA_VERSION = 1;

    private final String schemaId;
    private final String ownerAggregateType;

    PerArtifactType(String schemaId, String ownerAggregateType) {
        this.schemaId = schemaId;
        this.ownerAggregateType = ownerAggregateType;
    }

    public String schemaId() {
        return schemaId;
    }

    public String ownerAggregateType() {
        return ownerAggregateType;
    }
}
