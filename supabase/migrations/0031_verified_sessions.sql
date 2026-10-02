-- Verified sessions: the server checks where a request comes from before it
-- answers.
--
-- The app proves itself with a Play Integrity token: Google says whether it is
-- the unmodified OneDevs from Google Play, on a genuine device. The attest
-- Edge Function asks Google, and records the answer against the session that
-- asked. Every Data API request then passes check_request() first, which
-- looks that session up.
--
-- Three modes, so the switch is a setting and not a deploy:
--   off      no check at all
--   watch    nothing is refused; writes from unverified sessions are counted
--   enforce  unverified sessions get nothing, reads included
-- It starts in watch, so this migration changes nothing anyone can see.

create table if not exists public.integrity_settings (
    id boolean primary key default true check (id),
    mode text not null default 'watch' check (mode in ('off', 'watch', 'enforce')),
    -- How long one verdict covers a session. The app asks again before then.
    lifetime interval not null default interval '70 minutes'
);
insert into public.integrity_settings (id) values (true) on conflict (id) do nothing;

create table if not exists public.verified_sessions (
    session_id uuid primary key,
    account uuid not null references public.profiles (id) on delete cascade,
    passed boolean not null,
    verdict jsonb not null,
    verified_at timestamptz not null default now(),
    expires_at timestamptz not null
);
create index if not exists verified_sessions_account on public.verified_sessions (account);

-- Accounts the check waves through: a developer's own debug build is not
-- installed from Play, so Google will never call it genuine. Written from the
-- SQL editor only.
create table if not exists public.integrity_allowlist (
    account uuid primary key references public.profiles (id) on delete cascade,
    note text,
    added_at timestamptz not null default now()
);

-- What watch mode would have refused, by account, path and day.
create table if not exists public.integrity_misses (
    account uuid not null,
    path text not null,
    day date not null default (now() at time zone 'utc')::date,
    count int not null default 1,
    primary key (account, path, day)
);

alter table public.integrity_settings enable row level security;
alter table public.verified_sessions enable row level security;
alter table public.integrity_allowlist enable row level security;
alter table public.integrity_misses enable row level security;
revoke all on public.integrity_settings, public.verified_sessions,
              public.integrity_allowlist, public.integrity_misses
    from anon, authenticated;

/**
 * Records Google's verdict for a session. Service role only: the attest
 * function calls it after Google has decoded the token, with the account and
 * session taken from the caller's own verified access token.
 */
create or replace function public.record_verdict(
    p_account uuid,
    p_session uuid,
    p_passed boolean,
    p_verdict jsonb
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    v_until timestamptz;
begin
    if p_account is null or p_session is null then
        return jsonb_build_object('ok', false, 'reason', 'missing');
    end if;
    v_until := now() + (select lifetime from public.integrity_settings where id);
    insert into public.verified_sessions (session_id, account, passed, verdict, verified_at, expires_at)
    values (p_session, p_account, coalesce(p_passed, false), coalesce(p_verdict, '{}'::jsonb), now(), v_until)
    on conflict (session_id) do update
        set account = excluded.account, passed = excluded.passed, verdict = excluded.verdict,
            verified_at = excluded.verified_at, expires_at = excluded.expires_at
    -- A session never changes hands; a verdict for someone else's id is refused.
    where public.verified_sessions.account = excluded.account;
    return jsonb_build_object('ok', true, 'passed', coalesce(p_passed, false), 'until', v_until);
end $$;

revoke all on function public.record_verdict(uuid, uuid, boolean, jsonb) from public, anon, authenticated;
do $$
begin
    if exists (select 1 from pg_roles where rolname = 'service_role') then
        grant execute on function public.record_verdict(uuid, uuid, boolean, jsonb) to service_role;
    end if;
end $$;

/** Whether the calling session holds a passing verdict that has not expired. */
create or replace function public.session_verified() returns boolean
language sql stable security definer set search_path = '' as $$
    select exists (
        select 1 from public.integrity_allowlist where account = auth.uid()
    ) or exists (
        select 1 from public.verified_sessions v
        where v.session_id = nullif(nullif(current_setting('request.jwt.claims', true), '')::jsonb
                                    ->> 'session_id', '')::uuid
          and v.account = auth.uid()
          and v.passed
          and v.expires_at > now()
    );
$$;

revoke all on function public.session_verified() from public, anon;
grant execute on function public.session_verified() to authenticated;

/**
 * Runs before every Data API request. Signed-out callers and the service
 * role pass: one has no grants to use, the other is the server itself. A
 * signed-in session is checked against its verdict.
 */
create or replace function public.check_request() returns void
language plpgsql security definer set search_path = '' as $$
declare
    v_claims jsonb;
    v_mode text;
    v_path text;
begin
    v_claims := nullif(current_setting('request.jwt.claims', true), '')::jsonb;
    if v_claims is null or v_claims ->> 'role' is distinct from 'authenticated' then
        return;
    end if;
    select mode into v_mode from public.integrity_settings where id;
    if v_mode is null or v_mode = 'off' or public.session_verified() then
        return;
    end if;

    v_path := coalesce(current_setting('request.path', true), '');
    if v_mode = 'watch' then
        -- Reads run read-only, so only writes are counted. Counting must
        -- never be the reason a request fails.
        if current_setting('request.method', true) not in ('GET', 'HEAD') then
            begin
                insert into public.integrity_misses (account, path)
                values ((v_claims ->> 'sub')::uuid, left(v_path, 120))
                on conflict (account, path, day) do update
                    set count = public.integrity_misses.count + 1;
            exception when others then
                null;
            end;
        end if;
        return;
    end if;

    raise sqlstate 'PGRST' using
        message = json_build_object(
            'code', 'unverified',
            'message', 'This session has not been verified by Google Play.'
        )::text,
        detail = json_build_object('status', 403, 'headers', json_build_object())::text;
end $$;

revoke all on function public.check_request() from public, anon;
grant execute on function public.check_request() to authenticated, anon;

-- Run it before every Data API request.
alter role authenticator set pgrst.db_pre_request = 'public.check_request';
notify pgrst, 'reload config';
