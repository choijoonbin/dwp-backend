package com.dwp.services.platform.personalsettings;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class PersonalSettingsWorkspaceStateId implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long tenantId;
    private Long userId;
}
