package com.dwp.services.platform.widgetregistry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "plt_widget_definitions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WidgetDefinition extends WidgetRegistryEntity {
    @Id
    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;
    @Column(name = "definition_key", nullable = false, length = 160)
    private String definitionKey;
    @Column(name = "legacy_widget_key", length = 80)
    private String legacyWidgetKey;
    @Column(name = "owner_product_key", nullable = false, length = 120)
    private String ownerProductKey;
    @Column(name = "owner_team_key", nullable = false, length = 120)
    private String ownerTeamKey;
    @Column(name = "risk_tier", nullable = false, length = 16)
    private String riskTier;
    @Column(name = "data_classification", nullable = false, length = 24)
    private String dataClassification;
    @Column(name = "definition_state", nullable = false, length = 16)
    private String definitionState;
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
