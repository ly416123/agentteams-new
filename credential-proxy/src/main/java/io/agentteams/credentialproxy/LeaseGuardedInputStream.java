package io.agentteams.credentialproxy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Closes an in-flight provider response when its authorizing Attempt or Lease stops being active. */
final class LeaseGuardedInputStream extends InputStream {
    private final InputStream upstream;
    private final AttemptExecutionContextResolver contexts;
    private final AuthenticatedWorker worker;
    private final AttemptExecutionContext original;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private final ScheduledFuture<?> monitor;

    LeaseGuardedInputStream(InputStream upstream, AttemptExecutionContextResolver contexts,
            AuthenticatedWorker worker, AttemptExecutionContext original, Clock clock,
            ScheduledExecutorService scheduler, long pollIntervalMillis) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.original = Objects.requireNonNull(original, "original");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(scheduler, "scheduler");
        if (pollIntervalMillis < 1) throw new IllegalArgumentException("poll interval must be positive");
        this.monitor = scheduler.scheduleWithFixedDelay(this::checkAuthorization,
                pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    boolean isInvalidated() {
        return invalidated.get();
    }

    @Override
    public int read() throws IOException {
        ensureAuthorized();
        return upstream.read();
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        ensureAuthorized();
        return upstream.read(bytes, offset, length);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            monitor.cancel(false);
            upstream.close();
        }
    }

    private void checkAuthorization() {
        if (closed.get() || invalidated.get()) return;
        try {
            if (!clock.instant().isBefore(original.expiresAt())
                    || !original.sameExecutionAs(contexts.resolve(worker))) {
                invalidate();
            }
        } catch (RuntimeException unavailableOrDenied) {
            invalidate();
        }
    }

    private void invalidate() {
        if (invalidated.compareAndSet(false, true)) {
            closed.set(true);
            monitor.cancel(false);
            try {
                upstream.close();
            } catch (IOException ignored) {
                // The stream is already invalid; closing it is best-effort to interrupt the provider read.
            }
        }
    }

    private void ensureAuthorized() throws IOException {
        if (invalidated.get()) throw new IOException("Attempt authorization is no longer active");
        if (closed.get()) throw new IOException("stream is closed");
    }
}
