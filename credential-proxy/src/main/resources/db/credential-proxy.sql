-- Apply after the shared Control Plane schema is migrated through v121.
-- Only the non-login function owner can read the narrow columns below. The
-- runtime login should inherit credential_proxy_query and receive no table grants.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_query_owner') THEN
        CREATE ROLE credential_proxy_query_owner
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'credential_proxy_query') THEN
        CREATE ROLE credential_proxy_query
            NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END;
$$;

ALTER ROLE credential_proxy_query_owner
    WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER ROLE credential_proxy_query
    WITH NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- Credentials for shared, tenant-neutral Worker slots are bound to exactly
-- one Attempt and Lease. The tenant scope is always joined from the Task.
CREATE TABLE IF NOT EXISTS public.attempt_model_proxy_credentials (
    id UUID PRIMARY KEY,
    agent_id UUID NOT NULL,
    task_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    lease_id UUID NOT NULL,
    token_sha256 CHAR(64) NOT NULL UNIQUE,
    model_credential_ref TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp()
);
CREATE UNIQUE INDEX IF NOT EXISTS attempt_model_proxy_credentials_one_live_token
    ON public.attempt_model_proxy_credentials (attempt_id, lease_id)
    WHERE revoked_at IS NULL;
REVOKE ALL ON TABLE public.attempt_model_proxy_credentials FROM PUBLIC;

-- Provider references are provisioned by deployment administration. Workers
-- cannot choose or enumerate them. The issuer resolves one by TASK scope.
CREATE TABLE IF NOT EXISTS public.tenant_model_proxy_credential_refs (
    tenant_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    team_id TEXT NOT NULL,
    model_credential_ref TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (tenant_id, project_id, team_id),
    CHECK (length(model_credential_ref) BETWEEN 1 AND 512)
);
REVOKE ALL ON TABLE public.tenant_model_proxy_credential_refs FROM PUBLIC;

GRANT USAGE ON SCHEMA public TO credential_proxy_query_owner, credential_proxy_query;
GRANT CREATE ON SCHEMA public TO credential_proxy_query_owner;
GRANT SELECT (id, task_id, lease_id, phase, lease_expires_at)
    ON TABLE public.task_attempts TO credential_proxy_query_owner;
GRANT SELECT (task_id, attempt_id, agent_id, phase, released_at)
    ON TABLE public.task_assignments TO credential_proxy_query_owner;
GRANT SELECT (id, agent_id, task_attempt_id, expires_at, released_at, status)
    ON TABLE public.agent_leases TO credential_proxy_query_owner;
GRANT SELECT (resource_type, resource_id, tenant_id, project_id, team)
    ON TABLE public.resource_scopes TO credential_proxy_query_owner;
GRANT SELECT (id, session_id, agent_id, tenant_id, project_id, team_id, token_sha256,
              model_credential_ref, expires_at, revoked_at)
    ON TABLE public.worker_model_proxy_credentials TO credential_proxy_query_owner;
GRANT SELECT (id, agent_id, token_sha256, expires_at, revoked_at)
    ON TABLE public.agent_sessions TO credential_proxy_query_owner;
GRANT SELECT (agent_id, tenant_id, project_id, team_id)
    ON TABLE public.worker_enrollment_agent_scopes TO credential_proxy_query_owner;
GRANT SELECT (id, agent_id, task_id, attempt_id, lease_id, token_sha256,
              model_credential_ref, expires_at, revoked_at)
    ON TABLE public.attempt_model_proxy_credentials TO credential_proxy_query_owner;
GRANT SELECT (tenant_id, project_id, team_id, model_credential_ref, enabled)
    ON TABLE public.tenant_model_proxy_credential_refs TO credential_proxy_query_owner;
GRANT INSERT, UPDATE (revoked_at) ON TABLE public.attempt_model_proxy_credentials
    TO credential_proxy_query_owner;

ALTER TABLE public.worker_model_proxy_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.agent_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.worker_enrollment_agent_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.attempt_model_proxy_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.tenant_model_proxy_credential_refs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS credential_proxy_owner_model_credentials_read
    ON public.worker_model_proxy_credentials;
CREATE POLICY credential_proxy_owner_model_credentials_read
    ON public.worker_model_proxy_credentials FOR SELECT TO credential_proxy_query_owner USING (true);
DROP POLICY IF EXISTS credential_proxy_owner_sessions_read ON public.agent_sessions;
CREATE POLICY credential_proxy_owner_sessions_read
    ON public.agent_sessions FOR SELECT TO credential_proxy_query_owner USING (true);
DROP POLICY IF EXISTS credential_proxy_owner_agent_scopes_read
    ON public.worker_enrollment_agent_scopes;
CREATE POLICY credential_proxy_owner_agent_scopes_read
    ON public.worker_enrollment_agent_scopes FOR SELECT TO credential_proxy_query_owner USING (true);
DROP POLICY IF EXISTS credential_proxy_owner_attempt_credentials_read
    ON public.attempt_model_proxy_credentials;
CREATE POLICY credential_proxy_owner_attempt_credentials_read
    ON public.attempt_model_proxy_credentials FOR SELECT TO credential_proxy_query_owner USING (true);
DROP POLICY IF EXISTS credential_proxy_owner_attempt_credentials_insert
    ON public.attempt_model_proxy_credentials;
CREATE POLICY credential_proxy_owner_attempt_credentials_insert
    ON public.attempt_model_proxy_credentials FOR INSERT TO credential_proxy_query_owner
    WITH CHECK (true);
DROP POLICY IF EXISTS credential_proxy_owner_attempt_credentials_update
    ON public.attempt_model_proxy_credentials;
CREATE POLICY credential_proxy_owner_attempt_credentials_update
    ON public.attempt_model_proxy_credentials FOR UPDATE TO credential_proxy_query_owner
    USING (true) WITH CHECK (true);
DROP POLICY IF EXISTS credential_proxy_owner_tenant_credential_refs_read
    ON public.tenant_model_proxy_credential_refs;
CREATE POLICY credential_proxy_owner_tenant_credential_refs_read
    ON public.tenant_model_proxy_credential_refs FOR SELECT TO credential_proxy_query_owner USING (true);

-- Retire the older caller-scoped overload without dropping it: external
-- database objects may depend on its signature during an in-place upgrade.
-- Runtime principals must no longer be able to invoke it.
DO $$
DECLARE
    runtime_role TEXT;
BEGIN
    IF to_regprocedure('public.credential_proxy_active_attempts(uuid,text,text,text)') IS NOT NULL THEN
        EXECUTE 'REVOKE ALL ON FUNCTION public.credential_proxy_active_attempts(UUID, TEXT, TEXT, TEXT) '
             || 'FROM PUBLIC';
        FOREACH runtime_role IN ARRAY ARRAY[
            'credential_proxy_query', 'credential_proxy_runtime',
            'worker_enrollment', 'agentteams_runtime'
        ] LOOP
            IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
                EXECUTE format(
                    'REVOKE ALL ON FUNCTION public.credential_proxy_active_attempts(UUID, TEXT, TEXT, TEXT) FROM %I',
                    runtime_role);
            END IF;
        END LOOP;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION public.credential_proxy_active_attempts(p_proxy_credential_id UUID)
RETURNS TABLE (
    agent_id UUID,
    tenant_id TEXT,
    project_id TEXT,
    team_id TEXT,
    task_id UUID,
    attempt_id UUID,
    lease_id UUID,
    lease_expires_at TIMESTAMPTZ,
    lease_active BOOLEAN,
    attempt_active BOOLEAN,
    assignment_active BOOLEAN
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT assignment.agent_id,
           task_scope.tenant_id,
           task_scope.project_id,
           task_scope.team,
           assignment.task_id,
           assignment.attempt_id,
           lease.id,
           LEAST(lease.expires_at, attempt.lease_expires_at),
           TRUE,
           TRUE,
           TRUE
      FROM public.task_assignments AS assignment
      JOIN public.task_attempts AS attempt
        ON attempt.id = assignment.attempt_id
       AND attempt.task_id = assignment.task_id
      JOIN public.agent_leases AS lease
        ON lease.id = attempt.lease_id
       AND lease.task_attempt_id = attempt.id
       AND lease.agent_id = assignment.agent_id
      JOIN public.resource_scopes AS worker_scope
        ON worker_scope.resource_type = 'WORKER'
       AND worker_scope.resource_id = assignment.agent_id
      JOIN public.resource_scopes AS task_scope
        ON task_scope.resource_type = 'TASK'
       AND task_scope.resource_id = assignment.task_id
      JOIN public.worker_model_proxy_credentials AS credential
        ON credential.agent_id = assignment.agent_id
      JOIN public.agent_sessions AS session
        ON session.id = credential.session_id
       AND session.agent_id = credential.agent_id
      JOIN public.worker_enrollment_agent_scopes AS current_scope
        ON current_scope.agent_id = credential.agent_id
       AND current_scope.tenant_id = credential.tenant_id
       AND current_scope.project_id = credential.project_id
       AND current_scope.team_id = credential.team_id
     WHERE credential.id = p_proxy_credential_id
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND session.revoked_at IS NULL
       AND session.expires_at > statement_timestamp()
       AND worker_scope.tenant_id = credential.tenant_id
       AND worker_scope.project_id = credential.project_id
       AND worker_scope.team = credential.team_id
       AND task_scope.tenant_id = credential.tenant_id
       AND task_scope.project_id = credential.project_id
       AND task_scope.team = credential.team_id
       AND assignment.agent_id = credential.agent_id
       AND assignment.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND assignment.released_at IS NULL
       AND attempt.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND attempt.lease_expires_at > statement_timestamp()
       AND lease.status = 'ACTIVE'
       AND lease.released_at IS NULL
       AND lease.expires_at > statement_timestamp()
    UNION ALL
    SELECT assignment.agent_id,
           task_scope.tenant_id,
           task_scope.project_id,
           task_scope.team,
           assignment.task_id,
           assignment.attempt_id,
           lease.id,
           LEAST(lease.expires_at, attempt.lease_expires_at, credential.expires_at),
           TRUE,
           TRUE,
           TRUE
      FROM public.attempt_model_proxy_credentials AS credential
      JOIN public.task_assignments AS assignment
        ON assignment.task_id = credential.task_id
       AND assignment.attempt_id = credential.attempt_id
       AND assignment.agent_id = credential.agent_id
      JOIN public.task_attempts AS attempt
        ON attempt.id = assignment.attempt_id
       AND attempt.task_id = assignment.task_id
       AND attempt.lease_id = credential.lease_id
      JOIN public.agent_leases AS lease
        ON lease.id = attempt.lease_id
       AND lease.task_attempt_id = attempt.id
       AND lease.agent_id = assignment.agent_id
      JOIN public.resource_scopes AS task_scope
        ON task_scope.resource_type = 'TASK'
       AND task_scope.resource_id = assignment.task_id
     WHERE credential.id = p_proxy_credential_id
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND assignment.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND assignment.released_at IS NULL
       AND attempt.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND attempt.lease_expires_at > statement_timestamp()
       AND lease.status = 'ACTIVE'
       AND lease.released_at IS NULL
       AND lease.expires_at > statement_timestamp()
$$;

ALTER FUNCTION public.credential_proxy_active_attempts(UUID)
    OWNER TO credential_proxy_query_owner;
REVOKE ALL ON FUNCTION public.credential_proxy_active_attempts(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.credential_proxy_active_attempts(UUID)
    TO credential_proxy_query;

CREATE OR REPLACE FUNCTION public.credential_proxy_authenticate(p_token_sha256 TEXT)
RETURNS TABLE (
    proxy_credential_id UUID,
    agent_id UUID,
    tenant_id TEXT,
    project_id TEXT,
    team_id TEXT,
    model_credential_ref TEXT
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT credential.id,
           credential.agent_id,
           credential.tenant_id,
           credential.project_id,
           credential.team_id,
           credential.model_credential_ref
      FROM public.worker_model_proxy_credentials AS credential
      JOIN public.agent_sessions AS session
        ON session.id = credential.session_id
       AND session.agent_id = credential.agent_id
      JOIN public.worker_enrollment_agent_scopes AS current_scope
        ON current_scope.agent_id = credential.agent_id
       AND current_scope.tenant_id = credential.tenant_id
       AND current_scope.project_id = credential.project_id
       AND current_scope.team_id = credential.team_id
     WHERE credential.token_sha256 = p_token_sha256::CHAR(64)
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND session.revoked_at IS NULL
       AND session.expires_at > statement_timestamp()
$$;

ALTER FUNCTION public.credential_proxy_authenticate(TEXT)
    OWNER TO credential_proxy_query_owner;
REVOKE CREATE ON SCHEMA public FROM credential_proxy_query_owner;
REVOKE ALL ON FUNCTION public.credential_proxy_authenticate(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.credential_proxy_authenticate(TEXT)
    TO credential_proxy_query;

CREATE OR REPLACE FUNCTION public.credential_proxy_revalidate(p_proxy_credential_id UUID)
RETURNS TABLE (
    proxy_credential_id UUID,
    agent_id UUID,
    tenant_id TEXT,
    project_id TEXT,
    team_id TEXT,
    model_credential_ref TEXT
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT credential.id,
           credential.agent_id,
           credential.tenant_id,
           credential.project_id,
           credential.team_id,
           credential.model_credential_ref
      FROM public.worker_model_proxy_credentials AS credential
      JOIN public.agent_sessions AS session
        ON session.id = credential.session_id
       AND session.agent_id = credential.agent_id
      JOIN public.worker_enrollment_agent_scopes AS current_scope
        ON current_scope.agent_id = credential.agent_id
       AND current_scope.tenant_id = credential.tenant_id
       AND current_scope.project_id = credential.project_id
       AND current_scope.team_id = credential.team_id
     WHERE credential.id = p_proxy_credential_id
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND session.revoked_at IS NULL
       AND session.expires_at > statement_timestamp()
$$;

ALTER FUNCTION public.credential_proxy_revalidate(UUID)
    OWNER TO credential_proxy_query_owner;
REVOKE ALL ON FUNCTION public.credential_proxy_revalidate(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.credential_proxy_revalidate(UUID)
    TO credential_proxy_query;

-- v2 keeps the original function signatures available for rolling upgrades,
-- while returning Attempt/Lease identity for the tenant-neutral credential.
CREATE OR REPLACE FUNCTION public.credential_proxy_authenticate_v2(p_token_sha256 TEXT)
RETURNS TABLE (
    proxy_credential_id UUID,
    agent_id UUID,
    tenant_id TEXT,
    project_id TEXT,
    team_id TEXT,
    model_credential_ref TEXT,
    attempt_id UUID,
    lease_id UUID
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT credential.id, credential.agent_id, credential.tenant_id,
           credential.project_id, credential.team_id, credential.model_credential_ref,
           NULL::UUID, NULL::UUID
      FROM public.worker_model_proxy_credentials AS credential
      JOIN public.agent_sessions AS session
        ON session.id = credential.session_id AND session.agent_id = credential.agent_id
      JOIN public.worker_enrollment_agent_scopes AS current_scope
        ON current_scope.agent_id = credential.agent_id
       AND current_scope.tenant_id = credential.tenant_id
       AND current_scope.project_id = credential.project_id
       AND current_scope.team_id = credential.team_id
     WHERE credential.token_sha256 = p_token_sha256::CHAR(64)
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND session.revoked_at IS NULL
       AND session.expires_at > statement_timestamp()
    UNION ALL
    SELECT credential.id, assignment.agent_id, task_scope.tenant_id,
           task_scope.project_id, task_scope.team, credential.model_credential_ref,
           assignment.attempt_id, lease.id
      FROM public.attempt_model_proxy_credentials AS credential
      JOIN public.task_assignments AS assignment
        ON assignment.task_id = credential.task_id
       AND assignment.attempt_id = credential.attempt_id
       AND assignment.agent_id = credential.agent_id
      JOIN public.task_attempts AS attempt
        ON attempt.id = assignment.attempt_id
       AND attempt.task_id = assignment.task_id
       AND attempt.lease_id = credential.lease_id
      JOIN public.agent_leases AS lease
        ON lease.id = attempt.lease_id
       AND lease.task_attempt_id = attempt.id
       AND lease.agent_id = assignment.agent_id
      JOIN public.resource_scopes AS task_scope
        ON task_scope.resource_type = 'TASK'
       AND task_scope.resource_id = assignment.task_id
     WHERE credential.token_sha256 = p_token_sha256::CHAR(64)
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND assignment.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND assignment.released_at IS NULL
       AND attempt.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND attempt.lease_expires_at > statement_timestamp()
       AND lease.status = 'ACTIVE'
       AND lease.released_at IS NULL
       AND lease.expires_at > statement_timestamp()
$$;

ALTER FUNCTION public.credential_proxy_authenticate_v2(TEXT)
    OWNER TO credential_proxy_query_owner;
REVOKE ALL ON FUNCTION public.credential_proxy_authenticate_v2(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.credential_proxy_authenticate_v2(TEXT)
    TO credential_proxy_query;

-- A Gateway-authenticated Worker asks for its active Attempt credential only
-- after receiving TaskAssigned. The bearer session is never stored here: only
-- its hash is checked, and only the new Attempt token hash is persisted.
CREATE OR REPLACE FUNCTION public.credential_proxy_issue_attempt(
    p_worker_session_sha256 TEXT,
    p_task_id UUID,
    p_attempt_id UUID,
    p_lease_id UUID,
    p_token_sha256 TEXT,
    p_credential_id UUID,
    p_ttl_seconds INTEGER
)
RETURNS TABLE (
    credential_id UUID,
    agent_id UUID,
    tenant_id TEXT,
    project_id TEXT,
    team_id TEXT,
    model_credential_ref TEXT,
    expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    v_agent_id UUID;
    v_tenant_id TEXT;
    v_project_id TEXT;
    v_team_id TEXT;
    v_model_credential_ref TEXT;
    v_lease_expires_at TIMESTAMPTZ;
    v_attempt_expires_at TIMESTAMPTZ;
    v_expires_at TIMESTAMPTZ;
BEGIN
    IF p_worker_session_sha256 IS NULL OR p_token_sha256 IS NULL OR p_credential_id IS NULL
       OR p_task_id IS NULL OR p_attempt_id IS NULL OR p_lease_id IS NULL
       OR p_worker_session_sha256 !~ '^[0-9a-fA-F]{64}$'
       OR p_token_sha256 !~ '^[0-9a-fA-F]{64}$'
       OR p_ttl_seconds IS NULL OR p_ttl_seconds < 1 OR p_ttl_seconds > 300 THEN
        RETURN;
    END IF;

    -- Serialize retries for the same capability without granting table locks
    -- or write access on assignment and lease tables to this function owner.
    PERFORM pg_advisory_xact_lock(hashtextextended(p_attempt_id::TEXT || ':' || p_lease_id::TEXT, 0));

    SELECT session.agent_id INTO v_agent_id
      FROM public.agent_sessions AS session
     WHERE session.token_sha256 = p_worker_session_sha256::CHAR(64)
       AND session.revoked_at IS NULL
       AND session.expires_at > statement_timestamp();
    IF v_agent_id IS NULL THEN RETURN; END IF;

    SELECT task_scope.tenant_id, task_scope.project_id, task_scope.team,
           credential_ref.model_credential_ref, lease.expires_at, attempt.lease_expires_at
      INTO v_tenant_id, v_project_id, v_team_id, v_model_credential_ref,
           v_lease_expires_at, v_attempt_expires_at
      FROM public.task_assignments AS assignment
      JOIN public.task_attempts AS attempt
        ON attempt.id = assignment.attempt_id AND attempt.task_id = assignment.task_id
      JOIN public.agent_leases AS lease
        ON lease.id = attempt.lease_id AND lease.task_attempt_id = attempt.id
       AND lease.agent_id = assignment.agent_id
      JOIN public.resource_scopes AS task_scope
        ON task_scope.resource_type = 'TASK' AND task_scope.resource_id = assignment.task_id
      JOIN public.tenant_model_proxy_credential_refs AS credential_ref
        ON credential_ref.tenant_id = task_scope.tenant_id
       AND credential_ref.project_id = task_scope.project_id
       AND credential_ref.team_id = task_scope.team
       AND credential_ref.enabled
     WHERE assignment.task_id = p_task_id
       AND assignment.attempt_id = p_attempt_id
       AND assignment.agent_id = v_agent_id
       AND assignment.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND assignment.released_at IS NULL
       AND attempt.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND attempt.lease_expires_at > statement_timestamp()
       AND attempt.lease_id = p_lease_id
       AND lease.id = p_lease_id
       AND lease.status = 'ACTIVE'
       AND lease.released_at IS NULL
       AND lease.expires_at > statement_timestamp();
    IF v_tenant_id IS NULL THEN RETURN; END IF;

    v_expires_at := LEAST(statement_timestamp() + make_interval(secs => p_ttl_seconds),
                           v_lease_expires_at, v_attempt_expires_at);
    IF v_expires_at <= statement_timestamp() THEN RETURN; END IF;

    UPDATE public.attempt_model_proxy_credentials AS credential
       SET revoked_at = statement_timestamp()
     WHERE credential.attempt_id = p_attempt_id
       AND credential.lease_id = p_lease_id
       AND credential.revoked_at IS NULL;

    INSERT INTO public.attempt_model_proxy_credentials (
        id, agent_id, task_id, attempt_id, lease_id, token_sha256,
        model_credential_ref, expires_at
    ) VALUES (
        p_credential_id, v_agent_id, p_task_id, p_attempt_id, p_lease_id,
        p_token_sha256::CHAR(64), v_model_credential_ref, v_expires_at
    );

    RETURN QUERY SELECT p_credential_id, v_agent_id, v_tenant_id, v_project_id,
                        v_team_id, v_model_credential_ref, v_expires_at;
END;
$$;

ALTER FUNCTION public.credential_proxy_issue_attempt(TEXT, UUID, UUID, UUID, TEXT, UUID, INTEGER)
    OWNER TO credential_proxy_query_owner;
REVOKE ALL ON FUNCTION public.credential_proxy_issue_attempt(TEXT, UUID, UUID, UUID, TEXT, UUID, INTEGER)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.credential_proxy_issue_attempt(TEXT, UUID, UUID, UUID, TEXT, UUID, INTEGER)
    TO credential_proxy_query;

CREATE OR REPLACE FUNCTION public.credential_proxy_revalidate_v2(p_proxy_credential_id UUID)
RETURNS TABLE (
    proxy_credential_id UUID,
    agent_id UUID,
    tenant_id TEXT,
    project_id TEXT,
    team_id TEXT,
    model_credential_ref TEXT,
    attempt_id UUID,
    lease_id UUID
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $$
    SELECT credential.id, credential.agent_id, credential.tenant_id,
           credential.project_id, credential.team_id, credential.model_credential_ref,
           NULL::UUID, NULL::UUID
      FROM public.worker_model_proxy_credentials AS credential
      JOIN public.agent_sessions AS session
        ON session.id = credential.session_id AND session.agent_id = credential.agent_id
      JOIN public.worker_enrollment_agent_scopes AS current_scope
        ON current_scope.agent_id = credential.agent_id
       AND current_scope.tenant_id = credential.tenant_id
       AND current_scope.project_id = credential.project_id
       AND current_scope.team_id = credential.team_id
     WHERE credential.id = p_proxy_credential_id
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND session.revoked_at IS NULL
       AND session.expires_at > statement_timestamp()
    UNION ALL
    SELECT credential.id, assignment.agent_id, task_scope.tenant_id,
           task_scope.project_id, task_scope.team, credential.model_credential_ref,
           assignment.attempt_id, lease.id
      FROM public.attempt_model_proxy_credentials AS credential
      JOIN public.task_assignments AS assignment
        ON assignment.task_id = credential.task_id
       AND assignment.attempt_id = credential.attempt_id
       AND assignment.agent_id = credential.agent_id
      JOIN public.task_attempts AS attempt
        ON attempt.id = assignment.attempt_id
       AND attempt.task_id = assignment.task_id
       AND attempt.lease_id = credential.lease_id
      JOIN public.agent_leases AS lease
        ON lease.id = attempt.lease_id
       AND lease.task_attempt_id = attempt.id
       AND lease.agent_id = assignment.agent_id
      JOIN public.resource_scopes AS task_scope
        ON task_scope.resource_type = 'TASK'
       AND task_scope.resource_id = assignment.task_id
     WHERE credential.id = p_proxy_credential_id
       AND credential.revoked_at IS NULL
       AND credential.expires_at > statement_timestamp()
       AND assignment.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND assignment.released_at IS NULL
       AND attempt.phase IN ('ASSIGNED', 'ACCEPTED', 'RUNNING')
       AND attempt.lease_expires_at > statement_timestamp()
       AND lease.status = 'ACTIVE'
       AND lease.released_at IS NULL
       AND lease.expires_at > statement_timestamp()
$$;

ALTER FUNCTION public.credential_proxy_revalidate_v2(UUID)
    OWNER TO credential_proxy_query_owner;
REVOKE ALL ON FUNCTION public.credential_proxy_revalidate_v2(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.credential_proxy_revalidate_v2(UUID)
    TO credential_proxy_query;
