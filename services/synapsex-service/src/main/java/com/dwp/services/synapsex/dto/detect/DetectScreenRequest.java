package com.dwp.services.synapsex.dto.detect;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Aura 스크리닝 API 요청: POST /aura/detect/screen
 * 전표 헤더 + 아이템 핵심 필드(금액, 가맹점, 시간, 계정 등).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DetectScreenRequest {

    private Header header;
    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Header {
        private String bukrs;
        private String belnr;
        private String gjahr;
        private String docSource;
        private LocalDate budat;
        private LocalTime cputm;
        private String waers;
        private String bktxt;
        private String xblnr;
        private String blart;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Item {
        private String buzei;
        private String hkont;
        private BigDecimal wrbtr;
        private String lifnr;
        private String kunnr;
        private String sgtxt;
        private String bschl;
        private String shkzg;
        private String waers;
    }
}
