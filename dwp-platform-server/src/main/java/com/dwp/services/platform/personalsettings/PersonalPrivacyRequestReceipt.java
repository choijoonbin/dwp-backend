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
@Table(name = "usr_personal_privacy_request_receipts")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalPrivacyRequestReceipt {

    @Id
    @Column(name = "personal_privacy_request_id")
    private UUID requestId;

    @Column(name = "receipt_id", nullable = false, unique = true)
    private UUID receiptId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "receipt_type", nullable = false, length = 24)
    private String receiptType;

    @Column(name = "evidence_state", nullable = false, length = 24)
    private String evidenceState;

    @Column(name = "fulfillment_boundary", nullable = false, length = 96)
    private String fulfillmentBoundary;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;
}
