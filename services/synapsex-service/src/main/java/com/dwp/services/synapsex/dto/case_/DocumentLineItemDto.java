package com.dwp.services.synapsex.dto.case_;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Case Detail evidence.documentOrOpenItem.items[] 단일 라인.
 * Phase A: fi_doc_item 풍부한 라인 정보 (조회/표시 목적).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentLineItemDto {

    /** 행 식별자. FE data-row-id fallback: docKey + "-" + buzei 또는 buzei만. */
    private String id;
    private String buzei;
    private String lifnr;
    private String kunnr;
    private BigDecimal wrbtr;

    // Phase A 확장
    private String hkont;
    private String bschl;
    private String shkzg;
    private BigDecimal dmbtr;
    private String waers;
    private String mwskz;
    private String kostl;
    private String prctr;
    private String aufnr;
    private String zterm;
    private LocalDate zfbdt;
    private LocalDate dueDate;
    private Boolean paymentBlock;
    private Boolean disputeFlag;
    private String zuonr;
    private String sgtxt;

    /** 케이스가 특정 라인(buzei)을 갖는 경우 해당 라인 true, 프론트 강조용 */
    private Boolean isTarget;
}
