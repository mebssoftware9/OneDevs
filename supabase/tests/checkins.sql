-- Mission check-ins run on the server's clock: a start it recorded, at least
-- 32 seconds old, credited for no longer than it was open, used once.
-- Rolled back at the end.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
)
select '00000000-0000-0000-0000-000000000000'::uuid,
       (lpad(n::text, 8, '0') || '-dddd-0000-0000-000000000000')::uuid,
       'authenticated', 'authenticated', 'k' || n || '@onedevs.test', 'x', now(), now(), now(),
       '{}'::jsonb, '{}'::jsonb, false
from generate_series(1, 3) n;

insert into public.listings (id, owner, package_name, title, category, channel)
select (lpad(n::text, 8, '0') || '-eeee-0000-0000-000000000000')::uuid,
       (lpad(n::text, 8, '0') || '-dddd-0000-0000-000000000000')::uuid,
       'com.k.app' || n, 'App ' || n, 'Tools', 'testing'
from generate_series(1, 3) n;

insert into public.mission_groups (id, name, state, started_on)
values ('aaaaaaaa-dddd-0000-0000-000000000000', 'Clockwork', 'running', current_date);

-- Members 1 and 2; number 3 is not in it.
insert into public.mission_seats (mission_id, member, listing_id, device, seat)
values ('aaaaaaaa-dddd-0000-0000-000000000000', '00000001-dddd-0000-0000-000000000000',
        '00000001-eeee-0000-0000-000000000000', 'device-k-1', 1),
       ('aaaaaaaa-dddd-0000-0000-000000000000', '00000002-dddd-0000-0000-000000000000',
        '00000002-eeee-0000-0000-000000000000', 'device-k-2', 2);

do $$
declare
    g constant uuid := 'aaaaaaaa-dddd-0000-0000-000000000000';
    m1 constant uuid := '00000001-dddd-0000-0000-000000000000';
    l1 constant uuid := '00000001-eeee-0000-0000-000000000000';
    l2 constant uuid := '00000002-eeee-0000-0000-000000000000';
    r jsonb;
    v int;
begin
    perform set_config('request.jwt.claims', json_build_object('sub', m1)::text, true);

    -- ---- the phone's word alone counts for nothing
    r := public.mission_checkin(g, l2, 600, 'device-k-1');
    if r->>'reason' is distinct from 'not_started' then raise exception 'FAIL: counted without a start: %', r; end if;

    -- ---- a start, then the server's 32 seconds
    r := public.mission_checkin_begin(g, l2);
    if (r->>'ok')::boolean is not true then raise exception 'FAIL begin: %', r; end if;
    r := public.mission_checkin(g, l2, 600, 'device-k-1');
    if r->>'reason' is distinct from 'too_short' then raise exception 'FAIL: counted before 32 seconds: %', r; end if;

    update public.mission_checkin_starts set started_at = now() - interval '40 seconds'
    where mission_id = g and member = m1 and listing_id = l2;
    r := public.mission_checkin(g, l2, 999999, 'device-k-1');
    if (r->>'ok')::boolean is not true then raise exception 'FAIL checkin: %', r; end if;

    -- ---- credited for as long as it was open, not as long as the phone said
    select seconds into v from public.mission_checkins
    where mission_id = g and member = m1 and listing_id = l2;
    if v is null or v < 32 or v > 45 then raise exception 'FAIL: credited % seconds', v; end if;

    -- ---- a start is used once
    r := public.mission_checkin(g, l2, 600, 'device-k-1');
    if r->>'reason' is distinct from 'not_started' then raise exception 'FAIL: one start counted twice: %', r; end if;

    -- ---- a start left open for hours has gone stale
    r := public.mission_checkin_begin(g, l2);
    update public.mission_checkin_starts set started_at = now() - interval '7 hours'
    where mission_id = g and member = m1 and listing_id = l2;
    r := public.mission_checkin(g, l2, 600, 'device-k-1');
    if r->>'reason' is distinct from 'not_started' then raise exception 'FAIL: a stale start counted: %', r; end if;

    -- ---- the same rules as before for whose app and whose mission
    r := public.mission_checkin_begin(g, l1);
    if r->>'reason' is distinct from 'own_app' then raise exception 'FAIL own app: %', r; end if;
    perform set_config('request.jwt.claims',
        json_build_object('sub', '00000003-dddd-0000-0000-000000000000')::text, true);
    r := public.mission_checkin_begin(g, l2);
    if r->>'reason' is distinct from 'not_member' then raise exception 'FAIL outsider: %', r; end if;

    -- ---- and the clock is the server's alone
    if has_table_privilege('authenticated', 'public.mission_checkin_starts', 'select')
       or has_table_privilege('authenticated', 'public.mission_checkin_starts', 'update')
       or has_function_privilege('authenticated', 'public.mission_task(uuid, uuid)', 'execute') then
        raise exception 'FAIL: a client can reach the clock';
    end if;

    raise notice 'checkins: all checks passed';
end $$;

rollback;
