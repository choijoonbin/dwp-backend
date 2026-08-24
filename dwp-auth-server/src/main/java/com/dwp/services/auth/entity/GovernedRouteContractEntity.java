package com.dwp.services.auth.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "auth_governed_route_contract")
public class GovernedRouteContractEntity {

    @EmbeddedId
    @AttributeOverride(name = "contractKey", column = @Column(name = "route_contract_key"))
    private ProductAuthorizationEntityId id;

    @Column(name = "navigation_context_id", nullable = false, length = 160)
    private String navigationContextId;

    @Column(name = "subject_type", nullable = false, length = 24)
    private String subjectType;

    @Column(name = "product_key", length = 80)
    private String productKey;

    @Column(name = "surface_key", length = 120)
    private String surfaceKey;

    @Column(name = "route_kind", nullable = false, length = 16)
    private String routeKind;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "descriptor", nullable = false, columnDefinition = "jsonb")
    private JsonNode descriptor;

    protected GovernedRouteContractEntity() {
    }
}
