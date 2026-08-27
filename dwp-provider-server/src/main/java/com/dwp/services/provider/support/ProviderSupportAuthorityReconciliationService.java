package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Emits one database-time pulse; database triggers own authority reconciliation. */
@Service
public class ProviderSupportAuthorityReconciliationService {

    private final ProviderSupportSessionRepository repository;

    public ProviderSupportAuthorityReconciliationService(
            ProviderSupportSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reconcile() {
        try {
            if (repository.pulseAuthorityReconciliation() != 1) {
                throw unavailable(null);
            }
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private BaseException unavailable(RuntimeException cause) {
        String message = "Support authority reconciliation is temporarily unavailable.";
        return cause == null
                ? new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message)
                : new BaseException(ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE, message, cause);
    }
}
