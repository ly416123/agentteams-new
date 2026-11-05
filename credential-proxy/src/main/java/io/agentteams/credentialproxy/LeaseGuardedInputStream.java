package io.agentteams.credentialproxy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Closes an in-flight provider response when its authorizing Attempt or Lease stops being active. */
final class LeaseGuardedInputStream extends InputStream {
    private final InputStream upstream;
    private final ModelProxyAuthenticator authenticator;
    private final AttemptExecutionContextResolver contexts;
    private final AuthenticatedWorker worker;
    private final AttemptExecutionContext original;
    private final Clock clock;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean invalidated = new AtomicBoolean();
    private final AtomicBoolean permitReleased = new AtomicBoolean();
    private final Semaphore activeStreamPermits;
    private final ScheduledFuture<?> monitor;

    LeaseGuardedInputStream(InputStream upstream, ModelProxyAuthenticator authenticator,
            AttemptExecutionContextResolver contexts,
            AuthenticatedWorker worker, AttemptExecutionContext original, Clock clock,
            ScheduledExecutorService scheduler, Semaphore activeStreamPermits, long pollIntervalMillis) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.original = Objects.requireNonNull(original, "original");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(scheduler, "scheduler");
        this.activeStreamPermits = Objects.requireNonNull(activeStreamPermits, "activeStreamPermits");
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
            try {
                upstream.close();
            } finally {
                releasePermit();
            }
        }
    }

    private void checkAuthorization() {
        if (closed.get() || invalidated.get()) return;
        try {
            var currentWorker = authenticator.revalidate(worker);
            if (currentWorker.isEmpty() || !worker.equals(currentWorker.get())
                    || !clock.instant().isBefore(original.expiresAt())
                    || !original.sameExecutionAs(contexts.resolve(currentWorker.get()))) {
                invalidate();
            }
        } catch (RuntimeException unavailableOrDenied) {
            invalidate();
        }
    }

    private void invalidate() {
        if (invalidated.compareAndSet(false, true)) {
            if (closed.compareAndSet(false, true)) {
                monitor.cancel(false);
                try {
                    upstream.close();
                } catch (IOException ignored) {
                    // The stream is already invalid; closing it is best-effort to interrupt the provider read.
                } finally {
                    releasePermit();
                }
            }
        }
    }

    private void releasePermit() {
        if (permitReleased.compareAndSet(false, true)) activeStreamPermits.release();
    }

    private void ensureAuthorized() throws IOException {
        if (invalidated.get()) throw new IOException("Attempt authorization is no longer active");
        if (closed.get()) throw new IOException("stream is closed");
    }
}
