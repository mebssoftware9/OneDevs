-- How a mission ends, and leaving one before it starts. Rolled back at the end.
--
-- Seventeen developers. Number 3 joins and leaves again before the mission
-- fills, and number 17 takes the last seat. Twelve members use every other
-- app on 10 of the 14 days; four use them on 3. The fees of the four are
-- split among the twelve, and not one coin more.

begin;

insert into auth.users (
    instance_id, id, aud, role, email, encrypted_password,
    email_confirmed_at, created_at, updated_at,
    raw_app_meta_data, raw_user_meta_data, is_super_admin
)
select '00000000-0000-0000-0000-000000000000'::uuid,
       (lpad(n::text, 8, '0') || '-bbbb-0000-0000-000000000000')::uuid,
       'authenticated', 'authenticated', 'e' || n || '@onedevs.test', 'x', now(), now(), now(),
       '{}'::jsonb, '{}'::jsonb, false
from generate_series(1, 17) n;

insert into public.listings (id, owner, package_name, title, category, channel)
select (lpad(n::text, 8, '0') || '-cccc-0000-0000-000000000000')::uuid,
       (lpad(n::text, 8, '0') || '-bbbb-0000-0000-000000000000')::uuid,
       'com.e.app' || n, 'App ' || n, 'Tools', 'testing'
from generate_series(1, 17) n;

select public.grant_coins((lpad(n::text, 8, '0') || '-bbbb-0000-0000-000000000000')::uuid, 100)
from generate_series(1, 17) n;

do $$
declare
    v_mission uuid;
    r jsonb;
    n int;
    v_user uuid;
    v_before int;
    v_paid int;
    v_seats int[];
begin
    v_mission := public.recruiting_group();
    -- Whatever other missions exist are not this test's.
    update public.mission_groups set state = 'running', started_on = current_date
    where state = 'recruiting' and id <> v_mission;

    -- Members 1 to 5 join; 3 leaves with the fee back.
    for n in 1..5 loop
        perform set_config('request.jwt.claims',
            json_build_object('sub', lpad(n::text, 8, '0') || '-bbbb-0000-0000-000000000000')::text, true);
        r := public.take_seat(v_mission, (lpad(n::text, 8, '0') || '-cccc-0000-0000-000000000000')::uuid,
                              'device-e-' || n);
        if (r->>'joined')::boolean is not true then raise exception 'FAIL join %: %', n, r; end if;
    end loop;

    v_user := '00000003-bbbb-0000-0000-000000000000';
    perform set_config('request.jwt.claims', json_build_object('sub', v_user)::text, true);
    v_before := public.balance_of(v_user);
    r := public.leave_mission(v_mission);
    if (r->>'left')::boolean is not true or (r->>'coins')::int <> 100 then
        raise exception 'FAIL leave: %', r;
    end if;
    if public.balance_of(v_user) <> v_before + 100 then raise exception 'FAIL leave refund'; end if;
    r := public.leave_mission(v_mission);
    if r->>'reason' <> 'not_member' then raise exception 'FAIL leave twice: %', r; end if;
    select array_agg(seat order by seat) into v_seats from public.mission_seats where mission_id = v_mission;
    if v_seats <> array[1, 2, 3, 4] then raise exception 'FAIL seats after leave: %', v_seats; end if;
    if not exists (select 1 from public.mission_messages where mission_id = v_mission and kind = 'leave') then
        raise exception 'FAIL no leave line in the room';
    end if;

    -- Everyone else fills it: 6 to 17.
    for n in 6..17 loop
        perform set_config('request.jwt.claims',
            json_build_object('sub', lpad(n::text, 8, '0') || '-bbbb-0000-0000-000000000000')::text, true);
        r := public.take_seat(v_mission, (lpad(n::text, 8, '0') || '-cccc-0000-0000-000000000000')::uuid,
                              'device-e-' || n);
        if (r->>'joined')::boolean is not true then raise exception 'FAIL join %: %', n, r; end if;
    end loop;
    if (select state from public.mission_groups where id = v_mission) <> 'running' then
        raise exception 'FAIL the sixteenth seat did not start it';
    end if;

    -- Too late to leave now.
    perform set_config('request.jwt.claims',
        json_build_object('sub', '00000001-bbbb-0000-0000-000000000000')::text, true);
    r := public.leave_mission(v_mission);
    if r->>'reason' <> 'started' then raise exception 'FAIL leave after start: %', r; end if;

    -- Fourteen days ago. The first twelve seats test everything on 10 days;
    -- the last four on 3.
    update public.mission_groups set started_on = current_date - 14 where id = v_mission;
    insert into public.mission_checkins (mission_id, member, listing_id, day, seconds, device)
    select v_mission, s.member, o.listing_id, current_date - 14 + d, 60, 'device'
    from public.mission_seats s
    join public.mission_seats o on o.mission_id = s.mission_id and o.member <> s.member
    cross join generate_series(0, 13) d
    where s.mission_id = v_mission
      and d < case when s.seat <= 12 then 10 else 3 end;

    reset role;
    select coalesce(sum(delta), 0) into v_before from public.coin_entries;
    perform public.mission_tick();
    perform public.mission_tick();

    if (select state from public.mission_groups where id = v_mission) <> 'completed' then
        raise exception 'FAIL not completed';
    end if;
    if (select count(*) from public.mission_results where mission_id = v_mission and did_part) <> 12 then
        raise exception 'FAIL did_part count %',
            (select count(*) from public.mission_results where mission_id = v_mission and did_part);
    end if;
    -- Sixteen fees of 100 go to twelve people: 133 each and four of them 134.
    select coalesce(sum(delta), 0) into v_paid from public.coin_entries
    where ref = v_mission and reason in ('mission_refund', 'mission_bonus')
      and account <> '00000003-bbbb-0000-0000-000000000000';
    if v_paid <> 1600 then raise exception 'FAIL paid out %, expected 1600', v_paid; end if;
    if (select coalesce(sum(delta), 0) from public.coin_entries) - v_before <> 1600 then
        raise exception 'FAIL the tick paid twice or created coins';
    end if;
    if (select array_agg(coins order by coins) from public.mission_results
        where mission_id = v_mission and did_part) <>
       array[133,133,133,133,133,133,133,133,134,134,134,134] then
        raise exception 'FAIL split: %', (select array_agg(coins order by coins) from public.mission_results
                                         where mission_id = v_mission);
    end if;
    if exists (select 1 from public.mission_results where mission_id = v_mission and not did_part and coins <> 0) then
        raise exception 'FAIL someone who did not do their part was paid';
    end if;
    if (select body from public.mission_messages where mission_id = v_mission and kind = 'complete') <> '12/16' then
        raise exception 'FAIL closing line';
    end if;

    -- The member sees how it ended; a check-in is refused now.
    perform set_config('request.jwt.claims',
        json_build_object('sub', '00000001-bbbb-0000-0000-000000000000')::text, true);
    r := public.mission_view(v_mission);
    if r->>'state' <> 'completed' or (r->'result'->>'did_part')::boolean is not true
       or (r->'result'->>'coins')::int < 133 or (r->>'completers')::int <> 12
       or (r->>'days_needed')::int <> 10 then
        raise exception 'FAIL view: %', r;
    end if;
    r := public.mission_checkin(v_mission, '00000002-cccc-0000-0000-000000000000', 60, 'device-e-1');
    if r->>'reason' <> 'elapsed' then raise exception 'FAIL checkin after the end: %', r; end if;

    raise notice 'missions end: all checks passed';
end $$;

rollback;
