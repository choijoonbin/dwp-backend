package com.dwp.services.platform.personalsettings;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "usr_personal_settings_workspace_states")
@IdClass(PersonalSettingsWorkspaceStateId.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalSettingsWorkspaceState extends BaseEntity {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "last_change_at", nullable = false)
    private LocalDateTime lastChangeAt;

    @Column(name = "last_confirmed_at")
    private LocalDateTime lastConfirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
