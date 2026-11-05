package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LeaseGuardedInputStreamTest {
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();

    @AfterEach
    void stopExecutors() throws Exception {
        scheduler.shutdownNow();
        reader.shutdownNow();
        scheduler.awaitTermination(2, TimeUnit.SECONDS);
        reader.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void closesBlockedUpstreamReadWhenAttemptIsRevoked() throws Exception {
        AuthenticatedWorker worker = new AuthenticatedWorker(
                UUID.fromString("88000000-0000-0000-0000-000000000005"),
                UUID.fromString("88000000-0000-0000-0000-000000000001"),
                "tenant-a", "project-a", "team-a", "file:credential");
        ActiveAttemptCandidate active = new ActiveAttemptCandidate(worker.agentId(), worker.tenantId(),
                worker.projectId(), worker.teamId(),
                UUID.fromString("88000000-0000-0000-0000-000000000002"),
                UUID.fromString("88000000-0000-0000-0000-000000000003"),
                UUID.fromString("88000000-0000-0000-0000-000000000004"), NOW.plusSeconds(60), true, true, true);
        AtomicInteger checks = new AtomicInteger();
        AttemptExecutionContextResolver contexts = new AttemptExecutionContextResolver(ignored ->
                checks.incrementAndGet() == 1 ? List.of(active) : List.of(), Clock.fixed(NOW, ZoneOffset.UTC));
        AttemptExecutionContext original = contexts.resolve(worker);
        BlockingInputStream upstream = new BlockingInputStream();
        Semaphore permits = acquiredPermit();
        LeaseGuardedInputStream guarded = new LeaseGuardedInputStream(upstream, activeAuthenticator(), contexts,
                worker, original, Clock.fixed(NOW, ZoneOffset.UTC), scheduler, permits, 10);

        var pendingRead = reader.submit(() -> guarded.read());

        assertThat(upstream.awaitClosed()).isTrue();
        assertThatThrownBy(pendingRead::get)
                .hasCauseInstanceOf(IOException.class);
        assertThat(checks.get()).isGreaterThanOrEqualTo(2);
        assertThat(guarded.isInvalidated()).isTrue();
    }

    @Test
    void closesBlockedUpstreamReadWhenProxyCredentialIsRevoked() throws Exception {
        AuthenticatedWorker worker = worker();
        ActiveAttemptCandidate active = activeCandidate(worker);
        AtomicInteger checks = new AtomicInteger();
        AttemptExecutionContextResolver contexts = new AttemptExecutionContextResolver(ignored -> {
            checks.incrementAndGet();
            return List.of(active);
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        AttemptExecutionContext original = contexts.resolve(worker);
        ModelProxyAuthenticator revoked = new ModelProxyAuthenticator() {
            @Override
            public Optional<AuthenticatedWorker> authenticate(String token) {
                return Optional.of(worker);
            }

            @Override
            public Optional<AuthenticatedWorker> revalidate(AuthenticatedWorker authenticated) {
                return Optional.empty();
            }
        };
        BlockingInputStream upstream = new BlockingInputStream();
        Semaphore permits = acquiredPermit();
        LeaseGuardedInputStream guarded = new LeaseGuardedInputStream(upstream, revoked, contexts,
                worker, original, Clock.fixed(NOW, ZoneOffset.UTC), scheduler, permits, 10);

        var pendingRead = reader.submit(() -> guarded.read());

        assertThat(upstream.awaitClosed()).isTrue();
        assertThatThrownBy(pendingRead::get).hasCauseInstanceOf(IOException.class);
        assertThat(checks).hasValue(1);
        assertThat(guarded.isInvalidated()).isTrue();
    }

    @Test
    void closesBlockedUpstreamReadWhenAttemptRepositoryFails() throws Exception {
        AuthenticatedWorker worker = worker();
        ActiveAttemptCandidate active = activeCandidate(worker);
        AtomicInteger checks = new AtomicInteger();
        AttemptExecutionContextResolver contexts = new AttemptExecutionContextResolver(ignored -> {
            if (checks.incrementAndGet() == 1) return List.of(active);
            throw new IllegalStateException("database unavailable");
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        AttemptExecutionContext original = contexts.resolve(worker);
        BlockingInputStream upstream = new BlockingInputStream();
        LeaseGuardedInputStream guarded = new LeaseGuardedInputStream(upstream, activeAuthenticator(), contexts,
                worker, original, Clock.fixed(NOW, ZoneOffset.UTC), scheduler, acquiredPermit(), 10);

        var pendingRead = reader.submit(() -> guarded.read());

        assertThat(upstream.awaitClosed()).isTrue();
        assertThatThrownBy(pendingRead::get).hasCauseInstanceOf(IOException.class);
        assertThat(checks).hasValue(2);
        assertThat(guarded.isInvalidated()).isTrue();
    }

    @Test
    void releasesActiveStreamPermitOnClose() throws Exception {
        AuthenticatedWorker worker = worker();
        AttemptExecutionContextResolver contexts = new AttemptExecutionContextResolver(
                ignored -> List.of(activeCandidate(worker)), Clock.fixed(NOW, ZoneOffset.UTC));
        AttemptExecutionContext original = contexts.resolve(worker);
        Semaphore capacity = new Semaphore(1);
        assertThat(capacity.tryAcquire()).isTrue();
        LeaseGuardedInputStream first = new LeaseGuardedInputStream(InputStream.nullInputStream(),
                activeAuthenticator(), contexts, worker, original, Clock.fixed(NOW, ZoneOffset.UTC),
                scheduler, capacity, 10_000);

        assertThat(capacity.availablePermits()).isZero();

        first.close();
        assertThat(capacity.availablePermits()).isEqualTo(1);
        assertThat(capacity.tryAcquire()).isTrue();
        try (LeaseGuardedInputStream replacement = new LeaseGuardedInputStream(InputStream.nullInputStream(),
                activeAuthenticator(), contexts, worker, original, Clock.fixed(NOW, ZoneOffset.UTC),
                scheduler, capacity, 10_000)) {
            assertThat(replacement.isInvalidated()).isFalse();
        }
        assertThat(capacity.availablePermits()).isEqualTo(1);
    }

    private static Semaphore acquiredPermit() {
        Semaphore permits = new Semaphore(1);
        assertThat(permits.tryAcquire()).isTrue();
        return permits;
    }

    private static ModelProxyAuthenticator activeAuthenticator() {
        return new ModelProxyAuthenticator() {
            @Override
            public Optional<AuthenticatedWorker> authenticate(String token) {
                return Optional.empty();
            }

            @Override
            public Optional<AuthenticatedWorker> revalidate(AuthenticatedWorker worker) {
                return Optional.of(worker);
            }
        };
    }

    private static AuthenticatedWorker worker() {
        return new AuthenticatedWorker(
                UUID.fromString("88000000-0000-0000-0000-000000000005"),
                UUID.fromString("88000000-0000-0000-0000-000000000001"),
                "tenant-a", "project-a", "team-a", "file:credential");
    }

    private static ActiveAttemptCandidate activeCandidate(AuthenticatedWorker worker) {
        return new ActiveAttemptCandidate(worker.agentId(), worker.tenantId(), worker.projectId(), worker.teamId(),
                UUID.fromString("88000000-0000-0000-0000-000000000002"),
                UUID.fromString("88000000-0000-0000-0000-000000000003"),
                UUID.fromString("88000000-0000-0000-0000-000000000004"), NOW.plusSeconds(60), true, true, true);
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                if (!closed.await(3, TimeUnit.SECONDS)) throw new IOException("test read timed out");
                throw new IOException("upstream stream closed");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", interrupted);
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }

        boolean awaitClosed() throws InterruptedException {
            return closed.await(2, TimeUnit.SECONDS);
        }
    }
}
