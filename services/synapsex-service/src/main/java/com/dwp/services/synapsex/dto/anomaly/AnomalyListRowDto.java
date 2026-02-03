package com.dwp.services.synapsex.dto.anomaly;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * B1) GET /anomalies 응답 row
 * anomaly_id = case_id, anomaly_type = case_type
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyListRowDto {

    private Long anomalyId;  // case_id
    private String anomalyType;  // case_type
    private String severity;
    private BigDecimal score;
    private Instant detectedAt;
    private Map<String, Object> topEvidence;  // from evidence_json: xblnr match, amount match, etc
    private List<String> docKeys;
    private List<Long> partyIds;
}
