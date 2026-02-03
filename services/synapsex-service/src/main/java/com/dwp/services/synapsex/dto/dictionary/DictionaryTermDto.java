package com.dwp.services.synapsex.dto.dictionary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DictionaryTermDto {
    private Long termId;
    private String termKey;
    private String labelKo;
    private String description;
    private String category;
    private Instant createdAt;
}
