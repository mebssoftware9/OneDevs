-- Testing cycles: the monthly bonus, starting a cycle, activation, the
-- spotlight and its 50 DevCoins, the guarantee and the insights. Rolled
-- back at the end.
--
-- O owns the apps and pays for Premium. T tests from the Board. Forty
-- members sit in three missions that are running, and one more mission is
-- recruiting, so activation has somewhere to put the app.
--
-- Time is moved by moving a run's started_at back, not by waiting.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
)
select '00000000-0000-0000-0000-000000000000'::uuid, u.id, 'authenticated', 'authenticated',
       u.email, 'x', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false
from (values
    ('0e000000-0000-0000-0000-000000000001'::uuid, 'o@onedevs.test'),
    ('7e000000-0000-0000-0000-000000000002'::uuid, 't@onedevs.test')
) u (id, email)
union all
select '00000000-0000-0000-0000-000000000000'::uuid,
       (lpad(n::text, 8, '0') || '-aaaa-0000-0000-000000000000')::uuid,
       'authenticated', 'authenticated', 'm' || n || '@onedevs.test', 'x', now(), now(), now(),
       '{}'::jsonb, '{}'::jsonb, false
from generate_series(1, 40) n;

insert into public.listings (id, owner, package_name, title, category, channel) values
    ('11110000-0000-0000-0000-000000000001', '0e000000-0000-0000-0000-000000000001',
     'com.o.one', 'One', 'Tools', 'testing'),
    ('11110000-0000-0000-0000-000000000002', '0e000000-0000-0000-0000-000000000001',
     'com.o.two', 'Two', 'Tools', 'testing');

-- Every member's own app, for their mission seat.
insert into public.listings (id, owner, package_name, title, category, channel)
select ('22220000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
       (lpad(n::text, 8, '0') || '-aaaa-0000-0000-000000000000')::uuid,
       'com.m.app' || n, 'App ' || n, 'Tools', 'testing'
from generate_series(1, 40) n;

-- Three running missions of up to sixteen, with 12, 10 and 2 days left.
insert into public.mission_groups (id, name, state, started_on) values
    ('33330000-0000-0000-0000-000000000001', 'Mission A', 'running', current_date - 2),
    ('33330000-0000-0000-0000-000000000002', 'Mission B', 'running', current_date - 4),
    ('33330000-0000-0000-0000-000000000003', 'Mission C', 'running', current_date - 12),
    -- Over: nothing may be placed here.
    ('33330000-0000-0000-0000-000000000004', 'Mission D', 'running', current_date - 20);

insert into public.mission_seats (mission_id, member, listing_id, device, seat)
select ('33330000-0000-0000-0000-00000000000' || (1 + (n - 1) / 16)::text)::uuid,
       (lpad(n::text, 8, '0') || '-aaaa-0000-0000-000000000000')::uuid,
       ('22220000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
       'device-m' || n,
       1 + (n - 1) % 16
from generate_series(1, 40) n;

create temporary table said (step text primary key, reply jsonb) on commit drop;
grant all on said to authenticated;

-- ---- O before paying: Community has no cycles
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0e000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into said values
    ('free_start', public.cycle_start('11110000-0000-0000-0000-000000000001'));

-- ---- Premium, as the purchase function records it: first month, a retry, a renewal
reset role;
insert into said values
    ('bonus_first', public.subscription_update(
        '0e000000-0000-0000-0000-000000000001', 'tok-o', 'premium', 'active',
        now() + interval '30 days', 'GPA.1')),
    ('bonus_retry', public.subscription_update(
        '0e000000-0000-0000-0000-000000000001', 'tok-o', 'premium', 'active',
        now() + interval '30 days', 'GPA.1')),
    ('bonus_renewal', public.subscription_update(
        '0e000000-0000-0000-0000-000000000001', 'tok-o', 'premium', 'active',
        now() + interval '31 days', 'GPA.1..0'));

-- ---- O starts cycles
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0e000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into said values ('allowance_before', public.cycle_allowance());
insert into said values
    ('not_mine', public.cycle_start('22220000-0000-0000-0000-000000000001')),
    ('start', public.cycle_start('11110000-0000-0000-0000-000000000001'));
insert into said values
    ('again', public.cycle_start('11110000-0000-0000-0000-000000000001')),
    ('second_app', public.cycle_start('11110000-0000-0000-0000-000000000002')),
    ('allowance_after', public.cycle_allowance());

-- ---- T on day 0: the app is in the spotlight
select set_config('request.jwt.claims',
    '{"sub":"7e000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
insert into said values
    ('board_day0', public.home_board('device-t-0001')),
    ('status_day0', public.test_status('11110000-0000-0000-0000-000000000001', 'device-t-0001')),
    ('begin_day0', public.begin_test('11110000-0000-0000-0000-000000000001', 'device-t-0001'));

-- Thirty-two seconds later, by the server's clock.
reset role;
update public.test_sessions set started_at = now() - interval '1 minute'
where tester = '7e000000-0000-0000-0000-000000000002';

set local role authenticated;
insert into said values
    ('finish_day0', public.finish_test(
        (select (reply->>'session')::uuid from said where step = 'begin_day0'), 40));

-- ---- Day 1: out of the spotlight, and off the boards
reset role;
update public.ghostline_runs
set started_at = started_at - interval '1 day', ends_at = ends_at - interval '1 day'
where kind = 'cycle';
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"00000039-aaaa-0000-0000-000000000000","role":"authenticated"}', true);
insert into said values ('board_day1', public.home_board('device-m39'));

-- ---- Members of mission A use the app over the first days
reset role;
insert into public.mission_checkins (mission_id, member, listing_id, day, seconds, device, device_model, android_sdk, at)
select '33330000-0000-0000-0000-000000000001',
       (lpad(n::text, 8, '0') || '-aaaa-0000-0000-000000000000')::uuid,
       '11110000-0000-0000-0000-000000000001',
       current_date, 60 * n, 'device-m' || n,
       case when n % 2 = 0 then 'Pixel 8' else 'Galaxy S24' end, 34 + n % 2, now()
from generate_series(1, 5) n;

-- ---- Day 5: one report is out, and the spotlight is back on day 4 only
update public.ghostline_runs
set started_at = started_at - interval '4 days', ends_at = ends_at - interval '4 days'
where kind = 'cycle';
set local role authenticated;
select set_config('request.jwt.claims',
    '{"sub":"0e000000-0000-0000-0000-000000000001","role":"authenticated"}', true);
insert into said values ('dashboard_day5', public.ghostline_dashboard());

-- ---- Day 16, short of testers: the guarantee adds seven days
reset role;
update public.ghostline_runs
set started_at = now() - interval '16 days 1 hour', ends_at = now() - interval '1 hour'
where kind = 'cycle';
select public.ghostline_tick();
insert into said
select 'after_short', to_jsonb(r) from public.ghostline_runs r where kind = 'cycle';

-- ---- Day 23 with everyone tested: done
update public.ghostline_runs set ends_at = now() - interval '1 minute' where kind = 'cycle';
insert into public.mission_checkins (mission_id, member, listing_id, day, seconds, device, at)
select '33330000-0000-0000-0000-000000000001',
       (lpad(n::text, 8, '0') || '-aaaa-0000-0000-000000000000')::uuid,
       '11110000-0000-0000-0000-000000000001',
       current_date - 1, 90, 'device-m' || n, now() - interval '1 day'
from generate_series(6, 16) n;
select public.ghostline_tick();
insert into said
select 'after_done', to_jsonb(r) from public.ghostline_runs r where kind = 'cycle';

do $$
declare
    r jsonb;
    v_run uuid;
    v_row jsonb;
begin
    select reply into r from said where step = 'free_start';
    if r->>'reason' is distinct from 'no_plan' then raise exception 'FAIL free_start: %', r; end if;

    -- The bonus: once per order, and again for a renewal.
    select reply into r from said where step = 'bonus_first';
    if (r->>'bonus')::int is distinct from 500 then raise exception 'FAIL bonus_first: %', r; end if;
    select reply into r from said where step = 'bonus_retry';
    if (r->>'bonus')::int is distinct from 0 then raise exception 'FAIL bonus_retry paid twice: %', r; end if;
    select reply into r from said where step = 'bonus_renewal';
    if (r->>'bonus')::int is distinct from 500 then raise exception 'FAIL bonus_renewal: %', r; end if;
    if (select sum(delta) from public.coin_entries
        where account = '0e000000-0000-0000-0000-000000000001' and reason = 'plan_bonus') <> 1000 then
        raise exception 'FAIL bonus ledger';
    end if;

    select reply into r from said where step = 'allowance_before';
    if (r->>'apps')::int is distinct from 1 or (r->>'used')::int is distinct from 0 or (r->>'needed')::int is distinct from 16 then
        raise exception 'FAIL allowance_before: %', r;
    end if;
    select reply into r from said where step = 'not_mine';
    if r->>'reason' is distinct from 'not_your_testing_app' then raise exception 'FAIL not_mine: %', r; end if;
    select reply into r from said where step = 'start';
    if (r->>'ok')::boolean is not true then raise exception 'FAIL start: %', r; end if;
    v_run := (r->>'run')::uuid;
    select reply into r from said where step = 'again';
    if r->>'reason' is distinct from 'already_running' then raise exception 'FAIL again: %', r; end if;
    select reply into r from said where step = 'second_app';
    if r->>'reason' is distinct from 'month_used' or r->>'next_at' is null then
        raise exception 'FAIL second_app: Premium starts one cycle a month: %', r;
    end if;
    select reply into r from said where step = 'allowance_after';
    if (r->>'used')::int is distinct from 1 then raise exception 'FAIL allowance_after: %', r; end if;

    -- Activation: the recruiting mission (16 seats) and mission A (16
    -- members) reach 32, twice the 16 needed. Mission B is not needed,
    -- and D is over.
    if public.run_reach(v_run) < 32 then
        raise exception 'FAIL activation reach %', public.run_reach(v_run);
    end if;
    if not exists (select 1 from public.ghostline_seats s
                   join public.mission_groups g on g.id = s.mission_id
                   where s.run_id = v_run and g.state = 'recruiting') then
        raise exception 'FAIL activation skipped the recruiting mission';
    end if;
    if not exists (select 1 from public.ghostline_seats
                   where run_id = v_run and mission_id = '33330000-0000-0000-0000-000000000001') then
        raise exception 'FAIL activation skipped the mission with most days left';
    end if;
    if exists (select 1 from public.ghostline_seats
               where run_id = v_run and mission_id = '33330000-0000-0000-0000-000000000004') then
        raise exception 'FAIL activation placed the app in a finished mission';
    end if;

    -- The spotlight: first on the Testing Board, marked, paying 50.
    select reply into r from said where step = 'board_day0';
    v_row := r->'testing'->0;
    if v_row->>'id' <> '11110000-0000-0000-0000-000000000001'
       or (v_row->>'spotlight')::boolean is not true
       or (v_row->>'spotlight_reward')::int <> 50 then
        raise exception 'FAIL board_day0: spotlight should lead the board: %', v_row;
    end if;
    select reply into r from said where step = 'status_day0';
    if r->>'state' is distinct from 'available' or (r->>'reward')::int is distinct from 50 then
        raise exception 'FAIL status_day0: %', r;
    end if;
    select reply into r from said where step = 'begin_day0';
    if (r->>'ok')::boolean is not true or (r->>'reward')::int is distinct from 50 then
        raise exception 'FAIL begin_day0: %', r;
    end if;
    if exists (select 1 from public.test_sessions
               where tester = '7e000000-0000-0000-0000-000000000002' and reward <> 0) then
        raise exception 'FAIL a spotlight test held the owner''s coins';
    end if;
    select reply into r from said where step = 'finish_day0';
    if (r->>'paid')::boolean is not true or (r->>'coins')::int is distinct from 50 then
        raise exception 'FAIL finish_day0: %', r;
    end if;
    if (select sum(delta) from public.coin_entries
        where account = '7e000000-0000-0000-0000-000000000002' and reason = 'spotlight_reward') <> 50 then
        raise exception 'FAIL the tester was not paid 50 by OneDevs';
    end if;
    if exists (select 1 from public.coin_entries
               where account = '0e000000-0000-0000-0000-000000000001' and reason = 'test_payment') then
        raise exception 'FAIL the owner paid for a spotlight test';
    end if;

    select reply into r from said where step = 'board_day1';
    if exists (select 1 from jsonb_array_elements(r->'testing') e
               where e->>'id' = '11110000-0000-0000-0000-000000000001') then
        raise exception 'FAIL board_day1: the app should be off the board between spotlights';
    end if;

    -- Day 5: one report. T from the Board and five members: six testers.
    select reply into r from said where step = 'dashboard_day5';
    v_row := r->0;
    if v_row->>'kind' <> 'cycle' or (v_row->>'needed')::int <> 16 then
        raise exception 'FAIL dashboard kind: %', v_row;
    end if;
    if jsonb_array_length(v_row->'insights') <> 1 then
        raise exception 'FAIL dashboard_day5 insights: %', v_row->'insights';
    end if;
    if (v_row->>'testers')::int <> 6 or (v_row->'insights'->0->>'day')::int <> 4 then
        raise exception 'FAIL dashboard_day5 numbers: %', v_row;
    end if;
    if (v_row->>'spotlight')::boolean or v_row->>'next_spotlight_at' is null then
        raise exception 'FAIL dashboard_day5 spotlight: %', v_row;
    end if;

    select reply into r from said where step = 'after_short';
    if r->>'state' is distinct from 'extended' or (r->>'ends_at')::timestamptz < now() + interval '6 days' then
        raise exception 'FAIL after_short: the guarantee should add 7 days: %', r;
    end if;
    select reply into r from said where step = 'after_done';
    if r->>'state' is distinct from 'completed' then raise exception 'FAIL after_done: %', r; end if;

    raise notice 'cycles: all checks passed';
end $$;

rollback;
