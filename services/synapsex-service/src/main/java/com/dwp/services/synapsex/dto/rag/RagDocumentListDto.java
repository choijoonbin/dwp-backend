package com.dwp.services.synapsex.dto.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagDocumentListDto {
    private Long docId;
    private String title;
    private String sourceType;
    private String status;
    private Instant createdAt;
}
