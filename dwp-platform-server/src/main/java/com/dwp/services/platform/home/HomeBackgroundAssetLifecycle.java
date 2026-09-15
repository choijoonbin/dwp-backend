package com.dwp.services.platform.home;

import com.dwp.services.platform.media.TenantMediaStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Keeps background cleanup behavior together without changing the service transaction boundary. */
final class HomeBackgroundAssetLifecycle {

    private static final Logger log = LoggerFactory.getLogger(HomeBackgroundAssetLifecycle.class);

    private final TenantMediaStorage assetStorage;

    HomeBackgroundAssetLifecycle(TenantMediaStorage assetStorage) {
        this.assetStorage = assetStorage;
    }

    void clear(HomeExperience experience) {
        experience.setBackgroundAssetKey(null);
        experience.setBackgroundOriginalName(null);
        experience.setBackgroundContentType(null);
        experience.setBackgroundSizeBytes(null);
        experience.setBackgroundSha256(null);
        experience.setBackgroundWidth(null);
        experience.setBackgroundHeight(null);
    }

    void deleteQuietly(Long tenantId, String storageKey) {
        if (storageKey == null) return;
        try {
            assetStorage.delete(tenantId, storageKey);
        } catch (RuntimeException exception) {
            log.warn(
                    "Home asset cleanup failed for tenant {} and key {}",
                    tenantId,
                    storageKey,
                    exception);
        }
    }

    boolean scheduleRollbackCleanup(Long tenantId, String replacementKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            deleteQuietly(tenantId, replacementKey);
                        }
                    }
                });
        return true;
    }
}
