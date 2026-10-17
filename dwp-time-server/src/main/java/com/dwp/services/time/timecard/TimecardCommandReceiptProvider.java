package com.dwp.services.time.timecard;

import com.dwp.core.exception.BaseException;
import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import com.dwp.services.time.commandreceipt.CommandReceiptLookup;
import com.dwp.services.time.commandreceipt.CommandReceiptProvider;
import com.dwp.services.time.commandreceipt.VerifiedCommandReceiptRequest;
import com.dwp.services.time.timecard.TimecardModels.VerifiedTimecardRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final Clock clock;

    @Autowired
    public TimecardCommandReceiptProvider(
            TimecardRepository repository, TimecardService service) {
        this(repository, service, Clock.systemUTC());
    }

    TimecardCommandReceiptProvider(
            TimecardRepository repository, TimecardService service, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean owns(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        return freshAuthority(lookup).isPresent()
                && repository.findReceipt(lookup.tenantId(), lookup.receiptId()).isPresent();
    }

    @Override
    public Object receipt(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        VerifiedTimecardRequest authority = freshAuthority(lookup)
                .orElseThrow(TimecardCommandReceiptProvider::opaque);
        try {
            return service.receipt(authority, lookup.receiptId());
        } catch (BaseException failure) {
            throw HrisTimeApiException.from(failure);
        }
    }

    private Optional<VerifiedTimecardRequest> freshAuthority(CommandReceiptLookup lookup) {
        if (!(lookup.authorityEvidence() instanceof VerifiedCommandReceiptRequest common)) {
            return Optional.empty();
        }
        VerifiedTimecardRequest provider = lookup.providerAuthority(
                        PROVIDER_ID, VerifiedTimecardRequest.class)
                .orElse(null);
        Instant now = clock.instant();
        if (!common.revalidateAt().isAfter(now)
                || provider == null
                || !provider.revalidateAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(provider);
    }

    private static HrisTimeApiException opaque() {
        return HrisTimeApiException.of(HrisTimeProblemCode.TARGET_NOT_DISCLOSED);
    }
}
