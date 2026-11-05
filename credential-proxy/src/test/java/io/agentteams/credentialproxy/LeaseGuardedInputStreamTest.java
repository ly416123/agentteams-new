package io.agentteams.credentialproxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
        LeaseGuardedInputStream guarded = new LeaseGuardedInputStream(upstream, contexts, worker, original,
                Clock.fixed(NOW, ZoneOffset.UTC), scheduler, 10);

        var pendingRead = reader.submit(() -> guarded.read());

        assertThat(upstream.awaitClosed()).isTrue();
        assertThatThrownBy(pendingRead::get)
                .hasCauseInstanceOf(IOException.class);
        assertThat(checks.get()).isGreaterThanOrEqualTo(2);
        assertThat(guarded.isInvalidated()).isTrue();
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
