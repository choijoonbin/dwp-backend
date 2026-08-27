package com.dwp.services.provider.support;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists privilege-removal transitions independently of a later denial. */
@Service
public class ProviderSupportSessionLifecycleService {

    private final ProviderSupportSessionRepository repository;
    private final ProviderSupportRequestRepository requestRepository;

    public ProviderSupportSessionLifecycleService(
            ProviderSupportSessionRepository repository,
            ProviderSupportRequestRepository requestRepository) {
        this.repository = repository;
        this.requestRepository = requestRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireElapsedSessions() {
        try {
            repository.lockContainmentLedger();
            int changed = repository.expireSupportSessions();
            return changed + requestRepository.expireElapsedRequests();
        } catch (BaseException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BaseException(
                    ErrorCode.AUTHORITY_RESOLUTION_UNAVAILABLE,
                    "Support lifecycle evidence is temporarily unavailable.",
                    exception);
        }
    }
}
