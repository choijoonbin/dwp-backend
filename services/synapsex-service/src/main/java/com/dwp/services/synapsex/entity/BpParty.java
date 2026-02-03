package com.dwp.services.synapsex.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;

/**
 * bp_party — 거래처 마스터 (Business Partner)
 */
@Entity
@Table(schema = "dwp_aura", name = "bp_party")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BpParty {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "party_id")
    private Long partyId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "party_type", nullable = false, length = 10)
    private String partyType;

    @Column(name = "party_code", nullable = false, length = 40)
    private String partyCode;

    @Column(name = "name_display", length = 200)
    private String nameDisplay;

    @Column(name = "country", length = 3)
    private String country;

    @Column(name = "created_on")
    private LocalDate createdOn;

    @Column(name = "is_one_time", nullable = false)
    @Builder.Default
    private Boolean isOneTime = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "risk_flags", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private JsonNode riskFlags = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();

    @Column(name = "last_change_ts")
    private Instant lastChangeTs;

    @Column(name = "raw_event_id")
    private Long rawEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
