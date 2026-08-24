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
@Table(name = "auth_product_access_policy")
public class GovernedAccessPolicyEntity {

    @EmbeddedId
    @AttributeOverride(name = "contractKey", column = @Column(name = "access_policy_key"))
    private ProductAuthorizationEntityId id;

    @Column(name = "navigation_context_id", nullable = false, length = 160)
    private String navigationContextId;

    @Column(name = "product_key", length = 80)
    private String productKey;

    @Column(name = "surface_key", length = 120)
    private String surfaceKey;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "descriptor", nullable = false, columnDefinition = "jsonb")
    private JsonNode descriptor;

    protected GovernedAccessPolicyEntity() {
    }
}
