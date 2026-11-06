-- Keep only the scope tuple needed by Enrollment. The general-purpose metadata JSON
-- remains unavailable to the Enrollment role.
CREATE TABLE IF NOT EXISTS worker_enrollment_agent_scopes (
    agent_id UUID PRIMARY KEY REFERENCES agents(id) ON DELETE CASCADE,
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT NOT NULL
);

-- Tenant-neutral fixed execution capacity. A slot has no Tenant/Project/Team
-- scope, and each TaskAssignment will bind it to one active Attempt.
CREATE TABLE IF NOT EXISTS worker_slots (
    id UUID PRIMARY KEY,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DRAINING', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE IF NOT EXISTS worker_slot_sessions (
    id UUID PRIMARY KEY,
    slot_id UUID NOT NULL REFERENCES worker_slots(id) ON DELETE CASCADE,
    subject TEXT NOT NULL CHECK (length(btrim(subject)) > 0),
    token_sha256 CHAR(64) NOT NULL UNIQUE CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
);
CREATE INDEX IF NOT EXISTS worker_slot_sessions_active_idx
    ON worker_slot_sessions(slot_id, expires_at) WHERE revoked_at IS NULL;

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

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_enrollment_membership_owner') THEN
        CREATE ROLE worker_enrollment_membership_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END;
$$;
ALTER ROLE worker_enrollment_membership_owner
    WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO worker_enrollment_membership_owner;
GRANT SELECT (tenant_id, project_id, subject, role, status)
    ON TABLE project_memberships TO worker_enrollment_membership_owner;
GRANT SELECT (id, tenant_id, name, status) ON TABLE projects TO worker_enrollment_membership_owner;
GRANT SELECT (id, external_key, status) ON TABLE tenants TO worker_enrollment_membership_owner;

CREATE OR REPLACE FUNCTION worker_enrollment_has_active_project_management_membership(
    p_subject TEXT, p_tenant_id TEXT, p_project_id TEXT)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (
        SELECT 1
          FROM public.project_memberships membership
          JOIN public.projects project
            ON project.id = membership.project_id
           AND project.tenant_id = membership.tenant_id
          JOIN public.tenants tenant
            ON tenant.id::TEXT = membership.tenant_id
            OR tenant.external_key = membership.tenant_id
         WHERE membership.subject = p_subject
           AND p_tenant_id IN (tenant.id::TEXT, tenant.external_key)
           AND (project.id::TEXT = p_project_id OR project.name = p_project_id)
           AND membership.status = 'ACTIVE'
           AND membership.role IN ('OWNER', 'ADMIN', 'OPERATOR')
           AND project.status = 'ACTIVE'
           AND tenant.status = 'ACTIVE'
    );
$$;
ALTER FUNCTION worker_enrollment_has_active_project_management_membership(TEXT, TEXT, TEXT)
    OWNER TO worker_enrollment_membership_owner;
REVOKE ALL ON FUNCTION worker_enrollment_has_active_project_management_membership(TEXT, TEXT, TEXT)
    FROM PUBLIC;
REVOKE ALL ON TABLE project_memberships, projects, tenants FROM worker_enrollment;
GRANT EXECUTE ON FUNCTION worker_enrollment_has_active_project_management_membership(TEXT, TEXT, TEXT)
    TO worker_enrollment;

CREATE OR REPLACE FUNCTION worker_enrollment_resolve_authorized_project_id(
    p_subject TEXT, p_tenant_id TEXT, p_project_id TEXT)
RETURNS TEXT
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT project.id::TEXT
      FROM public.project_memberships membership
      JOIN public.projects project
        ON project.id = membership.project_id
       AND project.tenant_id = membership.tenant_id
      JOIN public.tenants tenant
        ON tenant.id::TEXT = membership.tenant_id
        OR tenant.external_key = membership.tenant_id
     WHERE membership.subject = p_subject
       AND p_tenant_id IN (tenant.id::TEXT, tenant.external_key)
       AND (project.id::TEXT = p_project_id OR project.name = p_project_id)
       AND membership.status = 'ACTIVE'
       AND membership.role IN ('OWNER', 'ADMIN', 'OPERATOR')
       AND project.status = 'ACTIVE'
       AND tenant.status = 'ACTIVE'
     LIMIT 1;
$$;
ALTER FUNCTION worker_enrollment_resolve_authorized_project_id(TEXT, TEXT, TEXT)
    OWNER TO worker_enrollment_membership_owner;
REVOKE ALL ON FUNCTION worker_enrollment_resolve_authorized_project_id(TEXT, TEXT, TEXT)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION worker_enrollment_resolve_authorized_project_id(TEXT, TEXT, TEXT)
    TO worker_enrollment;

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
GRANT SELECT (agent_id, revoked_at), UPDATE (revoked_at) ON TABLE agent_sessions
    TO worker_enrollment_scope_owner;
GRANT SELECT (agent_id, revoked_at), UPDATE (revoked_at) ON TABLE worker_model_proxy_credentials
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

-- The runtime Enrollment role can manage slot sessions only through these
-- narrow functions. It receives neither SELECT nor DML privileges on the tables.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'worker_enrollment_slot_owner') THEN
        CREATE ROLE worker_enrollment_slot_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END;
$$;
ALTER ROLE worker_enrollment_slot_owner
    WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER TABLE worker_slots OWNER TO worker_enrollment_slot_owner;
ALTER TABLE worker_slot_sessions OWNER TO worker_enrollment_slot_owner;
GRANT USAGE ON SCHEMA public TO worker_enrollment_slot_owner;
GRANT SELECT ON worker_slots TO worker_enrollment_slot_owner;
GRANT SELECT, INSERT, UPDATE ON worker_slot_sessions TO worker_enrollment_slot_owner;
ALTER TABLE worker_slots ENABLE ROW LEVEL SECURITY;
ALTER TABLE worker_slot_sessions ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS worker_enrollment_slot_owner_slots ON worker_slots;
CREATE POLICY worker_enrollment_slot_owner_slots ON worker_slots
    FOR SELECT TO worker_enrollment_slot_owner USING (true);
DROP POLICY IF EXISTS worker_enrollment_slot_owner_sessions ON worker_slot_sessions;
CREATE POLICY worker_enrollment_slot_owner_sessions ON worker_slot_sessions
    FOR ALL TO worker_enrollment_slot_owner USING (true) WITH CHECK (true);
REVOKE ALL ON TABLE worker_slots, worker_slot_sessions FROM PUBLIC, worker_enrollment;
GRANT SELECT ON TABLE worker_slots TO agentteams_runtime;
DROP POLICY IF EXISTS worker_enrollment_runtime_read_slots ON worker_slots;
CREATE POLICY worker_enrollment_runtime_read_slots ON worker_slots
    FOR SELECT TO agentteams_runtime USING (true);

CREATE OR REPLACE FUNCTION public.worker_enrollment_worker_slot_is_active(p_slot_id UUID)
RETURNS BOOLEAN
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT EXISTS (SELECT 1 FROM public.worker_slots WHERE id = p_slot_id AND status = 'ACTIVE')
$$;
ALTER FUNCTION public.worker_enrollment_worker_slot_is_active(UUID)
    OWNER TO worker_enrollment_slot_owner;
REVOKE ALL ON FUNCTION public.worker_enrollment_worker_slot_is_active(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.worker_enrollment_worker_slot_is_active(UUID) TO worker_enrollment;

CREATE OR REPLACE FUNCTION public.worker_enrollment_create_worker_slot_session(
    p_session_id UUID, p_slot_id UUID, p_subject TEXT, p_token_sha256 TEXT,
    p_expires_at TIMESTAMPTZ, p_created_at TIMESTAMPTZ)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    INSERT INTO public.worker_slot_sessions(id, slot_id, subject, token_sha256, expires_at, created_at)
    SELECT p_session_id, p_slot_id, p_subject, p_token_sha256, p_expires_at, p_created_at
     WHERE EXISTS (SELECT 1 FROM public.worker_slots WHERE id = p_slot_id AND status = 'ACTIVE');
    RETURN FOUND;
END;
$$;
ALTER FUNCTION public.worker_enrollment_create_worker_slot_session(UUID, UUID, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ)
    OWNER TO worker_enrollment_slot_owner;
REVOKE ALL ON FUNCTION public.worker_enrollment_create_worker_slot_session(
    UUID, UUID, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.worker_enrollment_create_worker_slot_session(
    UUID, UUID, TEXT, TEXT, TIMESTAMPTZ, TIMESTAMPTZ) TO worker_enrollment;

CREATE OR REPLACE FUNCTION public.worker_enrollment_worker_slot_session_for_update(
    p_slot_id UUID, p_session_id UUID)
RETURNS TABLE (slot_id UUID, revoked BOOLEAN)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    RETURN QUERY
    SELECT session.slot_id, session.revoked_at IS NOT NULL
      FROM public.worker_slot_sessions AS session
     WHERE session.slot_id = p_slot_id AND session.id = p_session_id
     FOR UPDATE OF session;
END;
$$;
ALTER FUNCTION public.worker_enrollment_worker_slot_session_for_update(UUID, UUID)
    OWNER TO worker_enrollment_slot_owner;
REVOKE ALL ON FUNCTION public.worker_enrollment_worker_slot_session_for_update(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.worker_enrollment_worker_slot_session_for_update(UUID, UUID)
    TO worker_enrollment;

CREATE OR REPLACE FUNCTION public.worker_enrollment_revoke_worker_slot_session(
    p_slot_id UUID, p_session_id UUID, p_revoked_at TIMESTAMPTZ)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
    UPDATE public.worker_slot_sessions
       SET revoked_at = COALESCE(revoked_at, p_revoked_at)
     WHERE slot_id = p_slot_id AND id = p_session_id;
    RETURN FOUND;
END;
$$;
ALTER FUNCTION public.worker_enrollment_revoke_worker_slot_session(UUID, UUID, TIMESTAMPTZ)
    OWNER TO worker_enrollment_slot_owner;
REVOKE ALL ON FUNCTION public.worker_enrollment_revoke_worker_slot_session(UUID, UUID, TIMESTAMPTZ)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.worker_enrollment_revoke_worker_slot_session(UUID, UUID, TIMESTAMPTZ)
    TO worker_enrollment;

-- The Gateway may verify a slot's long-lived workload identity but must never
-- read the session table or learn its secret hash. Only active slots are
-- discoverable, while revoked/expired sessions remain visible so the Gateway can
-- reject them using the same response path.
CREATE OR REPLACE FUNCTION public.worker_enrollment_worker_slot_session_by_token_sha256(
    p_token_sha256 CHAR(64))
RETURNS TABLE (slot_id UUID, expires_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT session.slot_id, session.expires_at, session.revoked_at
      FROM public.worker_slot_sessions AS session
      JOIN public.worker_slots AS slot ON slot.id = session.slot_id
     WHERE session.token_sha256 = p_token_sha256 AND slot.status = 'ACTIVE'
$$;
ALTER FUNCTION public.worker_enrollment_worker_slot_session_by_token_sha256(CHAR(64))
    OWNER TO worker_enrollment_slot_owner;
REVOKE ALL ON FUNCTION public.worker_enrollment_worker_slot_session_by_token_sha256(CHAR(64)) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.worker_enrollment_worker_slot_session_by_token_sha256(CHAR(64))
    TO agentteams_runtime;

-- Credential Proxy needs a session ID to bind an Attempt capability so that
-- revoking or expiring the long-lived slot session can invalidate renewals.
-- It receives no token hash or subject and cannot read the session table.
CREATE OR REPLACE FUNCTION public.worker_enrollment_worker_slot_session_context_by_token_sha256(
    p_token_sha256 CHAR(64))
RETURNS TABLE (session_id UUID, slot_id UUID, expires_at TIMESTAMPTZ, revoked_at TIMESTAMPTZ)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT session.id, session.slot_id, session.expires_at, session.revoked_at
      FROM public.worker_slot_sessions AS session
      JOIN public.worker_slots AS slot ON slot.id = session.slot_id
     WHERE session.token_sha256 = p_token_sha256 AND slot.status = 'ACTIVE'
$$;
ALTER FUNCTION public.worker_enrollment_worker_slot_session_context_by_token_sha256(CHAR(64))
    OWNER TO worker_enrollment_slot_owner;
REVOKE ALL ON FUNCTION public.worker_enrollment_worker_slot_session_context_by_token_sha256(CHAR(64))
    FROM PUBLIC;
