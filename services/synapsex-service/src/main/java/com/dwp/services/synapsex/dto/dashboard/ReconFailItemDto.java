package com.dwp.services.synapsex.dto.dashboard;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** recon_result FAIL 1건 요약 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReconFailItemDto {

    private Long resultId;
    private String resourceType;
    private String resourceKey;
    private String status;
    private JsonNode detailJson;
}
