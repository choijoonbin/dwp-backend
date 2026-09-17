package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonalSettingActivityRepository extends JpaRepository<PersonalSettingActivity, UUID> {

    List<PersonalSettingActivity> findTop50ByTenantIdAndUserIdOrderByOccurredAtDesc(
            Long tenantId, Long userId);

    Optional<PersonalSettingActivity> findTopByTenantIdAndUserIdAndSettingKeyAndActivityTypeOrderByOccurredAtDesc(
            Long tenantId, Long userId, String settingKey, String activityType);
}
