package com.dwp.services.time.commandreceipt;

import com.dwp.services.time.api.HrisTimeApiException;
import com.dwp.services.time.api.HrisTimeProblemCode;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves exactly one command-receipt owner without first-provider-wins behavior. */
@Component
public final class CommandReceiptProviderRegistry implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(CommandReceiptProviderRegistry.class);
    private static final Pattern PROVIDER_ID = Pattern.compile("[a-z][a-z0-9.-]{0,63}");

    private final List<RegisteredProvider> providers;
    private final CommandReceiptAuditSink audit;
    private final Duration timeout;
    private final ExecutorService executor;
    private final boolean ownsExecutor;

    @Autowired
    public CommandReceiptProviderRegistry(
            List<CommandReceiptProvider> providers,
            CommandReceiptAuditSink audit,
            @Value("${dwp.hris.time.command-receipt.provider-timeout:PT2S}")
            Duration timeout) {
        this(providers, audit, timeout, Executors.newVirtualThreadPerTaskExecutor(), true);
    }

    public CommandReceiptProviderRegistry(
            List<CommandReceiptProvider> providers,
            CommandReceiptAuditSink audit,
            Duration timeout,
            ExecutorService executor) {
        this(providers, audit, timeout, executor, false);
    }

    private CommandReceiptProviderRegistry(
            List<CommandReceiptProvider> providers,
            CommandReceiptAuditSink audit,
            Duration timeout,
            ExecutorService executor,
            boolean ownsExecutor) {
        Objects.requireNonNull(providers, "providers");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("provider timeout must be positive");
        }
        Set<String> identifiers = new HashSet<>();
        List<RegisteredProvider> registered = new ArrayList<>();
        for (CommandReceiptProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            String identifier = provider.providerId();
            if (identifier == null || !PROVIDER_ID.matcher(identifier).matches()) {
                throw new IllegalArgumentException("providerId is invalid");
            }
            if (!identifiers.add(identifier)) {
                throw new IllegalArgumentException("providerId must be unique: " + identifier);
            }
            registered.add(new RegisteredProvider(identifier, provider));
        }
        this.providers = List.copyOf(registered);
    }

    /**
     * Probes every provider, rejects ambiguity or partial authority, and reads only the unique owner.
     */
    public Object lookup(CommandReceiptLookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        List<ProbeResult> results = probeEveryProvider(lookup);
        List<RegisteredProvider> owners = results.stream()
                .filter(ProbeResult::owns)
                .map(ProbeResult::provider)
                .toList();
        if (owners.isEmpty()) {
            throw HrisTimeApiException.of(HrisTimeProblemCode.TARGET_NOT_DISCLOSED);
        }
        if (owners.size() != 1) {
            audit(lookup, CommandReceiptAuditEvent.Failure.MULTIPLE_OWNERS,
                    owners.stream().map(RegisteredProvider::identifier).toList());
            throw HrisTimeApiException.of(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE);
        }
        return readUniqueOwner(lookup, owners.getFirst());
    }

    private List<ProbeResult> probeEveryProvider(CommandReceiptLookup lookup) {
        if (providers.isEmpty()) {
            return List.of();
        }
        List<Callable<ProbeResult>> tasks = providers.stream()
                .<Callable<ProbeResult>>map(provider -> () ->
                        new ProbeResult(provider, provider.provider().owns(lookup)))
                .toList();
        List<Future<ProbeResult>> futures;
        try {
            futures = executor.invokeAll(tasks, timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            audit(lookup, CommandReceiptAuditEvent.Failure.REGISTRY_INTERRUPTED,
                    providerIdentifiers());
            throw HrisTimeApiException.authorityUnavailable(interrupted);
        } catch (RuntimeException unavailable) {
            audit(lookup, CommandReceiptAuditEvent.Failure.PROVIDER_LOOKUP_ERROR,
                    providerIdentifiers());
            throw HrisTimeApiException.authorityUnavailable(unavailable);
        }

        List<ProbeResult> results = new ArrayList<>(providers.size());
        List<String> failed = new ArrayList<>();
        boolean timedOut = false;
        for (int index = 0; index < futures.size(); index++) {
            Future<ProbeResult> future = futures.get(index);
            RegisteredProvider provider = providers.get(index);
            if (future.isCancelled()) {
                timedOut = true;
                failed.add(provider.identifier());
                continue;
            }
            try {
                results.add(future.get());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                audit(lookup, CommandReceiptAuditEvent.Failure.REGISTRY_INTERRUPTED,
                        providerIdentifiers());
                throw HrisTimeApiException.authorityUnavailable(interrupted);
            } catch (ExecutionException | CancellationException failedLookup) {
                failed.add(provider.identifier());
            }
        }
        if (!failed.isEmpty()) {
            audit(lookup, timedOut
                            ? CommandReceiptAuditEvent.Failure.PROVIDER_LOOKUP_TIMEOUT
                            : CommandReceiptAuditEvent.Failure.PROVIDER_LOOKUP_ERROR,
                    failed);
            throw HrisTimeApiException.of(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE);
        }
        return List.copyOf(results);
    }

    private Object readUniqueOwner(
            CommandReceiptLookup lookup, RegisteredProvider owner) {
        Future<Object> read;
        try {
            read = executor.submit(() -> owner.provider().receipt(lookup));
        } catch (RuntimeException unavailable) {
            audit(lookup, CommandReceiptAuditEvent.Failure.OWNER_READ_ERROR,
                    List.of(owner.identifier()));
            throw HrisTimeApiException.authorityUnavailable(unavailable);
        }
        try {
            Object receipt = read.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (receipt == null) {
                audit(lookup, CommandReceiptAuditEvent.Failure.OWNER_READ_ERROR,
                        List.of(owner.identifier()));
                throw HrisTimeApiException.of(HrisTimeProblemCode.AUTHORITY_UNAVAILABLE);
            }
            return receipt;
        } catch (TimeoutException timeoutFailure) {
            read.cancel(true);
            audit(lookup, CommandReceiptAuditEvent.Failure.OWNER_READ_TIMEOUT,
                    List.of(owner.identifier()));
            throw HrisTimeApiException.authorityUnavailable(timeoutFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            read.cancel(true);
            audit(lookup, CommandReceiptAuditEvent.Failure.REGISTRY_INTERRUPTED,
                    List.of(owner.identifier()));
            throw HrisTimeApiException.authorityUnavailable(interrupted);
        } catch (ExecutionException failedRead) {
            Throwable cause = failedRead.getCause();
            if (cause instanceof HrisTimeApiException expected) {
                throw expected;
            }
            audit(lookup, CommandReceiptAuditEvent.Failure.OWNER_READ_ERROR,
                    List.of(owner.identifier()));
            throw HrisTimeApiException.authorityUnavailable(cause);
        }
    }

    private List<String> providerIdentifiers() {
        return providers.stream().map(RegisteredProvider::identifier).toList();
    }

    private void audit(
            CommandReceiptLookup lookup,
            CommandReceiptAuditEvent.Failure failure,
            List<String> providerIds) {
        try {
            audit.record(new CommandReceiptAuditEvent(
                    lookup.tenantId(), lookup.receiptId(), lookup.correlationId(),
                    failure, providerIds));
        } catch (RuntimeException auditFailure) {
            log.error(
                    "Command-receipt failure audit could not be persisted; failure={} type={}",
                    failure,
                    auditFailure.getClass().getSimpleName());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private record RegisteredProvider(
            String identifier, CommandReceiptProvider provider) {
    }

    private record ProbeResult(RegisteredProvider provider, boolean owns) {
    }
}
