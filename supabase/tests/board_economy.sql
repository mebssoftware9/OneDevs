-- The Board's economy end to end, as it works since 0007: listing is free,
-- each test is paid from the developer's balance when it is done, and the
-- reward is held while the tester has the app open. Rolled back at the end.

begin;

do $$
declare
    devA constant uuid := 'aaaaaaaa-1111-0000-0000-000000000001';
    devB constant uuid := 'bbbbbbbb-1111-0000-0000-000000000002';
    tester constant uuid := 'cccccccc-1111-0000-0000-000000000003';
    v_listing constant uuid := 'dddddddd-1111-0000-0000-000000000004';
    v_dear constant uuid := 'eeeeeeee-1111-0000-0000-000000000005';
    r jsonb;
    v_session uuid;
    v int;
    minted int;
    total int;
begin
    insert into auth.users (
        instance_id, id, aud, role, email, encrypted_password,
        email_confirmed_at, created_at, updated_at,
        raw_app_meta_data, raw_user_meta_data, is_super_admin
    )
    select '00000000-0000-0000-0000-000000000000', u.id, 'authenticated',
           'authenticated', u.email, 'x', now(), now(), now(),
           '{}'::jsonb, '{}'::jsonb, false
    from (values (devA, 'a@onedevs.test'), (devB, 'b@onedevs.test'),
                 (tester, 't@onedevs.test')) as u(id, email);

    -- ---- every new account is granted 75, once
    v := public.balance_of(devA);
    if v <> 75 then raise exception 'FAIL welcome: got %, expected 75', v; end if;

    -- ---- listing is free: the developer pays per test, not up front
    perform set_config('request.jwt.claims', json_build_object('sub', devA)::text, true);
    insert into public.listings (id, owner, package_name, title, category, channel)
    values (v_listing, devA, 'com.a.app', 'A app', 'Tools', 'testing');
    v := public.balance_of(devA);
    if v <> 75 then raise exception 'FAIL listing: charged up front, balance %', v; end if;

    -- ---- a test holds the reward without moving it
    perform set_config('request.jwt.claims', json_build_object('sub', tester)::text, true);
    r := public.begin_test(v_listing, 'device-tester-0001');
    if (r->>'ok')::boolean is not true or (r->>'reward')::int is distinct from 25 then
        raise exception 'FAIL begin: %', r;
    end if;
    v_session := (r->>'session')::uuid;
    if public.balance_of(devA) <> 75 or public.available_of(devA) <> 50 then
        raise exception 'FAIL hold: balance %, available %', public.balance_of(devA), public.available_of(devA);
    end if;

    -- ---- the server's clock decides 32 seconds, not the phone's
    r := public.finish_test(v_session, 45);
    if (r->>'paid')::boolean or r->>'reason' is distinct from 'too_short' then
        raise exception 'FAIL: paid before 32 seconds had passed: %', r;
    end if;

    update public.test_sessions set started_at = now() - interval '40 seconds' where id = v_session;
    r := public.finish_test(v_session, 45);
    if (r->>'paid')::boolean is not true or (r->>'coins')::int is distinct from 25 then
        raise exception 'FAIL finish: %', r;
    end if;
    if public.balance_of(devA) <> 50 or public.balance_of(tester) <> 100 then
        raise exception 'FAIL payment: developer %, tester %', public.balance_of(devA), public.balance_of(tester);
    end if;

    -- ---- asking again is answered, not paid again
    r := public.finish_test(v_session, 45);
    if (r->>'repeat')::boolean is not true or public.balance_of(tester) <> 100 then
        raise exception 'FAIL repeat: %, tester %', r, public.balance_of(tester);
    end if;

    -- ---- nobody tests their own app
    perform set_config('request.jwt.claims', json_build_object('sub', devA)::text, true);
    r := public.begin_test(v_listing, 'device-owner-0001');
    if r->>'reason' is distinct from 'own_app' then raise exception 'FAIL own app: %', r; end if;

    -- ---- one phone is one tester, whichever account it signs in with
    perform set_config('request.jwt.claims', json_build_object('sub', devB)::text, true);
    r := public.begin_test(v_listing, 'device-tester-0001');
    if r->>'reason' is distinct from 'device_used' then raise exception 'FAIL same phone: %', r; end if;

    -- ---- a reward the developer cannot cover is not offered
    insert into public.listings (id, owner, package_name, title, category, channel, reward)
    values (v_dear, devB, 'com.b.app', 'B app', 'Tools', 'testing', 100);
    perform set_config('request.jwt.claims', json_build_object('sub', tester)::text, true);
    r := public.begin_test(v_dear, 'device-tester-0001');
    if r->>'reason' is distinct from 'unfunded' then raise exception 'FAIL unfunded: %', r; end if;

    -- ---- and nothing was created or destroyed along the way
    select coalesce(sum(delta), 0) into minted from public.coin_entries where reason = 'grant';
    select coalesce(sum(delta), 0) into total from public.coin_entries;
    if minted <> total then
        raise exception 'FAIL: supply leaked -- granted %, in circulation %', minted, total;
    end if;

    raise notice 'board economy: all checks passed';
end $$;

rollback;
