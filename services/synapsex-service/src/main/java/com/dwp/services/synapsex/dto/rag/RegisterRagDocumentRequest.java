package com.dwp.services.synapsex.dto.rag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRagDocumentRequest {

    @NotBlank(message = "title is required")
    private String title;

    @NotNull(message = "sourceType is required")
    private String sourceType;  // UPLOAD, S3, URL

    /** 문서 성격: REGULATION, MANUAL, POLICY 등 (업로드 시 명시) */
    private String docType;

    private String s3Key;
    private String url;
    private String checksum;
}
