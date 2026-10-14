package com.dwp.services.platform.auditcontrol;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.dwp.services.platform.auditcontrol.HcmAuditEvidenceCursorCodec.AccessContext;
import static com.dwp.services.platform.auditcontrol.HcmAuditEvidenceDtos.Evidence;
import static com.dwp.services.platform.auditcontrol.HcmAuditEvidenceDtos.Page;

@Service
public class HcmAuditEvidenceService {

    private final HcmAuditEvidenceRepository repository;
    private final HcmAuditEvidenceCursorCodec cursorCodec;
    private final Set<String> registeredServices;
    private final Clock clock;

    @Autowired
    public HcmAuditEvidenceService(
            HcmAuditEvidenceRepository repository,
            HcmAuditEvidenceCursorCodec cursorCodec,
            @Value("${dwp.platform.audit.allowed-services:"
                    + AuditControlService.DEFAULT_ALLOWED_SERVICES + "}") String allowedServices) {
        this(repository, cursorCodec, allowedServices, Clock.systemUTC());
    }

    HcmAuditEvidenceService(
            HcmAuditEvidenceRepository repository,
            HcmAuditEvidenceCursorCodec cursorCodec,
            String allowedServices,
            Clock clock) {
        this.repository = repository;
        this.cursorCodec = cursorCodec;
        this.registeredServices = Arrays.stream(allowedServices.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page page(
            AccessContext access,
            AuditWindow window,
            String category,
            String severity,
            String outcome,
            String sourceService,
            String query,
            int size,
            String cursor) {
        HcmAuditEvidenceCriteria criteria = HcmAuditEvidenceCriteria.of(
                access.tenantId(), window, category, severity, outcome,
                sourceService, query, size, clock.instant(), registeredServices);
        HcmAuditEvidenceCursorCodec.CursorPosition position = null;
        if (cursor != null && !cursor.isBlank()) {
            position = cursorCodec.decode(cursor, access, criteria);
            criteria = criteria.atSnapshot(position.snapshotAt());
        }
        List<Evidence> rows = repository.list(criteria, position, size + 1);
        boolean hasMore = rows.size() > size;
        List<Evidence> items = List.copyOf(rows.subList(0, Math.min(rows.size(), size)));
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Evidence last = items.getLast();
            nextCursor = cursorCodec.encode(
                    access, criteria, last.occurredAt(), last.eventId());
        }
        return new Page(items, items.size(), criteria.snapshotAt(), nextCursor);
    }

    @Transactional(readOnly = true)
    public Evidence detail(AccessContext access, UUID eventId) {
        if (eventId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT_VALUE, "Event identifier is required.");
        }
        Instant snapshotAt = clock.instant();
        return repository.findById(access.tenantId(), eventId, snapshotAt)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.NOT_FOUND,
                        "The HCM audit evidence was not found."));
    }
}
