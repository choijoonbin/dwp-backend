package com.dwp.services.synapsex.dto.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagChunkReplaceRequest {

    @NotEmpty
    private List<@Valid AuraChunkItemDto> chunks;
}
