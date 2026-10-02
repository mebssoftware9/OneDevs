-- check_request(), the gate in front of every Data API request: what each
-- mode lets through, and that a verdict belongs to one session of one account.
-- Rolled back at the end.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
)
select '00000000-0000-0000-0000-000000000000'::uuid, u.id, 'authenticated', 'authenticated',
       u.email, 'x', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false
from (values ('11111111-9999-0000-0000-000000000001'::uuid, 'u@onedevs.test'),
             ('22222222-9999-0000-0000-000000000002'::uuid, 'v@onedevs.test')) as u(id, email);

do $$
declare
    u constant uuid := '11111111-9999-0000-0000-000000000001';
    v constant uuid := '22222222-9999-0000-0000-000000000002';
    s1 constant uuid := 'aaaaaaaa-9999-0000-0000-000000000001';
    s2 constant uuid := 'aaaaaaaa-9999-0000-0000-000000000002';
    n int;
begin
    -- ---- watch: nothing refused, writes from an unverified session counted
    update public.integrity_settings set mode = 'watch' where id;
    perform set_config('request.jwt.claims',
        json_build_object('role', 'authenticated', 'sub', u, 'session_id', s1)::text, true);
    perform set_config('request.path', '/rpc/begin_test', true);
    perform set_config('request.method', 'POST', true);
    perform public.check_request();
    perform public.check_request();
    perform set_config('request.method', 'GET', true);
    perform public.check_request();
    select count into n from public.integrity_misses where account = u and path = '/rpc/begin_test';
    if n is distinct from 2 then raise exception 'FAIL watch: counted % writes, expected 2', n; end if;

    -- ---- enforce: an unverified session gets nothing, reads included
    update public.integrity_settings set mode = 'enforce' where id;
    begin
        perform public.check_request();
        raise exception 'FAIL: an unverified read was answered';
    exception when sqlstate 'PGRST' then
        null;
    end;

    -- ---- a passing verdict opens this session, and only this one
    perform public.record_verdict(u, s1, true, '{"app": "PLAY_RECOGNIZED"}'::jsonb);
    perform public.check_request();
    perform set_config('request.jwt.claims',
        json_build_object('role', 'authenticated', 'sub', u, 'session_id', s2)::text, true);
    begin
        perform public.check_request();
        raise exception 'FAIL: one session''s verdict opened another';
    exception when sqlstate 'PGRST' then
        null;
    end;

    -- ---- nor someone else's account, nor can a verdict be moved to one
    perform set_config('request.jwt.claims',
        json_build_object('role', 'authenticated', 'sub', v, 'session_id', s1)::text, true);
    begin
        perform public.check_request();
        raise exception 'FAIL: a verdict worked for another account';
    exception when sqlstate 'PGRST' then
        null;
    end;
    perform public.record_verdict(v, s1, true, '{}'::jsonb);
    if (select account from public.verified_sessions where session_id = s1) is distinct from u then
        raise exception 'FAIL: a session changed hands';
    end if;

    -- ---- a failing or expired verdict opens nothing
    perform set_config('request.jwt.claims',
        json_build_object('role', 'authenticated', 'sub', u, 'session_id', s1)::text, true);
    perform public.record_verdict(u, s1, false, '{"device": []}'::jsonb);
    begin
        perform public.check_request();
        raise exception 'FAIL: a failing verdict was let in';
    exception when sqlstate 'PGRST' then
        null;
    end;
    perform public.record_verdict(u, s1, true, '{}'::jsonb);
    update public.verified_sessions set expires_at = now() - interval '1 minute' where session_id = s1;
    begin
        perform public.check_request();
        raise exception 'FAIL: an expired verdict was let in';
    exception when sqlstate 'PGRST' then
        null;
    end;

    -- ---- the allow-list lets a developer's own debug build through
    insert into public.integrity_allowlist (account, note) values (u, 'debug build');
    perform public.check_request();
    delete from public.integrity_allowlist where account = u;

    -- ---- the server itself and signed-out callers are not this gate's business
    perform set_config('request.jwt.claims', json_build_object('role', 'service_role')::text, true);
    perform public.check_request();
    perform set_config('request.jwt.claims', '', true);
    perform public.check_request();

    -- ---- off is off
    update public.integrity_settings set mode = 'off' where id;
    perform set_config('request.jwt.claims',
        json_build_object('role', 'authenticated', 'sub', u, 'session_id', s2)::text, true);
    perform public.check_request();

    -- ---- and no client can write a verdict or read the books
    if has_function_privilege('authenticated', 'public.record_verdict(uuid, uuid, boolean, jsonb)', 'execute')
       or has_table_privilege('authenticated', 'public.verified_sessions', 'select')
       or has_table_privilege('authenticated', 'public.integrity_allowlist', 'insert')
       or has_table_privilege('authenticated', 'public.integrity_settings', 'update') then
        raise exception 'FAIL: a client can reach the verdicts';
    end if;

    raise notice 'integrity: all checks passed';
end $$;

rollback;
