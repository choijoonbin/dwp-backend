package com.dwp.services.platform.hrisconfiguration.home;

import com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.dwp.platform.contracts.hris.home.HrisHomeContributionMaterializationV1.FailureCode;
import static com.dwp.services.platform.hrisconfiguration.home.HrisHomeCompositionPorts.*;

/** Executes owner GETs concurrently behind one shared, bounded deadline. */
final class HrisHomeCompositionCoordinator implements AutoCloseable {

    private final OwnerMaterializationClient owners;
    private final HrisHomeCompositionRuntimeControls controls;
    private final ExecutorService executor;

    HrisHomeCompositionCoordinator(
            OwnerMaterializationClient owners,
            HrisHomeCompositionRuntimeControls controls) {
        this.owners = Objects.requireNonNull(owners, "owners");
        this.controls = Objects.requireNonNull(controls, "controls");
        this.executor = new ThreadPoolExecutor(
                controls.maxOwnerConcurrency(), controls.maxOwnerConcurrency(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), task -> {
                    Thread thread = new Thread(task, "hris-home-owner-materializer");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    List<OwnerResult> materialize(List<OwnerCall> calls) {
        Objects.requireNonNull(calls, "calls");
        Map<OwnerCall, CompletableFuture<TimedMaterialization>> futures =
                new LinkedHashMap<>();
        long deadline = System.nanoTime() + controls.ownerTimeout().toNanos();
        for (OwnerCall call : calls) {
            if (call == null || futures.containsKey(call)) {
                throw new IllegalArgumentException("Home owner calls must be unique and non-null");
            }
            try {
                futures.put(call, CompletableFuture.supplyAsync(
                        () -> new TimedMaterialization(
                                owners.materialize(call.request()), System.nanoTime()), executor));
            } catch (RejectedExecutionException rejected) {
                futures.put(call, CompletableFuture.failedFuture(
                        new OwnerMaterializationException(
                                FailureCode.OWNER_UNAVAILABLE, true)));
            }
        }

        return calls.stream().map(call -> resolve(call, futures.get(call), deadline)).toList();
    }

    private OwnerResult resolve(
            OwnerCall call,
            CompletableFuture<TimedMaterialization> future,
            long deadline) {
        try {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 && !future.isDone()) throw new TimeoutException();
            TimedMaterialization timed = future.get(
                    Math.max(0, remaining), TimeUnit.NANOSECONDS);
            if (timed.completedAtNanos() > deadline) throw new TimeoutException();
            return OwnerResult.success(call, timed.value());
        } catch (TimeoutException exception) {
            future.cancel(true);
            return OwnerResult.failed(call, FailureCode.OWNER_TIMEOUT, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return OwnerResult.failed(call, FailureCode.OWNER_UNAVAILABLE, true);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof OwnerMaterializationException ownerFailure) {
                return OwnerResult.failed(
                        call, ownerFailure.code(), ownerFailure.retryable());
            }
            return OwnerResult.failed(call, FailureCode.OWNER_UNAVAILABLE, true);
        } catch (RuntimeException exception) {
            return OwnerResult.failed(call, FailureCode.OWNER_UNAVAILABLE, true);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    record OwnerCall(
            HrisHomeCompositionDtos.OwnerAuthority owner,
            OwnerRequest request) {
        OwnerCall {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(request, "request");
            if (owner.module() != request.candidate().module()) {
                throw new IllegalArgumentException("Home owner call module mismatch");
            }
        }
    }

    record OwnerResult(
            OwnerCall call,
            HrisHomeContributionMaterializationV1 materialization,
            FailureCode failureCode,
            boolean retryable) {
        OwnerResult {
            Objects.requireNonNull(call, "call");
            if ((materialization == null) == (failureCode == null)) {
                throw new IllegalArgumentException(
                        "Home owner result must contain exactly one outcome");
            }
        }

        static OwnerResult success(
                OwnerCall call,
                HrisHomeContributionMaterializationV1 materialization) {
            if (materialization == null) {
                return failed(call, FailureCode.OWNER_UNAVAILABLE, true);
            }
            return new OwnerResult(call, materialization, null, false);
        }

        static OwnerResult failed(
                OwnerCall call,
                FailureCode failureCode,
                boolean retryable) {
            return new OwnerResult(
                    call, null, Objects.requireNonNull(failureCode, "failureCode"), retryable);
        }
    }

    private record TimedMaterialization(
            HrisHomeContributionMaterializationV1 value,
            long completedAtNanos) {
    }
}
