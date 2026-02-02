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
public class PolicySodRuleId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tenantId;
    private Long profileId;
    private String ruleKey;
}
