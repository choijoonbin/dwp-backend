package com.dwp.services.provider.entitlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "prv_entitlement_catalog")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Entitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entitlement_id")
    private Long entitlementId;

    @Column(name = "entitlement_key", nullable = false, unique = true, length = 120)
    private String entitlementKey;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "entitlement_type", nullable = false, length = 20)
    private String entitlementType;

    @Column(length = 1000)
    private String description;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;
}
