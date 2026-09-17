package com.dwp.services.platform.personalsettings;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "usr_personal_setting_activity")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PersonalSettingActivity {

    @Id
    @Column(name = "personal_setting_activity_id")
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "setting_key", nullable = false, length = 64)
    private String settingKey;

    @Column(name = "activity_type", nullable = false, length = 16)
    private String activityType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "changed_fields", nullable = false, columnDefinition = "jsonb")
    private JsonNode changedFields;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
