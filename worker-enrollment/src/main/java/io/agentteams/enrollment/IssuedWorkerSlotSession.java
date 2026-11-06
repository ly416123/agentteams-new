package io.agentteams.enrollment;

import java.time.Instant;
import java.util.UUID;

public record IssuedWorkerSlotSession(UUID sessionId, UUID slotId, String accessToken, Instant expiresAt) { }
