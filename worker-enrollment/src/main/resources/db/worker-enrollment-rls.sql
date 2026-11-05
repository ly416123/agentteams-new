-- The app role is deliberately not the owner of either table and has no BYPASSRLS.
ALTER TABLE agents ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_sessions ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE agents FROM worker_enrollment;
REVOKE ALL ON TABLE agent_sessions FROM worker_enrollment;
GRANT SELECT (id, phase, metadata) ON TABLE agents TO worker_enrollment;
GRANT SELECT (id, agent_id, revoked_at) ON TABLE agent_sessions TO worker_enrollment;
GRANT INSERT (id, agent_id, token_sha256, expires_at, revoked_at, created_at)
    ON TABLE agent_sessions TO worker_enrollment;
GRANT UPDATE (revoked_at) ON TABLE agent_sessions TO worker_enrollment;

DROP POLICY IF EXISTS worker_enrollment_agents_scope ON agents;
CREATE POLICY worker_enrollment_agents_scope ON agents
    FOR SELECT TO worker_enrollment
    USING (
        metadata #>> '{scope,tenant}' = NULLIF(current_setting('agentteams.tenant_id', true), '')
        AND metadata #>> '{scope,project}' = NULLIF(current_setting('agentteams.project_id', true), '')
        AND metadata #>> '{scope,team}' = NULLIF(current_setting('agentteams.team_id', true), '')
    );

DROP POLICY IF EXISTS worker_enrollment_sessions_scope ON agent_sessions;
CREATE POLICY worker_enrollment_sessions_scope ON agent_sessions
    TO worker_enrollment
    USING (EXISTS (SELECT 1 FROM agents WHERE agents.id = agent_sessions.agent_id))
    WITH CHECK (EXISTS (SELECT 1 FROM agents WHERE agents.id = agent_sessions.agent_id));
