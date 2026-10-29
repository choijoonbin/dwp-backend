package com.dwp.services.platform.mail;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/** Keeps provider/network preparation outside the short cursor-fenced database commit. */
@Component
class MailExternalSyncTransactions {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    <T> T commit(Supplier<T> work) {
        return work.get();
    }
}
