package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String version;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Instant createdAt;
    private Instant updatedAt;
}
