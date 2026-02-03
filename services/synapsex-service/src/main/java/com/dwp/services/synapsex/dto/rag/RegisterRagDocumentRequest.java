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

    private String s3Key;
    private String url;
    private String checksum;
}
