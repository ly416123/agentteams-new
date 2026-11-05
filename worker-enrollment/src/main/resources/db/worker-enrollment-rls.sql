-- Keep only the scope tuple needed by Enrollment. The general-purpose metadata JSON
-- remains unavailable to the Enrollment role.
CREATE TABLE IF NOT EXISTS worker_enrollment_agent_scopes (
    agent_id UUID PRIMARY KEY REFERENCES agents(id) ON DELETE CASCADE,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT NOT NULL
);

CREATE OR REPLACE FUNCTION worker_enrollment_sync_agent_scope()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
DECLARE
    agent_scope JSONB := NEW.metadata -> 'scope';
    tenant_value TEXT := NULLIF(BTRIM(agent_scope ->> 'tenant'), '');
    project_value TEXT := NULLIF(BTRIM(agent_scope ->> 'project'), '');
    team_value TEXT := NULLIF(BTRIM(agent_scope ->> 'team'), '');
BEGIN
    IF tenant_value IS NULL OR project_value IS NULL OR team_value IS NULL THEN
        DELETE FROM public.worker_enrollment_agent_scopes WHERE agent_id = NEW.id;
    ELSE
        INSERT INTO public.worker_enrollment_agent_scopes(agent_id, tenant_id, project_id, team_id)
        VALUES (NEW.id, tenant_value, project_value, team_value)
        ON CONFLICT (agent_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id,
                                             project_id = EXCLUDED.project_id,
                                             team_id = EXCLUDED.team_id;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS worker_enrollment_sync_agent_scope ON agents;
CREATE TRIGGER worker_enrollment_sync_agent_scope
    AFTER INSERT OR UPDATE OF metadata ON agents
    FOR EACH ROW EXECUTE FUNCTION worker_enrollment_sync_agent_scope();

-- Backfill existing agents before Enrollment starts and remove scopes that no longer qualify.
DELETE FROM worker_enrollment_agent_scopes scoped
 USING agents agent
 WHERE agent.id = scoped.agent_id
   AND (NULLIF(BTRIM(agent.metadata #>> '{scope,tenant}'), '') IS NULL
        OR NULLIF(BTRIM(agent.metadata #>> '{scope,project}'), '') IS NULL
        OR NULLIF(BTRIM(agent.metadata #>> '{scope,team}'), '') IS NULL);

INSERT INTO worker_enrollment_agent_scopes(agent_id, tenant_id, project_id, team_id)
SELECT id,
       BTRIM(metadata #>> '{scope,tenant}'),
       BTRIM(metadata #>> '{scope,project}'),
       BTRIM(metadata #>> '{scope,team}')
  FROM agents
 WHERE NULLIF(BTRIM(metadata #>> '{scope,tenant}'), '') IS NOT NULL
   AND NULLIF(BTRIM(metadata #>> '{scope,project}'), '') IS NOT NULL
   AND NULLIF(BTRIM(metadata #>> '{scope,team}'), '') IS NOT NULL
ON CONFLICT (agent_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id,
                                     project_id = EXCLUDED.project_id,
                                     team_id = EXCLUDED.team_id;

ALTER TABLE worker_enrollment_agent_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE agents ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_sessions ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE worker_enrollment_agent_scopes FROM worker_enrollment;
REVOKE ALL ON TABLE agents FROM worker_enrollment;
REVOKE ALL ON TABLE agent_sessions FROM worker_enrollment;

GRANT SELECT (agent_id, tenant_id, project_id, team_id)
    ON TABLE worker_enrollment_agent_scopes TO worker_enrollment;
GRANT SELECT (id, phase) ON TABLE agents TO worker_enrollment;
GRANT SELECT (id, agent_id, revoked_at) ON TABLE agent_sessions TO worker_enrollment;
GRANT INSERT (id, agent_id, token_sha256, expires_at, revoked_at, created_at)
    ON TABLE agent_sessions TO worker_enrollment;
GRANT UPDATE (revoked_at) ON TABLE agent_sessions TO worker_enrollment;

DROP POLICY IF EXISTS worker_enrollment_agent_scopes_scope ON worker_enrollment_agent_scopes;
CREATE POLICY worker_enrollment_agent_scopes_scope ON worker_enrollment_agent_scopes
    FOR SELECT TO worker_enrollment
    USING (
        tenant_id = NULLIF(current_setting('agentteams.tenant_id', true), '')
        AND project_id = NULLIF(current_setting('agentteams.project_id', true), '')
        AND team_id = NULLIF(current_setting('agentteams.team_id', true), '')
    );

DROP POLICY IF EXISTS worker_enrollment_agents_scope ON agents;
CREATE POLICY worker_enrollment_agents_scope ON agents
    FOR SELECT TO worker_enrollment
    USING (EXISTS (
        SELECT 1 FROM worker_enrollment_agent_scopes scoped
         WHERE scoped.agent_id = agents.id
    ));

DROP POLICY IF EXISTS worker_enrollment_sessions_scope ON agent_sessions;
CREATE POLICY worker_enrollment_sessions_scope ON agent_sessions
    TO worker_enrollment
    USING (EXISTS (
        SELECT 1 FROM worker_enrollment_agent_scopes scoped
         WHERE scoped.agent_id = agent_sessions.agent_id
    ))
    WITH CHECK (EXISTS (
        SELECT 1 FROM worker_enrollment_agent_scopes scoped
         WHERE scoped.agent_id = agent_sessions.agent_id
    ));
