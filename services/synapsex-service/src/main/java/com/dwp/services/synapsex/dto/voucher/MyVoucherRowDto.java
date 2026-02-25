package com.dwp.services.synapsex.dto.voucher;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyVoucherRowDto {
    private String bukrs;
    private String belnr;
    private String gjahr;
    private LocalDate postingDate;
    private BigDecimal wrbtr;
    private String waers;
    private String bktxt;
    private Long caseId;
    private String caseStatus;
    private BigDecimal score;
    private Instant detectedAt;
}
