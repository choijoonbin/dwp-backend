package com.dwp.services.synapsex.entity;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class TenantCompanyCodeScopeId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tenantId;
    private String bukrs;
}
