package com.dwp.services.time.timecard;

import com.dwp.core.exception.BaseException;
import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.commandreceipt.CommandReceiptLookup;
import com.dwp.services.time.commandreceipt.CommandReceiptProvider;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Compatibility adapter that preserves the existing nine-field Timecard receipt wire DTO. */
@Component
@ConditionalOnProperty(
        name = "dwp.hris.time.timecard.enabled",
        havingValue = "true",
        matchIfMissing = false)
public final class TimecardCommandReceiptProvider implements CommandReceiptProvider {

    public static final String PROVIDER_ID = "timecard";

    private final TimecardRepository repository;
    private final TimecardService service;

    public TimecardCommandReceiptProvider(
            TimecardRepository repository, TimecardService service) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean owns(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return repository.findReceipt(lookup.tenantId(), lookup.receiptId()).isPresent();
    }

    @Override
    public Object receipt(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        VerifiedTimecardRequest authority =
                lookup.requireAuthority(VerifiedTimecardRequest.class);
        try {
            return service.receipt(authority, lookup.receiptId());
        } catch (BaseException failure) {
            throw HrisTimeApiException.from(failure);
        }
    }
}
