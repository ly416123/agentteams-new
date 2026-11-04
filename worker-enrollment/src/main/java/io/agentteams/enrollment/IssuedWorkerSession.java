package io.agentteams.enrollment;

import java.time.Instant;
import java.util.UUID;

public record IssuedWorkerSession(UUID sessionId, UUID agentId, String accessToken, Instant expiresAt) { }
