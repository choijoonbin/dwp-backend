package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentListDto {
    private Long docId;
    private String title;
    private String sourceType;
    private String docType;
    private String status;
    private Boolean qualityGatePassed;
    private JsonNode qualityReport;
    private Long refCount;
    private String version;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Instant createdAt;
    private Instant updatedAt;
}
