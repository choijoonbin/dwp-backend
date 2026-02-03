package com.dwp.services.synapsex.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class SapChangeLogId implements Serializable {

    private Long tenantId;
    private String objectclas;
    private String objectid;
    private String changenr;
    private String tabname;
    private String fname;
}
