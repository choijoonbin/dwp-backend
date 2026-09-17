package com.dwp.services.platform.workplace.safetyoperations;

import com.dwp.core.common.ErrorCode;
import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.dwp.services.platform.workplace.safetyoperations.SafetyEmergencyContactDtos.*;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.CommandRow;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyIncidentRepository.IncidentRow;
import static com.dwp.services.platform.workplace.safetyoperations.SafetyOperationsDtos.*;

@Service
public class SafetyEmergencyContactService {
    private static final String CONFIGURE = "CONFIGURE_EMERGENCY_CONTACT";
    private static final String PREVIEW = "PREVIEW_EMERGENCY_HANDOFF";
    private static final String HANDOFF = "EMERGENCY_HANDOFF";
    private static final String RECONCILE = "RECONCILE_EMERGENCY_HANDOFF";

    private final SafetyEmergencyContactRepository repository;
    private final SafetyIncidentRepository incidents;
    private final SafetyConnectorService connectors;
    private final SafetyEmergencyHandoffProvider provider;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public SafetyEmergencyContactService(
            SafetyEmergencyContactRepository repository,
            SafetyIncidentRepository incidents,
            SafetyConnectorService connectors,
            SafetyEmergencyHandoffProvider provider,
            ObjectMapper mapper) {
        this(repository, incidents, connectors, provider, mapper, Clock.systemUTC());
    }

    SafetyEmergencyContactService(
            SafetyEmergencyContactRepository repository,
            SafetyIncidentRepository incidents,
            SafetyConnectorService connectors,
            SafetyEmergencyHandoffProvider provider,
            ObjectMapper mapper,
            Clock clock) {
        this.repository = repository;
        this.incidents = incidents;
        this.connectors = connectors;
        this.provider = provider;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<EmergencyContactView> contactsForUser(
            long tenantId, long userId, UUID incidentId) {
        requireActor(tenantId, userId);
        IncidentRow incident = incidents.incidentForUser(tenantId, userId, incidentId)
                .orElseThrow(() -> notFound("The active safety incident was not found."));
        if (!active(incident)) throw notFound("The active safety incident was not found.");
        return views(tenantId, repository.contacts(tenantId, true));
    }

    @Transactional(readOnly = true)
    public List<EmergencyContactView> adminContacts(long tenantId) {
        requireTenant(tenantId);
        return views(tenantId, repository.contacts(tenantId, false));
    }

    @Transactional(readOnly = true)
    public EmergencyContactView adminContact(long tenantId, UUID contactId) {
        requireTenant(tenantId);
        return view(tenantId, repository.contact(tenantId, contactId)
                .orElseThrow(() -> notFound("The emergency contact was not found.")), truth(tenantId));
    }

    @Transactional
    public EmergencyContactConfigurationResult configure(
            long tenantId,
            long actorId,
            UUID contactId,
            String idempotencyKey,
            EmergencyContactConfigurationRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        validateConfiguration(request);
        String key = key(idempotencyKey);
        String fingerprint = fingerprint(CONFIGURE, contactId, request);
        incidents.lockCommandKey(tenantId, actorId, CONFIGURE, key);
        CommandRow duplicate = incidents.command(tenantId, actorId, CONFIGURE, key).orElse(null);
        if (duplicate != null) {
            verifyFingerprint(duplicate, fingerprint);
            EmergencyContactView contact = adminContact(tenantId, contactId);
            return new EmergencyContactConfigurationResult(contact, receipt(duplicate, true));
        }
        EmergencyContactRow current = repository.contact(tenantId, contactId).orElse(null);
        long currentVersion = current == null ? 0 : current.version();
        if (currentVersion != request.expectedVersion()) {
            throw conflict("The emergency-contact version changed.");
        }
        OffsetDateTime now = now();
        String correlation = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, null, CONFIGURE, key,
                fingerprint, request.reason().trim(), correlation,
                "/v1/admin/workplace/safety/emergency-contacts/" + contactId, now);
        EmergencyContactRow changed = repository.configure(
                tenantId, actorId, contactId, request, now)
                .orElseThrow(() -> conflict("The emergency-contact version changed."));
        incidents.completeLocalCommand(tenantId, command.id(), "EMERGENCY_CONTACT_CONFIGURED", now);
        repository.audit(tenantId, null, actorId, "safety.emergency-contact.configured",
                "EMERGENCY_CONTACT", contactId, correlation,
                Map.of("kind", changed.kind().name(), "actionMode", changed.actionMode().name(),
                        "active", changed.active(), "directTelAllowed", changed.directTelAllowed(),
                        "credentialMaterialPersisted", false), now);
        CommandRow completed = incidents.command(tenantId, command.id()).orElseThrow();
        return new EmergencyContactConfigurationResult(
                view(tenantId, changed, truth(tenantId)), receipt(completed, false));
    }

    @Transactional
    public EmergencyHandoffPreviewResult preview(
            long tenantId,
            long actorId,
            UUID incidentId,
            String idempotencyKey,
            EmergencyHandoffPreviewRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        String key = key(idempotencyKey);
        String fingerprint = fingerprint(PREVIEW, incidentId, request);
        incidents.lockCommandKey(tenantId, actorId, PREVIEW, key);
        CommandRow duplicate = incidents.command(tenantId, actorId, PREVIEW, key).orElse(null);
        if (duplicate != null) {
            verifyFingerprint(duplicate, fingerprint);
            EmergencyHandoffPreviewRow replay = repository.previewByCommand(
                    tenantId, duplicate.id()).orElseThrow(() -> conflict(
                            "The replayed emergency-handoff preview is missing."));
            return new EmergencyHandoffPreviewResult(preview(replay), receipt(duplicate, true));
        }
        IncidentRow incident = incidents.incident(tenantId, incidentId)
                .orElseThrow(() -> notFound("The safety incident was not found."));
        EmergencyContactRow contact = repository.contact(tenantId, request.contactId())
                .orElseThrow(() -> notFound("The emergency contact was not found."));
        ProviderSnapshot snapshot = truth(tenantId);
        EmergencyContactView contactView = view(tenantId, contact, snapshot);
        List<String> limitations = new ArrayList<>();
        if (!active(incident)) limitations.add("INCIDENT_NOT_ACTIVE");
        if (incident.version() != request.expectedIncidentVersion()) {
            limitations.add("INCIDENT_VERSION_CHANGED");
        }
        if (!contact.active()) limitations.add("CONTACT_DISABLED");
        if (contact.version() != request.expectedContactVersion()) {
            limitations.add("CONTACT_VERSION_CHANGED");
        }
        if (contact.actionMode() != EmergencyContactActionMode.GOVERNED_HANDOFF) {
            limitations.add("CONTACT_USES_DIRECT_TEL");
        }
        if (contactView.providerState() != EmergencyContactProviderState.READY) {
            limitations.add("PROVIDER_NOT_READY");
        }
        boolean eligible = limitations.isEmpty();
        OffsetDateTime now = now();
        String correlation = correlation(correlationId);
        UUID previewId = UUID.randomUUID();
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId, PREVIEW, key,
                fingerprint, request.reason().trim(), correlation,
                "/v1/admin/workplace/safety/incidents/" + incidentId
                        + "/emergency-handoff-previews/" + previewId, now);
        EmergencyHandoffPreviewRow row = new EmergencyHandoffPreviewRow(
                previewId, tenantId, command.id(), actorId, incidentId, contact.contactId(),
                request.expectedIncidentVersion(), request.expectedContactVersion(),
                contactView.providerState(), contactView.providerCode(),
                contactView.providerConfigurationVersion(), snapshot.evidenceReference(), eligible,
                List.copyOf(limitations), now.plusMinutes(5), now);
        repository.insertPreview(row);
        incidents.completeLocalCommand(tenantId, command.id(), "EMERGENCY_HANDOFF_PREVIEWED", now);
        CommandRow completed = incidents.command(tenantId, command.id()).orElseThrow();
        return new EmergencyHandoffPreviewResult(preview(row), receipt(completed, false));
    }

    @Transactional(readOnly = true)
    public EmergencyHandoffPreview preview(long tenantId, UUID incidentId, UUID previewId) {
        requireTenant(tenantId);
        EmergencyHandoffPreviewRow row = repository.preview(tenantId, previewId)
                .filter(value -> value.incidentId().equals(incidentId))
                .orElseThrow(() -> notFound("The emergency-handoff preview was not found."));
        return preview(row);
    }

    @Transactional
    public EmergencyHandoffReceipt execute(
            long tenantId,
            long actorId,
            UUID incidentId,
            String idempotencyKey,
            ConfirmEmergencyHandoffRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String key = key(idempotencyKey);
        String fingerprint = fingerprint(HANDOFF, incidentId, request);
        incidents.lockCommandKey(tenantId, actorId, HANDOFF, key);
        CommandRow duplicate = incidents.command(tenantId, actorId, HANDOFF, key).orElse(null);
        if (duplicate != null) {
            verifyFingerprint(duplicate, fingerprint);
            EmergencyHandoffRow replay = repository.handoffByCommand(tenantId, duplicate.id())
                    .orElseThrow(() -> conflict("The replayed emergency handoff is missing."));
            return receipt(replay, duplicate, true);
        }
        OffsetDateTime now = now();
        EmergencyHandoffPreviewRow preview = repository.preview(tenantId, request.previewId())
                .filter(value -> value.incidentId().equals(incidentId))
                .orElseThrow(() -> notFound("The emergency-handoff preview was not found."));
        if (!preview.eligible() || !preview.expiresAt().isAfter(now)
                || preview.expectedIncidentVersion() != request.expectedIncidentVersion()
                || preview.expectedContactVersion() != request.expectedContactVersion()) {
            throw conflict("The emergency-handoff preview is stale or ineligible.");
        }
        IncidentRow incident = incidents.incident(tenantId, incidentId)
                .orElseThrow(() -> notFound("The safety incident was not found."));
        EmergencyContactRow contact = repository.contact(tenantId, preview.contactId())
                .orElseThrow(() -> notFound("The emergency contact was not found."));
        EmergencyContactView current = view(tenantId, contact, truth(tenantId));
        if (!active(incident) || incident.version() != request.expectedIncidentVersion()
                || !contact.active() || contact.version() != request.expectedContactVersion()
                || contact.actionMode() != EmergencyContactActionMode.GOVERNED_HANDOFF
                || current.providerState() != EmergencyContactProviderState.READY
                || !Objects.equals(current.providerCode(), preview.providerCode())
                || !Objects.equals(current.providerConfigurationVersion(),
                        preview.providerConfigurationVersion())) {
            throw conflict("The incident, contact, or provider truth changed after preview.");
        }
        String correlation = correlation(correlationId);
        CommandRow command = incidents.insertCommand(tenantId, actorId, incidentId, HANDOFF, key,
                fingerprint, request.reason().trim(), correlation,
                "/v1/admin/workplace/safety/incidents/" + incidentId
                        + "/emergency-handoffs/pending", now);
        UUID handoffId = deterministicHandoffId(tenantId, actorId, key);
        repository.updateCommandStatusHref(tenantId, command.id(),
                "/v1/admin/workplace/safety/incidents/" + incidentId
                        + "/emergency-handoffs/" + command.id());
        EmergencyHandoffRow pending = new EmergencyHandoffRow(
                handoffId, tenantId, command.id(), preview.previewId(), incidentId,
                contact.contactId(), CommandState.RESULT_UNKNOWN, current.providerCode(),
                current.providerConfigurationVersion(), null, null, "DISPATCHING", 1,
                now, null, now);
        repository.insertHandoff(pending);
        SafetyEmergencyHandoffProvider.Result result;
        try {
            result = provider.handoff(handoffId, tenantId, incidentId, contact.contactId(),
                    current.providerCode(), current.providerConfigurationVersion());
        } catch (RuntimeException unavailable) {
            result = new SafetyEmergencyHandoffProvider.Result(CommandState.RESULT_UNKNOWN,
                    "PROVIDER_TRANSPORT_OUTCOME_UNKNOWN", null, null);
        }
        repository.applyProviderResult(tenantId, command.id(), handoffId, result, now());
        repository.audit(tenantId, incidentId, actorId, "safety.emergency-handoff.executed",
                "EMERGENCY_HANDOFF", handoffId, correlation,
                Map.of("contactKind", contact.kind().name(), "state", result.state().name(),
                        "resultCode", result.resultCode(), "personalDataSent", false,
                        "credentialMaterialPersisted", false), now());
        EmergencyHandoffRow completed = repository.handoffByCommand(tenantId, command.id())
                .orElseThrow();
        CommandRow completedCommand = incidents.command(tenantId, command.id()).orElseThrow();
        return receipt(completed, completedCommand, false);
    }

    @Transactional(readOnly = true)
    public EmergencyHandoffReceipt handoff(
            long tenantId, UUID incidentId, UUID commandId) {
        requireTenant(tenantId);
        EmergencyHandoffRow row = repository.handoff(tenantId, incidentId, commandId)
                .orElseThrow(() -> notFound("The emergency handoff was not found."));
        CommandRow command = incidents.command(tenantId, incidentId, commandId).orElseThrow();
        return receipt(row, command, false);
    }

    @Transactional
    public EmergencyHandoffReceipt reconcile(
            long tenantId,
            long actorId,
            UUID incidentId,
            UUID commandId,
            String idempotencyKey,
            ReconcileEmergencyHandoffRequest request,
            String correlationId) {
        requireActor(tenantId, actorId);
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        String key = key(idempotencyKey);
        String fingerprint = fingerprint(RECONCILE, incidentId, commandId, request);
        incidents.lockCommandKey(tenantId, actorId, RECONCILE, key);
        CommandRow duplicate = incidents.command(tenantId, actorId, RECONCILE, key).orElse(null);
        if (duplicate != null) {
            verifyFingerprint(duplicate, fingerprint);
            return handoff(tenantId, incidentId, commandId);
        }
        EmergencyHandoffRow row = repository.handoff(tenantId, incidentId, commandId)
                .orElseThrow(() -> notFound("The emergency handoff was not found."));
        if (row.state() != CommandState.RESULT_UNKNOWN) return handoff(tenantId, incidentId, commandId);
        OffsetDateTime now = now();
        String correlation = correlation(correlationId);
        CommandRow reconcile = incidents.insertCommand(tenantId, actorId, incidentId, RECONCILE,
                key, fingerprint, request.reason().trim(), correlation,
                "/v1/admin/workplace/safety/incidents/" + incidentId
                        + "/emergency-handoffs/" + commandId, now);
        SafetyEmergencyHandoffProvider.Result result;
        try {
            result = provider.ready(row.providerCode(), row.providerConfigurationVersion())
                    ? provider.lookup(row.handoffId(), tenantId, row.providerCode(),
                            row.providerConfigurationVersion(), row.providerOperationReference())
                    : new SafetyEmergencyHandoffProvider.Result(CommandState.RESULT_UNKNOWN,
                            "STATUS_LOOKUP_NOT_CONFIGURED", row.providerOperationReference(), null);
        } catch (RuntimeException unavailable) {
            result = new SafetyEmergencyHandoffProvider.Result(CommandState.RESULT_UNKNOWN,
                    "STATUS_LOOKUP_UNAVAILABLE", row.providerOperationReference(), null);
        }
        repository.applyProviderResult(tenantId, row.commandId(), row.handoffId(), result, now());
        incidents.completeLocalCommand(tenantId, reconcile.id(), "EMERGENCY_HANDOFF_RECONCILED", now());
        repository.audit(tenantId, incidentId, actorId, "safety.emergency-handoff.reconciled",
                "EMERGENCY_HANDOFF", row.handoffId(), correlation,
                Map.of("state", result.state().name(), "resultCode", result.resultCode(),
                        "providerLookupOnly", true), now());
        return handoff(tenantId, incidentId, commandId);
    }

    private List<EmergencyContactView> views(long tenantId, List<EmergencyContactRow> rows) {
        ProviderSnapshot snapshot = truth(tenantId);
        return rows.stream().map(row -> view(tenantId, row, snapshot)).toList();
    }

    private EmergencyContactView view(
            long tenantId, EmergencyContactRow row, ProviderSnapshot snapshot) {
        EmergencyContactProviderState state;
        String providerCode = null;
        Long providerVersion = null;
        String telUri = null;
        if (!row.active()) {
            state = EmergencyContactProviderState.NOT_CONFIGURED;
        } else if (row.actionMode() == EmergencyContactActionMode.TEL_URI) {
            boolean ready = row.directTelAllowed() && validTel(row.telUri());
            state = ready ? EmergencyContactProviderState.READY
                    : EmergencyContactProviderState.NOT_CONFIGURED;
            telUri = ready ? row.telUri() : null;
        } else {
            state = snapshot.state();
            providerCode = snapshot.providerCode();
            providerVersion = snapshot.configurationVersion();
            if (state == EmergencyContactProviderState.READY
                    && (providerCode == null || providerVersion == null
                    || !provider.ready(providerCode, providerVersion))) {
                state = EmergencyContactProviderState.NOT_CONFIGURED;
            }
        }
        return new EmergencyContactView(row.contactId(), row.kind(), row.displayNameKo(),
                row.displayNameEn(), row.actionMode(), telUri, row.directTelAllowed(), state,
                providerCode, providerVersion, row.active(), row.sortOrder(), row.version(), now());
    }

    private ProviderSnapshot truth(long tenantId) {
        ConnectorTruth truth = connectors.truth(tenantId).stream()
                .filter(value -> value.kind() == ConnectorKind.EMERGENCY_119)
                .findFirst().orElse(null);
        if (truth == null) return new ProviderSnapshot(
                EmergencyContactProviderState.NOT_CONFIGURED, null, null, null);
        return new ProviderSnapshot(EmergencyContactProviderState.valueOf(truth.state().name()),
                truth.providerCode(), truth.configurationVersion() > 0
                        ? truth.configurationVersion() : null, truth.evidenceReference());
    }

    private static EmergencyHandoffPreview preview(EmergencyHandoffPreviewRow row) {
        return new EmergencyHandoffPreview(row.previewId(), row.incidentId(), row.contactId(),
                row.expectedIncidentVersion(), row.expectedContactVersion(), row.providerState(),
                row.providerCode(), row.providerConfigurationVersion(), row.eligible(),
                List.of("EXTERNAL_PROVIDER_HANDOFF", "AUDIT_EVIDENCE_RECORDED",
                        "NO_PERSONAL_CONTACT_DATA_SENT"),
                row.limitations(), row.expiresAt(), row.createdAt());
    }

    private static EmergencyHandoffReceipt receipt(
            EmergencyHandoffRow row, CommandRow command, boolean replay) {
        return new EmergencyHandoffReceipt(row.handoffId(), row.commandId(), row.previewId(),
                row.incidentId(), row.contactId(), row.state(), row.resultCode(),
                row.providerOperationReference(), row.providerEvidenceReference(), row.version(),
                "/v1/admin/workplace/safety/incidents/" + row.incidentId()
                        + "/emergency-handoffs/" + row.commandId(),
                command.correlationId(), command.acceptedAt(), row.completedAt(), row.updatedAt(),
                replay);
    }

    private static CommandReceipt receipt(CommandRow row, boolean replay) {
        return new CommandReceipt(row.id(), row.state(), row.statusHref(), replay,
                row.correlationId(), row.acceptedAt());
    }

    private String fingerprint(String command, Object... values) {
        try {
            return SafetyRepositorySupport.sha256(command + ":" + mapper.writeValueAsString(values));
        } catch (JsonProcessingException invalid) {
            throw invalid("The emergency-contact command is invalid.");
        }
    }

    private static UUID deterministicHandoffId(long tenantId, long actorId, String key) {
        return UUID.nameUUIDFromBytes(("safety-emergency-handoff:" + tenantId + ":" + actorId
                + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static void validateConfiguration(EmergencyContactConfigurationRequest request) {
        if (!request.explicitConfirmation()) throw invalid("Explicit confirmation is required.");
        if (request.actionMode() == EmergencyContactActionMode.TEL_URI) {
            if (!validTel(request.telUri())) throw invalid("A safe tel URI is required.");
        } else if (request.telUri() != null || request.directTelAllowed()) {
            throw invalid("Governed provider handoffs cannot expose a direct tel URI.");
        }
    }

    private static boolean validTel(String value) {
        return value != null && value.matches("^tel:\\+[1-9][0-9]{6,14}$");
    }

    private static void verifyFingerprint(CommandRow row, String expected) {
        if (!row.fingerprint().equals(expected)) {
            throw conflict("The Idempotency-Key was used for a different request.");
        }
    }

    private static String key(String value) {
        if (value == null || !value.matches("^[!-~]{1,160}$")) {
            throw invalid("A bounded visible-ASCII Idempotency-Key is required.");
        }
        return value;
    }

    private static String correlation(String value) {
        try {
            return SafetyRepositorySupport.correlation(value);
        } catch (IllegalArgumentException invalid) {
            throw invalid("X-Correlation-ID must be at most 160 characters.");
        }
    }

    private static boolean active(IncidentRow row) {
        return row.state() == IncidentState.ACTIVE || row.state() == IncidentState.CLOSURE_PENDING;
    }

    private static void requireActor(long tenantId, long actorId) {
        requireTenant(tenantId);
        if (actorId <= 0) throw invalid("A positive actor identifier is required.");
    }

    private static void requireTenant(long tenantId) {
        if (tenantId <= 0) throw invalid("A positive tenant identifier is required.");
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static BaseException invalid(String message) {
        return new BaseException(ErrorCode.INVALID_INPUT_VALUE, message);
    }

    private static BaseException conflict(String message) {
        return new BaseException(ErrorCode.OBJECT_VERSION_CONFLICT, message);
    }

    private static BaseException notFound(String message) {
        return new BaseException(ErrorCode.NOT_FOUND, message);
    }

    private record ProviderSnapshot(
            EmergencyContactProviderState state,
            String providerCode,
            Long configurationVersion,
            String evidenceReference) { }
}
