-- Keep only the scope tuple needed by Enrollment. The general-purpose metadata JSON
-- remains unavailable to the Enrollment role.
CREATE TABLE IF NOT EXISTS worker_enrollment_agent_scopes (
    agent_id UUID PRIMARY KEY REFERENCES agents(id) ON DELETE CASCADE,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS agent_sessions_id_agent_id_key
    ON agent_sessions (id, agent_id);

CREATE TABLE IF NOT EXISTS worker_model_proxy_credentials (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    agent_id UUID NOT NULL,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT NOT NULL,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    model_credential_ref TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT worker_model_proxy_ref_valid CHECK (
        length(model_credential_ref) BETWEEN 1 AND 256
        AND length(btrim(model_credential_ref)) = length(model_credential_ref)
        AND model_credential_ref !~ '[[:cntrl:]]'
    ),
    CONSTRAINT worker_model_proxy_session_agent_fk
        FOREIGN KEY (session_id, agent_id) REFERENCES agent_sessions(id, agent_id) ON DELETE CASCADE,
    CONSTRAINT worker_model_proxy_session_unique UNIQUE (session_id)
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_enrollment_scope_owner') THEN
        CREATE ROLE worker_enrollment_scope_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END;
$$;
ALTER ROLE worker_enrollment_scope_owner
    WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER TABLE worker_enrollment_agent_scopes OWNER TO worker_enrollment_scope_owner;

CREATE OR REPLACE FUNCTION worker_enrollment_sync_agent_scope()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    agent_scope JSONB := NEW.metadata -> 'scope';
    tenant_value TEXT := NULLIF(BTRIM(agent_scope ->> 'tenant'), '');
    project_value TEXT := NULLIF(BTRIM(agent_scope ->> 'project'), '');
    team_value TEXT := NULLIF(BTRIM(agent_scope ->> 'team'), '');
    scope_changed BOOLEAN := FALSE;
    affected_rows BIGINT := 0;
BEGIN
    IF tenant_value IS NULL OR project_value IS NULL OR team_value IS NULL THEN
        DELETE FROM public.worker_enrollment_agent_scopes WHERE agent_id = NEW.id;
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        scope_changed := affected_rows > 0;
    ELSE
        INSERT INTO public.worker_enrollment_agent_scopes(agent_id, tenant_id, project_id, team_id)
        VALUES (NEW.id, tenant_value, project_value, team_value)
        ON CONFLICT (agent_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id,
                                             project_id = EXCLUDED.project_id,
                                             team_id = EXCLUDED.team_id
        WHERE (worker_enrollment_agent_scopes.tenant_id,
               worker_enrollment_agent_scopes.project_id,
               worker_enrollment_agent_scopes.team_id)
              IS DISTINCT FROM (EXCLUDED.tenant_id, EXCLUDED.project_id, EXCLUDED.team_id);
        GET DIAGNOSTICS affected_rows = ROW_COUNT;
        scope_changed := affected_rows > 0;
    END IF;

    IF scope_changed THEN
        UPDATE public.agent_sessions
           SET revoked_at = COALESCE(revoked_at, statement_timestamp())
         WHERE agent_id = NEW.id AND revoked_at IS NULL;
        UPDATE public.worker_model_proxy_credentials
           SET revoked_at = COALESCE(revoked_at, statement_timestamp())
         WHERE agent_id = NEW.id AND revoked_at IS NULL;
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION worker_enrollment_sync_agent_scope() OWNER TO worker_enrollment_scope_owner;
REVOKE ALL ON FUNCTION worker_enrollment_sync_agent_scope() FROM PUBLIC;

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
ALTER TABLE worker_model_proxy_credentials ENABLE ROW LEVEL SECURITY;

-- Preserve the Control Plane's current application-layer authorization model
-- after it stops connecting as PostgreSQL superuser. These policies intentionally
-- allow the runtime role to access all rows and are not tenant isolation.
DROP POLICY IF EXISTS control_plane_runtime_agents ON agents;
CREATE POLICY control_plane_runtime_agents ON agents
    FOR ALL TO agentteams_runtime
    USING (true)
    WITH CHECK (true);

DROP POLICY IF EXISTS control_plane_runtime_sessions ON agent_sessions;
CREATE POLICY control_plane_runtime_sessions ON agent_sessions
    FOR ALL TO agentteams_runtime
    USING (true)
    WITH CHECK (true);

REVOKE ALL ON TABLE worker_enrollment_agent_scopes FROM worker_enrollment;
REVOKE ALL ON TABLE agents FROM worker_enrollment;
REVOKE ALL ON TABLE agent_sessions FROM worker_enrollment;
REVOKE ALL ON TABLE worker_model_proxy_credentials FROM worker_enrollment;

GRANT SELECT (agent_id, tenant_id, project_id, team_id)
    ON TABLE worker_enrollment_agent_scopes TO worker_enrollment;
GRANT SELECT (id, phase) ON TABLE agents TO worker_enrollment;
GRANT SELECT (id, agent_id, expires_at, revoked_at) ON TABLE agent_sessions TO worker_enrollment;
GRANT INSERT (id, agent_id, token_sha256, expires_at, revoked_at, created_at)
    ON TABLE agent_sessions TO worker_enrollment;
GRANT UPDATE (revoked_at) ON TABLE agent_sessions TO worker_enrollment;
GRANT INSERT (id, session_id, agent_id, tenant_id, project_id, team_id, token_sha256,
              model_credential_ref, expires_at, created_at)
    ON TABLE worker_model_proxy_credentials TO worker_enrollment;
GRANT SELECT (agent_id, session_id, revoked_at)
    ON TABLE worker_model_proxy_credentials TO worker_enrollment;
GRANT UPDATE (revoked_at) ON TABLE worker_model_proxy_credentials TO worker_enrollment;

-- The SECURITY DEFINER scope trigger gets only the columns needed to revoke
-- credentials. Its policies intentionally cannot expose or mutate other rows.
GRANT SELECT (agent_id), UPDATE (revoked_at) ON TABLE agent_sessions
    TO worker_enrollment_scope_owner;
GRANT SELECT (agent_id), UPDATE (revoked_at) ON TABLE worker_model_proxy_credentials
    TO worker_enrollment_scope_owner;

DROP POLICY IF EXISTS worker_enrollment_scope_owner_read_sessions ON agent_sessions;
DROP POLICY IF EXISTS worker_enrollment_scope_owner_revoke_sessions ON agent_sessions;
CREATE POLICY worker_enrollment_scope_owner_read_sessions ON agent_sessions
    FOR SELECT TO worker_enrollment_scope_owner USING (true);
CREATE POLICY worker_enrollment_scope_owner_revoke_sessions ON agent_sessions
    FOR UPDATE TO worker_enrollment_scope_owner USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS worker_enrollment_scope_owner_revoke_model_credentials
    ON worker_model_proxy_credentials;
DROP POLICY IF EXISTS worker_enrollment_scope_owner_read_model_credentials
    ON worker_model_proxy_credentials;
CREATE POLICY worker_enrollment_scope_owner_read_model_credentials
    ON worker_model_proxy_credentials FOR SELECT TO worker_enrollment_scope_owner USING (true);
CREATE POLICY worker_enrollment_scope_owner_revoke_model_credentials
    ON worker_model_proxy_credentials FOR UPDATE TO worker_enrollment_scope_owner
    USING (true) WITH CHECK (true);

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

DROP POLICY IF EXISTS worker_enrollment_model_proxy_credentials_insert_scope
    ON worker_model_proxy_credentials;
CREATE POLICY worker_enrollment_model_proxy_credentials_insert_scope
    ON worker_model_proxy_credentials FOR INSERT TO worker_enrollment
    WITH CHECK (
        tenant_id = NULLIF(current_setting('agentteams.tenant_id', true), '')
        AND project_id = NULLIF(current_setting('agentteams.project_id', true), '')
        AND team_id = NULLIF(current_setting('agentteams.team_id', true), '')
        AND EXISTS (
            SELECT 1 FROM worker_enrollment_agent_scopes scoped
             WHERE scoped.agent_id = worker_model_proxy_credentials.agent_id
               AND scoped.tenant_id = worker_model_proxy_credentials.tenant_id
               AND scoped.project_id = worker_model_proxy_credentials.project_id
               AND scoped.team_id = worker_model_proxy_credentials.team_id
        )
        AND EXISTS (
            SELECT 1 FROM agent_sessions session
             WHERE session.id = worker_model_proxy_credentials.session_id
               AND session.agent_id = worker_model_proxy_credentials.agent_id
               AND session.revoked_at IS NULL
               AND session.expires_at > statement_timestamp()
        )
    );

DROP POLICY IF EXISTS worker_enrollment_model_proxy_credentials_select_scope
    ON worker_model_proxy_credentials;
CREATE POLICY worker_enrollment_model_proxy_credentials_select_scope
    ON worker_model_proxy_credentials FOR SELECT TO worker_enrollment
    USING (
        tenant_id = NULLIF(current_setting('agentteams.tenant_id', true), '')
        AND project_id = NULLIF(current_setting('agentteams.project_id', true), '')
        AND team_id = NULLIF(current_setting('agentteams.team_id', true), '')
    );

DROP POLICY IF EXISTS worker_enrollment_model_proxy_credentials_update_scope
    ON worker_model_proxy_credentials;
CREATE POLICY worker_enrollment_model_proxy_credentials_update_scope
    ON worker_model_proxy_credentials FOR UPDATE TO worker_enrollment
    USING (
        tenant_id = NULLIF(current_setting('agentteams.tenant_id', true), '')
        AND project_id = NULLIF(current_setting('agentteams.project_id', true), '')
        AND team_id = NULLIF(current_setting('agentteams.team_id', true), '')
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('agentteams.tenant_id', true), '')
        AND project_id = NULLIF(current_setting('agentteams.project_id', true), '')
        AND team_id = NULLIF(current_setting('agentteams.team_id', true), '')
    );
