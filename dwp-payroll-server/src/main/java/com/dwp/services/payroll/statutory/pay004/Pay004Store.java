package com.dwp.services.payroll.statutory.pay004;

import java.time.Instant;

interface Pay004Store {
    Pay004Models.SourcePage retirement(
            Pay004Authority.Actor actor,
            Pay004Models.QueryRequest query,
            Instant asOf,
            int offset);
}
