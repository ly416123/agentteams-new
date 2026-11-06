package io.agentteams.enrollment;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Uses narrowly scoped database functions; the Enrollment login cannot read slot/session tables directly. */
@Repository
public class JdbcWorkerSlotSessionRepository implements WorkerSlotSessionRepository {
    private final JdbcTemplate jdbc;

    public JdbcWorkerSlotSessionRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<WorkerSlotRecord> findSlot(UUID slotId) {
        return jdbc.query("""
                SELECT ?::uuid AS id, 'ACTIVE'::text AS status
                 WHERE public.worker_enrollment_worker_slot_is_active(?)
                """, JdbcWorkerSlotSessionRepository::mapSlot, slotId, slotId).stream().findFirst();
    }

    @Override
    public void insertSession(WorkerSlotSessionRecord session) {
        Boolean inserted = jdbc.queryForObject("""
                SELECT public.worker_enrollment_create_worker_slot_session(?, ?, ?, ?, ?, ?)
                """, Boolean.class, session.id(), session.slotId(), session.subject(), session.tokenSha256(),
                Timestamp.from(session.expiresAt()), Timestamp.from(session.createdAt()));
        if (!Boolean.TRUE.equals(inserted)) {
            throw new WorkerEnrollmentException("Worker slot is not available");
        }
    }

    @Override
    public Optional<WorkerSlotSessionOwnership> findSessionForUpdate(UUID slotId, UUID sessionId) {
        return jdbc.query("""
                SELECT slot_id, revoked
                  FROM public.worker_enrollment_worker_slot_session_for_update(?, ?)
                """, JdbcWorkerSlotSessionRepository::mapOwnership, slotId, sessionId).stream().findFirst();
    }

    @Override
    public boolean revoke(UUID slotId, UUID sessionId, Instant revokedAt) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT public.worker_enrollment_revoke_worker_slot_session(?, ?, ?)
                """, Boolean.class, slotId, sessionId, Timestamp.from(revokedAt)));
    }

    private static WorkerSlotRecord mapSlot(ResultSet rs, int row) throws SQLException {
        return new WorkerSlotRecord(rs.getObject("id", UUID.class), rs.getString("status"));
    }

    private static WorkerSlotSessionOwnership mapOwnership(ResultSet rs, int row) throws SQLException {
        return new WorkerSlotSessionOwnership(rs.getObject("slot_id", UUID.class), rs.getBoolean("revoked"));
    }
}
