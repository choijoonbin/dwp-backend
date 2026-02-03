package com.dwp.services.synapsex.dto.dictionary;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DictionaryTermUpsertRequest {

    @NotBlank(message = "termKey is required")
    private String termKey;

    private String labelKo;
    private String description;
    private String category;
}
