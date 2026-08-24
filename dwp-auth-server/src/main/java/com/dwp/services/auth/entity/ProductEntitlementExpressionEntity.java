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
@Table(name = "auth_product_entitlement_expression")
public class ProductEntitlementExpressionEntity {

    @EmbeddedId
    @AttributeOverride(name = "contractKey", column = @Column(name = "expression_key"))
    private ProductAuthorizationEntityId id;

    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private String lifecycleState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "descriptor", nullable = false, columnDefinition = "jsonb")
    private JsonNode descriptor;

    protected ProductEntitlementExpressionEntity() {
    }
}
