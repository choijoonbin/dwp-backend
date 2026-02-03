package com.dwp.services.synapsex.dto.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GET /entities/{partyId}/change-logs 응답 row
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityChangeLogDto {

    private String changenr;
    private String udate;
    private String utime;
    private String tabname;
    private String fname;
    private String valueOld;
    private String valueNew;
}
