package com.dwp.services.platform.personalsettings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "usr_personal_privacy_consents")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalPrivacyConsent {

    @Id
    @Column(name = "personal_privacy_consent_id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "purpose_key", nullable = false, length = 64)
    private String purposeKey;

    @Column(name = "consent_state", nullable = false, length = 16)
    private String consentState;

    @Column(name = "notice_version", nullable = false, length = 64)
    private String noticeVersion;

    @Column(name = "source", nullable = false, length = 32)
    private String source;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
