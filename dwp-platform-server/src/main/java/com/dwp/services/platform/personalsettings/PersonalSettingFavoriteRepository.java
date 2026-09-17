package com.dwp.services.platform.personalsettings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PersonalSettingFavoriteRepository extends JpaRepository<PersonalSettingFavorite, Long> {

    List<PersonalSettingFavorite> findByTenantIdAndUserIdOrderByUpdatedAtDesc(Long tenantId, Long userId);

    Optional<PersonalSettingFavorite> findByTenantIdAndUserIdAndSettingKey(
            Long tenantId, Long userId, String settingKey);
}
