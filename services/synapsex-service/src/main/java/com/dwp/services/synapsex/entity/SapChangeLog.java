package com.dwp.services.synapsex.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * sap_change_log — CDHDR/CDPOS 등 변경 이력
 */
@Entity
@Table(schema = "dwp_aura", name = "sap_change_log")
@IdClass(SapChangeLogId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SapChangeLog {

    @Id
    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Id
    @Column(name = "objectclas", nullable = false, length = 15)
    private String objectclas;

    @Id
    @Column(name = "objectid", nullable = false, length = 90)
    private String objectid;

    @Id
    @Column(name = "changenr", nullable = false, length = 10)
    private String changenr;

    @Column(name = "username", length = 12)
    private String username;

    @Column(name = "udate")
    private LocalDate udate;

    @Column(name = "utime")
    private LocalTime utime;

    @Id
    @Column(name = "tabname", length = 30)
    private String tabname;

    @Id
    @Column(name = "fname", length = 30)
    private String fname;

    @Column(name = "value_old", columnDefinition = "TEXT")
    private String valueOld;

    @Column(name = "value_new", columnDefinition = "TEXT")
    private String valueNew;

    @Column(name = "last_change_ts")
    private Instant lastChangeTs;

    @Column(name = "raw_event_id")
    private Long rawEventId;
}
