-- The economy, exercised end to end.
--
-- One DO block, so it runs anywhere: psql, the Supabase SQL editor, anything
-- that can send a statement. It finishes by raising an exception on purpose --
-- that rolls the whole block back, so it can be run against the live database
-- as often as you like and never leaves a row behind.
--
-- The property under test is conservation. Coins enter only through 'grant'.
-- Every mission, payout, lapse and close after that moves coins without
-- creating or destroying a single one.

do $$
declare
    dev uuid := '11111111-1111-1111-1111-111111111111';
    tst uuid := '22222222-2222-2222-2222-222222222222';
    v_listing uuid;
    v_mission uuid;
    v_enrol uuid;
    r jsonb;
    v int;
    s text;
    ok boolean;
    minted int;
    total int;
begin
    insert into auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, created_at, updated_at,
        raw_app_meta_data, raw_user_meta_data, is_super_admin
    ) values
    ('00000000-0000-0000-0000-000000000000', dev, 'authenticated', 'authenticated',
     'dev@onedevs.test', 'x', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false),
    ('00000000-0000-0000-0000-000000000000', tst, 'authenticated', 'authenticated',
     'tester@onedevs.test', 'x', now(), now(), now(), '{}'::jsonb, '{}'::jsonb, false);

    if (select count(*) from public.profiles where id in (dev, tst)) <> 2 then
        raise exception 'FAIL: signup did not create profiles';
    end if;

    perform public.grant_coins(dev, 1000);

    insert into public.listings (owner, package_name, title, category, channel)
    values (dev, 'com.devbangs.beampad', 'BeamPad', 'Tools', 'testing')
    returning id into v_listing;

    -- ---- the developer funds 2 slots x (3 days x 10 + 20 bonus) = 100
    perform set_config('request.jwt.claims', json_build_object('sub', dev)::text, true);
    v_mission := public.create_mission(v_listing, 2, 3, 10, 20);

    v := public.balance_of(dev);
    if v <> 900 then raise exception 'FAIL escrow: expected 900, got %', v; end if;

    -- ---- a developer must not be able to test their own app
    ok := false;
    begin
        perform public.join_mission(v_mission, 'device-owner-0001');
    exception when others then
        ok := sqlerrm like '%own app%';
    end;
    if not ok then raise exception 'FAIL: the owner joined their own mission'; end if;

    -- ---- a tester joins and works the required days
    perform set_config('request.jwt.claims', json_build_object('sub', tst)::text, true);
    v_enrol := public.join_mission(v_mission, 'device-tester-0001');

    r := public.record_day(v_enrol, 45);
    if not (r->>'counted')::boolean then
        raise exception 'FAIL: first day not counted: %', r;
    end if;

    r := public.record_day(v_enrol, 45);
    if (r->>'counted')::boolean then
        raise exception 'FAIL: the same day was paid twice';
    end if;

    -- Walk the recorded days backwards to stand in for the clock, so each new
    -- call lands on a fresh day with no gap.
    for i in 1..2 loop
        update public.mission_days set day = day - 1 where enrollment_id = v_enrol;
        r := public.record_day(v_enrol, 40);
        if not (r->>'counted')::boolean then
            raise exception 'FAIL: day % not counted: %', i + 1, r;
        end if;
    end loop;

    select state into s from public.enrollments where id = v_enrol;
    if s <> 'complete' then raise exception 'FAIL: enrollment state is %', s; end if;

    v := public.balance_of(tst);
    if v <> 50 then
        raise exception 'FAIL payout: tester has %, expected 50 (3x10 + 20)', v;
    end if;

    -- ---- the developer closes: one slot was never taken, so 50 comes back
    perform set_config('request.jwt.claims', json_build_object('sub', dev)::text, true);
    perform public.close_mission(v_mission);

    v := public.balance_of(dev);
    if v <> 950 then raise exception 'FAIL refund: developer has %, expected 950', v; end if;

    -- ---- and nothing was created or destroyed along the way
    select coalesce(sum(delta), 0) into minted
        from public.coin_entries where reason = 'grant';
    select coalesce(sum(delta), 0) into total from public.coin_entries;
    if minted <> total then
        raise exception 'FAIL: supply leaked -- granted %, in circulation %', minted, total;
    end if;

    raise exception 'ECONOMY OK -- all assertions passed, nothing kept';
end $$;
