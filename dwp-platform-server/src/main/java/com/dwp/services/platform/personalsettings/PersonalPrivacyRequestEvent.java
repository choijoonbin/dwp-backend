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
@Table(name = "usr_personal_privacy_request_events")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalPrivacyRequestEvent {

    @Id
    @Column(name = "personal_privacy_request_event_id")
    private UUID id;

    @Column(name = "personal_privacy_request_id", nullable = false)
    private UUID requestId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "request_state", nullable = false, length = 24)
    private String requestState;

    @Column(name = "detail_key", nullable = false, length = 96)
    private String detailKey;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
