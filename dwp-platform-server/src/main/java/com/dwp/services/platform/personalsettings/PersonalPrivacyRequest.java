package com.dwp.services.platform.personalsettings;

import com.dwp.core.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "usr_personal_privacy_requests")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalPrivacyRequest extends BaseEntity {

    @Id
    @Column(name = "personal_privacy_request_id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_type", nullable = false, length = 32)
    private String requestType;

    @Column(name = "request_state", nullable = false, length = 24)
    private String requestState;

    @Column(name = "requested_scope", nullable = false, length = 64)
    private String requestedScope;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
